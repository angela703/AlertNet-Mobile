
package cl.inacap.alertnetmobile.sistemaalertaiot

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.functions.Functions

object SupabaseProvider {

    private const val SUPABASE_URL =
        "https://pagxsezmwveydvtdrkri.supabase.co"

    private const val SUPABASE_KEY =
        "sb_publishable_o67-ta8v6yPccGMm0cBlFw_HzkkdZtp"

    val client by lazy {
        createSupabaseClient(
            supabaseUrl = SUPABASE_URL,
            supabaseKey = SUPABASE_KEY
        ) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
            install(Functions)
        }
    }
}
