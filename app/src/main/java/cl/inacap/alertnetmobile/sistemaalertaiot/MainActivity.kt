package cl.inacap.alertnetmobile.sistemaalertaiot

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

@Serializable
private data class PerfilUsuario(
    val id: String = "",
    val rol: String = "operador",
    val activo: Boolean = true
)

private fun formatearErrorLogin(rawMessage: String?): String {
    val msg = rawMessage.orEmpty().lowercase()
    return when {
        "invalid_credentials" in msg ||
                "invalid login credentials" in msg ||
                "invalid email or password" in msg ||
                "email not confirmed" in msg -> "correo o contraseña incorrecta"
        "unable to resolve host" in msg ||
                "network" in msg ||
                "timeout" in msg ||
                "connection" in msg -> "No se pudo conectar con el servidor. Revisa tu conexión a internet."
        msg.isBlank() -> "correo o contraseña incorrecta"
        else -> "correo o contraseña incorrecta"
    }
}

private suspend fun crearUsuarioDesdeEdgeFunction(
    email: String,
    password: String,
    rol: String
): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        val rawUrl = SupabaseProvider.client.supabaseUrl.trim().trimEnd('/')
        val baseUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl
        } else {
            "https://$rawUrl"
        }

        val accessToken = SupabaseProvider.client.auth.currentAccessTokenOrNull()
            ?: throw Exception("No hay sesión activa de administrador.")

        val endpoint = URL("$baseUrl/functions/v1/crear_usuario")
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
        }

        val payload = JSONObject().apply {
            put("email", email)
            put("password", password)
            put("rol", rol)
        }.toString()

        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(payload)
            writer.flush()
        }

        val statusCode = connection.responseCode
        val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
        val bodyText = stream?.let { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
        }.orEmpty()

        connection.disconnect()

        val json = runCatching { JSONObject(bodyText) }.getOrNull()
        if (statusCode !in 200..299) {
            throw Exception(
                json?.optString("error").takeUnless { it.isNullOrBlank() }
                    ?: "Error al crear cuenta ($statusCode)"
            )
        }

        "Cuenta creada ($rol): $email"
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val iotManager = IoTConnectionManager(applicationContext)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    val scope = rememberCoroutineScope()

                    NavHost(
                        navController = navController,
                        startDestination = "login"
                    ) {
                        composable("login") {
                            LoginScreen(
                                onLoginSuccess = { rol ->
                                    when (rol.lowercase()) {
                                        "administrador", "admin" -> {
                                            navController.navigate("admin") {
                                                popUpTo("login") { inclusive = true }
                                            }
                                        }
                                        "operador" -> {
                                            navController.navigate("operador") {
                                                popUpTo("login") { inclusive = true }
                                            }
                                        }
                                        else -> {
                                            navController.navigate("usuarios") {
                                                popUpTo("login") { inclusive = true }
                                            }
                                        }
                                    }
                                }
                            )
                        }

                        composable("admin") {
                            AdminScreen(
                                iotManager = iotManager,
                                onLogout = {
                                    scope.launch {
                                        runCatching { SupabaseProvider.client.auth.signOut() }
                                        navController.navigate("login") {
                                            popUpTo(0) { inclusive = true }
                                        }
                                    }
                                }
                            )
                        }

                        composable("operador") {
                            OperatorScreen(
                                iotManager = iotManager,
                                onLogout = {
                                    iotManager.cerrarConexionBluetooth()
                                    scope.launch {
                                        runCatching { SupabaseProvider.client.auth.signOut() }
                                        navController.navigate("login") {
                                            popUpTo(0) { inclusive = true }
                                        }
                                    }
                                }
                            )
                        }

                        composable("usuarios") {
                            UsuarioGeneralScreen(
                                onLogout = {
                                    scope.launch {
                                        runCatching { SupabaseProvider.client.auth.signOut() }
                                        navController.navigate("login") {
                                            popUpTo(0) { inclusive = true }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LoginScreen(
    onLoginSuccess: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var correo by remember { mutableStateOf("") }
    var contrasena by remember { mutableStateOf("") }
    var cargando by remember { mutableStateOf(false) }
    var mensajeError by remember { mutableStateOf<String?>(null) }
    var mensajeInfo by remember { mutableStateOf<String?>(null) }

    var mostrarDialogoRecuperar by remember { mutableStateOf(false) }
    var correoRecuperar by remember { mutableStateOf("") }
    var enviandoRecuperacion by remember { mutableStateOf(false) }
    var errorRecuperar by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
    ) {
        Text("ALERTNET", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Iniciar sesión", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Ingresa con tu cuenta registrada para acceder según tu rol.",
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            value = correo,
            onValueChange = {
                correo = it
                mensajeError = null
                mensajeInfo = null
            },
            label = { Text("Correo electrónico") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = contrasena,
            onValueChange = {
                contrasena = it
                mensajeError = null
                mensajeInfo = null
            },
            label = { Text("Contraseña") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        if (mensajeError != null) {
            Text(
                text = mensajeError!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (mensajeInfo != null) {
            Text(
                text = mensajeInfo!!,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Button(
            onClick = {
                val emailLimpio = correo.trim()
                if (emailLimpio.isEmpty() || contrasena.isEmpty()) {
                    mensajeError = "Completa tu correo y contraseña."
                    return@Button
                }
                scope.launch {
                    cargando = true
                    mensajeError = null
                    mensajeInfo = null
                    try {
                        SupabaseProvider.client.auth.signInWith(Email) {
                            email = emailLimpio
                            password = contrasena
                        }
                        val userId = SupabaseProvider.client.auth.currentUserOrNull()?.id
                            ?: throw Exception("No se pudo obtener el ID de usuario.")

                        val perfil = SupabaseProvider.client
                            .from("perfiles")
                            .select()
                            .decodeList<PerfilUsuario>()
                            .firstOrNull { it.id == userId }

                        if (perfil != null && !perfil.activo) {
                            mensajeError = "Tu cuenta se encuentra inactiva."
                        } else {
                            onLoginSuccess(perfil?.rol ?: "usuario")
                        }
                    } catch (e: Exception) {
                        mensajeError = formatearErrorLogin(e.message)
                    } finally {
                        cargando = false
                    }
                }
            },
            enabled = !cargando,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (cargando) "Ingresando..." else "Ingresar")
        }

        TextButton(
            onClick = {
                correoRecuperar = correo.trim()
                errorRecuperar = null
                mostrarDialogoRecuperar = true
            },
            enabled = !cargando,
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("¿Olvidaste tu contraseña?")
        }
    }

    if (mostrarDialogoRecuperar) {
        AlertDialog(
            onDismissRequest = {
                if (!enviandoRecuperacion) {
                    mostrarDialogoRecuperar = false
                }
            },
            title = { Text("Recuperar contraseña") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Escribe tu correo electrónico y te enviaremos un enlace para restablecer tu contraseña.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = correoRecuperar,
                        onValueChange = {
                            correoRecuperar = it
                            errorRecuperar = null
                        },
                        label = { Text("Correo electrónico") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (errorRecuperar != null) {
                        Text(
                            text = errorRecuperar!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val destino = correoRecuperar.trim()
                        if (destino.isEmpty() || "@" !in destino) {
                            errorRecuperar = "Ingresa un correo electrónico válido."
                            return@Button
                        }
                        scope.launch {
                            enviandoRecuperacion = true
                            errorRecuperar = null
                            try {
                                SupabaseProvider.client.auth.resetPasswordForEmail(destino)
                                correo = destino
                                mensajeInfo = "Se envió un correo de recuperación a $destino."
                                mensajeError = null
                                mostrarDialogoRecuperar = false
                            } catch (e: Exception) {
                                errorRecuperar = "No se pudo enviar el correo de recuperación. Intenta nuevamente."
                            } finally {
                                enviandoRecuperacion = false
                            }
                        }
                    },
                    enabled = !enviandoRecuperacion
                ) {
                    Text(if (enviandoRecuperacion) "Enviando..." else "Enviar")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { mostrarDialogoRecuperar = false },
                    enabled = !enviandoRecuperacion
                ) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
    iotManager: IoTConnectionManager,
    onLogout: () -> Unit
) {
    val telemetria by iotManager.telemetria.collectAsState()
    val scope = rememberCoroutineScope()

    var armado by remember { mutableStateOf(false) }
    var alarmaManual by remember { mutableStateOf(false) }
    var estadoMensaje by remember { mutableStateOf("Listo para sincronizar") }

    var formularioVisible by remember { mutableStateOf(false) }
    var nuevoCorreo by remember { mutableStateOf("") }
    var nuevaContrasena by remember { mutableStateOf("") }
    var nuevoRol by remember { mutableStateOf("operador") }
    var creandoUsuario by remember { mutableStateOf(false) }
    var resultadoUsuario by remember { mutableStateOf<String?>(null) }
    var errorUsuario by remember { mutableStateOf<String?>(null) }

    suspend fun refrescarRemoto() {
        iotManager.consultarEstadoRemotoWiFi()
            .onSuccess { remoto: EstadoRemotoIoT ->
                armado = remoto.armado
                alarmaManual = remoto.alarmaManual
                estadoMensaje = "Sincronizado OK (cmd '${remoto.ultimoComando}')"
            }
            .onFailure { err ->
                estadoMensaje = err.message ?: "Error al consultar estado remoto"
            }
    }

    LaunchedEffect(Unit) {
        refrescarRemoto()
    }

    fun enviarAccionRemota(comando: Char, nuevoArmado: Boolean, nuevaAlarma: Boolean) {
        scope.launch {
            iotManager.enviarComandoRemotoWiFi(
                comando = comando,
                armado = nuevoArmado,
                alarmaManual = nuevaAlarma
            ).onSuccess {
                armado = nuevoArmado
                alarmaManual = nuevaAlarma
                estadoMensaje = "Comando '$comando' sincronizado en Supabase"
            }.onFailure { err ->
                estadoMensaje = err.message ?: "Error al enviar comando remoto"
            }
        }
    }

    fun registrarNuevoUsuario() {
        val correoLimpio = nuevoCorreo.trim()
        if (correoLimpio.isEmpty() || "@" !in correoLimpio) {
            errorUsuario = "Ingresa un correo electrónico válido."
            resultadoUsuario = null
            return
        }
        if (nuevaContrasena.length < 6) {
            errorUsuario = "La contraseña debe tener al menos 6 caracteres."
            resultadoUsuario = null
            return
        }

        scope.launch {
            creandoUsuario = true
            errorUsuario = null
            resultadoUsuario = null

            crearUsuarioDesdeEdgeFunction(
                email = correoLimpio,
                password = nuevaContrasena,
                rol = nuevoRol
            ).onSuccess { msg ->
                resultadoUsuario = msg
                nuevoCorreo = ""
                nuevaContrasena = ""
            }.onFailure { err ->
                errorUsuario = err.message ?: "No se pudo crear la cuenta."
            }

            creandoUsuario = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("ALERTNET", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Panel de Administrador", style = MaterialTheme.typography.headlineSmall)
        Text("Bienvenido, Administrador.", style = MaterialTheme.typography.bodyMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("GESTIÓN DE CUENTAS", style = MaterialTheme.typography.labelMedium)
                Button(
                    onClick = {
                        formularioVisible = !formularioVisible
                        errorUsuario = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (formularioVisible) "Ocultar formulario" else "Crear operador o administrador")
                }

                if (resultadoUsuario != null) {
                    Text(
                        text = resultadoUsuario!!,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                AnimatedVisibility(visible = formularioVisible) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        OutlinedTextField(
                            value = nuevoCorreo,
                            onValueChange = {
                                nuevoCorreo = it
                                errorUsuario = null
                                resultadoUsuario = null
                            },
                            label = { Text("Correo electrónico") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = nuevaContrasena,
                            onValueChange = {
                                nuevaContrasena = it
                                errorUsuario = null
                                resultadoUsuario = null
                            },
                            label = { Text("Contraseña (mín. 6 caracteres)") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Rol asignado:", style = MaterialTheme.typography.bodySmall)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf("operador", "administrador").forEach { rolOpcion ->
                                FilterChip(
                                    selected = nuevoRol == rolOpcion,
                                    onClick = { nuevoRol = rolOpcion },
                                    label = { Text(rolOpcion) }
                                )
                            }
                        }
                        if (errorUsuario != null) {
                            Text(
                                text = errorUsuario!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(
                            onClick = { registrarNuevoUsuario() },
                            enabled = !creandoUsuario,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (creandoUsuario) "Creando cuenta..." else "Guardar cuenta")
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("MONITOREO DEL SISTEMA", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = if (armado) "Estado: ARMADO" else "Estado: DESARMADO",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text("Distancia del sensor: ${telemetria.distanciaCm} cm")
                Text("Últimas alertas: ${telemetria.ultimaAlerta}")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CONEXIÓN REMOTA (WI-FI)", style = MaterialTheme.typography.labelMedium)
                Text("Wi-Fi: ${if (telemetria.wifiDisponible) "Conectado" else "Sin conexión"}")
                Text("Sincronización remota: ${telemetria.estadoRemotoTexto}")
                Text("Detalle: $estadoMensaje", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = { scope.launch { refrescarRemoto() } },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Verificar estado Wi-Fi")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CONTROL DE ALARMA", style = MaterialTheme.typography.labelMedium)
                Text("Alarma manual: ${if (alarmaManual) "ACTIVA" else "INACTIVA"}")
                Button(
                    onClick = { enviarAccionRemota('1', nuevoArmado = true, nuevaAlarma = alarmaManual) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Armar sistema")
                }
                OutlinedButton(
                    onClick = { enviarAccionRemota('0', nuevoArmado = false, nuevaAlarma = false) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Desarmar sistema")
                }
                Button(
                    onClick = { enviarAccionRemota('A', nuevoArmado = armado, nuevaAlarma = true) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Activar alarma manual")
                }
                OutlinedButton(
                    onClick = { enviarAccionRemota('0', nuevoArmado = armado, nuevaAlarma = false) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Detener alarma manual")
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
            Text("Cerrar sesión")
        }
    }
}

@Composable
fun OperatorScreen(
    iotManager: IoTConnectionManager,
    onLogout: () -> Unit
) {
    val telemetria by iotManager.telemetria.collectAsState()
    val scope = rememberCoroutineScope()

    var armado by remember { mutableStateOf(false) }
    var alarmaManual by remember { mutableStateOf(false) }
    var estadoBtTexto by remember { mutableStateOf("HC-05: Sin conectar (modo nube activo)") }
    var estadoSyncTexto by remember { mutableStateOf("Sincronizando con Supabase...") }

    suspend fun sincronizarDesdeSupabase() {
        iotManager.consultarEstadoRemotoWiFi()
            .onSuccess { remoto: EstadoRemotoIoT ->
                armado = remoto.armado
                alarmaManual = remoto.alarmaManual
                estadoSyncTexto = "Supabase sincronizado (cmd '${remoto.ultimoComando}')"
            }
            .onFailure { err ->
                estadoSyncTexto = err.message ?: "Sin sincronización remota"
            }
    }

    val permisoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        scope.launch {
            estadoBtTexto = "Conectando a HC-05..."
            iotManager.conectarBluetooth("HC-05")
                .onSuccess { msg -> estadoBtTexto = "HC-05: $msg" }
                .onFailure { err -> estadoBtTexto = "HC-05: ${err.message ?: "no disponible"}" }
        }
    }

    fun solicitarConexionHc05() {
        val permisos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        permisoLauncher.launch(permisos)
    }

    fun ejecutarControlOperador(comando: Char, nuevoArmado: Boolean, nuevaAlarma: Boolean) {
        scope.launch {
            if (telemetria.btConectado) {
                iotManager.enviarComandoBluetooth(comando)
                    .onSuccess {
                        armado = nuevoArmado
                        alarmaManual = nuevaAlarma
                        estadoBtTexto = "HC-05: Comando '$comando' enviado"
                    }
                    .onFailure { err ->
                        estadoBtTexto = "HC-05: ${err.message}"
                    }
            }

            iotManager.enviarComandoRemotoWiFi(
                comando = comando,
                armado = nuevoArmado,
                alarmaManual = nuevaAlarma
            ).onSuccess {
                armado = nuevoArmado
                alarmaManual = nuevaAlarma
                estadoSyncTexto = if (telemetria.btConectado) {
                    "BT + Supabase actualizados ('$comando')"
                } else {
                    "Sincronizado en Supabase sin HC-05 ('$comando')"
                }
            }.onFailure { err ->
                estadoSyncTexto = err.message ?: "Error al actualizar estado en Supabase"
            }
        }
    }

    LaunchedEffect(Unit) {
        sincronizarDesdeSupabase()
    }

    DisposableEffect(Unit) {
        onDispose {
            iotManager.cerrarConexionBluetooth()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("ALERTNET", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Panel de Operador", style = MaterialTheme.typography.headlineSmall)
        Text("Monitoreo y control local / respaldo nube.", style = MaterialTheme.typography.bodyMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CONEXIÓN BLUETOOTH", style = MaterialTheme.typography.labelMedium)
                Text(estadoBtTexto)
                Text("Estado nube: $estadoSyncTexto", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { solicitarConexionHc05() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Conectar HC-05")
                    }
                    OutlinedButton(
                        onClick = { scope.launch { sincronizarDesdeSupabase() } },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Sync Supabase")
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ESTADO DEL SISTEMA", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = if (armado) "ARMADO" else "DESARMADO",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text("Distancia detectada: ${telemetria.distanciaCm} cm")
                Text("Última alerta: ${telemetria.ultimaAlerta}")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CONTROL LOCAL", style = MaterialTheme.typography.labelMedium)
                Text("Alarma manual: ${if (alarmaManual) "ACTIVA" else "INACTIVA"}")
                Button(
                    onClick = { ejecutarControlOperador('1', nuevoArmado = true, nuevaAlarma = alarmaManual) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Armar sistema")
                }
                OutlinedButton(
                    onClick = { ejecutarControlOperador('0', nuevoArmado = false, nuevaAlarma = false) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Desarmar sistema")
                }
                Button(
                    onClick = { ejecutarControlOperador('A', nuevoArmado = armado, nuevaAlarma = true) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Activar alarma manual")
                }
                OutlinedButton(
                    onClick = { ejecutarControlOperador('0', nuevoArmado = armado, nuevaAlarma = false) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Detener alarma manual")
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
            Text("Cerrar sesión")
        }
    }
}

@Composable
fun UsuarioGeneralScreen(onLogout: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("ALERTNET", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Vista de Usuario", style = MaterialTheme.typography.headlineSmall)
        Text("Sesión activa sin privilegios de operador o administrador.")
        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
            Text("Cerrar sesión")
        }
    }
}