package cl.inacap.alertnetmobile.sistemaalertaiot

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID

data class TelemetriaIoT(
    val distanciaCm: String = "--",
    val ultimaAlerta: String = "Sin registros",
    val btConectado: Boolean = false,
    val wifiDisponible: Boolean = false,
    val estadoRemotoTexto: String = "Sin sincronizar"
)

@Serializable
data class EstadoRemotoIoT(
    val id: Int = 1,
    val armado: Boolean = false,
    @SerialName("alarma_manual") val alarmaManual: Boolean = false,
    @SerialName("distancia_cm") val distanciaCm: String = "--",
    @SerialName("ultima_alerta") val ultimaAlerta: String = "Sin registros",
    @SerialName("ultimo_comando") val ultimoComando: String = "0"
)

class IoTConnectionManager(private val context: Context) {

    // UUID estándar Serial Port Profile (SPP) para módulo HC-05
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var reader: BufferedReader? = null

    private val _telemetria = MutableStateFlow(
        TelemetriaIoT(wifiDisponible = estaConectadoAWiFi())
    )
    val telemetria: StateFlow<TelemetriaIoT> = _telemetria

    fun estaConectadoAWiFi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun refrescarEstadoRed() {
        _telemetria.value = _telemetria.value.copy(
            wifiDisponible = estaConectadoAWiFi()
        )
    }

    @SuppressLint("MissingPermission")
    fun obtenerDispositivosEmparejados(): List<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices?.toList().orEmpty()
    }

    @SuppressLint("MissingPermission")
    suspend fun conectarBluetooth(
        macONombreHc05: String = "HC-05",
        onLineaRecibida: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val adapter = bluetoothAdapter
                ?: return@withContext Result.failure(Exception("Bluetooth no soportado en este dispositivo."))

            if (!adapter.isEnabled) {
                return@withContext Result.failure(Exception("Activa el Bluetooth antes de conectar."))
            }

            val dispositivo: BluetoothDevice = adapter.bondedDevices
                ?.firstOrNull {
                    it.address.equals(macONombreHc05, ignoreCase = true) ||
                            it.name?.contains(macONombreHc05, ignoreCase = true) == true
                }
                ?: return@withContext Result.failure(
                    Exception("No se encontró '$macONombreHc05' entre los dispositivos emparejados.")
                )

            cerrarConexionBluetooth()

            val nuevoSocket = dispositivo.createRfcommSocketToServiceRecord(sppUuid)
            adapter.cancelDiscovery()
            nuevoSocket.connect()

            socket = nuevoSocket
            outputStream = nuevoSocket.outputStream
            reader = BufferedReader(InputStreamReader(nuevoSocket.inputStream))

            _telemetria.value = _telemetria.value.copy(btConectado = true)

            while (socket?.isConnected == true) {
                val linea = reader?.readLine()?.trim()
                if (!linea.isNullOrEmpty()) {
                    procesarTramaEntrante(linea)
                    withContext(Dispatchers.Main) {
                        onLineaRecibida(linea)
                    }
                }
            }

            Result.success("Conectado a ${dispositivo.name ?: dispositivo.address}")
        } catch (e: Exception) {
            cerrarConexionBluetooth()
            Result.failure(Exception("Error Bluetooth: ${e.message ?: "fallo de conexión"}"))
        }
    }

    private fun procesarTramaEntrante(trama: String) {
        when {
            trama.startsWith("DIST:", ignoreCase = true) -> {
                val valor = trama.substringAfter(":").trim()
                _telemetria.value = _telemetria.value.copy(distanciaCm = valor)
            }
            trama.startsWith("ALERTA:", ignoreCase = true) -> {
                val detalle = trama.substringAfter(":").trim()
                _telemetria.value = _telemetria.value.copy(ultimaAlerta = detalle)
            }
        }
    }

    suspend fun enviarComandoBluetooth(comando: Char): Result<Unit> = withContext(Dispatchers.IO) {
        if (comando !in listOf('1', '0', 'A', 'a')) {
            return@withContext Result.failure(Exception("Comando no permitido: $comando"))
        }
        try {
            val out = outputStream
                ?: return@withContext Result.failure(Exception("No hay conexión activa con el HC-05."))
            out.write(comando.code)
            out.flush()
            Result.success(Unit)
        } catch (e: Exception) {
            cerrarConexionBluetooth()
            Result.failure(Exception("Error al enviar comando: ${e.message}"))
        }
    }

    fun cerrarConexionBluetooth() {
        try { reader?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        reader = null
        outputStream = null
        socket = null
        _telemetria.value = _telemetria.value.copy(btConectado = false)
    }

    suspend fun consultarEstadoRemotoWiFi(): Result<EstadoRemotoIoT> = withContext(Dispatchers.IO) {
        refrescarEstadoRed()
        if (!_telemetria.value.wifiDisponible) {
            _telemetria.value = _telemetria.value.copy(estadoRemotoTexto = "Sin Wi-Fi")
            return@withContext Result.failure(Exception("No hay conexión Wi-Fi activa."))
        }

        try {
            val registros = SupabaseProvider.client
                .from("estado_sistema")
                .select()
                .decodeList<EstadoRemotoIoT>()

            val actual = registros.firstOrNull { it.id == 1 }
                ?: return@withContext Result.failure(Exception("No existe el registro id=1 en estado_sistema."))

            _telemetria.value = _telemetria.value.copy(
                distanciaCm = actual.distanciaCm,
                ultimaAlerta = actual.ultimaAlerta,
                estadoRemotoTexto = "Sincronizado OK"
            )
            Result.success(actual)
        } catch (e: Exception) {
            _telemetria.value = _telemetria.value.copy(estadoRemotoTexto = "Error remoto")
            Result.failure(Exception("Error al leer estado remoto: ${e.message}"))
        }
    }

    suspend fun enviarComandoRemotoWiFi(
        comando: Char,
        armado: Boolean,
        alarmaManual: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        refrescarEstadoRed()
        if (!_telemetria.value.wifiDisponible) {
            _telemetria.value = _telemetria.value.copy(estadoRemotoTexto = "Sin Wi-Fi")
            return@withContext Result.failure(Exception("Sin Wi-Fi para enviar comando remoto."))
        }

        try {
            SupabaseProvider.client
                .from("estado_sistema")
                .update({
                    set("ultimo_comando", comando.toString())
                    set("armado", armado)
                    set("alarma_manual", alarmaManual)
                }) {
                    filter { eq("id", 1) }
                }

            _telemetria.value = _telemetria.value.copy(
                estadoRemotoTexto = "Comando '$comando' enviado"
            )
            Result.success(Unit)
        } catch (e: Exception) {
            _telemetria.value = _telemetria.value.copy(estadoRemotoTexto = "Error envío Wi-Fi")
            Result.failure(Exception("Error al enviar comando remoto: ${e.message}"))
        }
    }
}