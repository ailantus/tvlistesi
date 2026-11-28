package com.example.tvlistesi.cast

import android.content.Context

/** Telefondan TV'ye gönderilen uzaktan kontrol komutları. */
object CastAction {
    const val VOLUME_UP = "VOLUME_UP"
    const val VOLUME_DOWN = "VOLUME_DOWN"
    const val SEEK_BACK = "SEEK_BACK"
    const val SEEK_FORWARD = "SEEK_FORWARD"
    const val PLAY_PAUSE = "PLAY_PAUSE"
    const val STOP = "STOP"

    val ALL = setOf(VOLUME_UP, VOLUME_DOWN, SEEK_BACK, SEEK_FORWARD, PLAY_PAUSE, STOP)
}

/** Aynı Wi-Fi ağında bulunan bir TV Listesi alıcısı. */
data class CastDevice(
    val ip: String,
    val name: String,
    val model: String
)

/** Alıcıya göre kaydedilen eşleştirme kodları. */
object CastPrefs {
    private const val FILE = "yansitma_eslestirme"
    private const val KEY = "pin_"
    private const val LAST_IP = "son_cihaz_ip"
    private const val LAST_NAME = "son_cihaz_ad"
    private const val LAST_MODEL = "son_cihaz_model"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun pinFor(context: Context, key: String): String =
        prefs(context).getString(KEY + key, "").orEmpty()

    fun savePin(context: Context, key: String, pin: String) {
        prefs(context).edit().putString(KEY + key, pin).apply()
    }

    fun clearPin(context: Context, key: String) {
        prefs(context).edit().remove(KEY + key).apply()
    }

    /** Kısayolun doğrudan bağlanabilmesi için son kullanılan alıcıyı saklar. */
    fun saveLastDevice(context: Context, device: CastDevice) {
        prefs(context).edit()
            .putString(LAST_IP, device.ip)
            .putString(LAST_NAME, device.name)
            .putString(LAST_MODEL, device.model)
            .apply()
    }

    fun lastDevice(context: Context): CastDevice? {
        val ip = prefs(context).getString(LAST_IP, "").orEmpty()
        if (ip.isBlank()) return null
        return CastDevice(
            ip = ip,
            name = prefs(context).getString(LAST_NAME, "").orBlankAs("TvListesi"),
            model = prefs(context).getString(LAST_MODEL, "").orEmpty()
        )
    }

    private fun String?.orBlankAs(fallback: String) = if (isNullOrBlank()) fallback else this
}
