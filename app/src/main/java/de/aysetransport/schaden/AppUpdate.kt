package de.aysetransport.schaden

import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Update der App selbst (neue APK) – Gegenstück zu electron-updater in der Windows-App. Die APK kommt nur vom Release
 * „android-app“ in aysetransport/schaden und wird per SHA-256 gegen android-update.json geprüft. Android installiert sie
 * nur nach Bestätigung durch den Benutzer und nur mit derselben Signatur wie die installierte App.
 */
class AppUpdate(private val activity: MainActivity) {
    companion object {
        const val BASIS = "https://github.com/aysetransport/schaden/releases/download/android-app/"
    }

    private val apk get() = File(activity.cacheDir, "update/Ayse-CRM.apk")
    private var bereit = ""

    private fun oeffne(pfad: String): HttpURLConnection {
        val v = URL(BASIS + pfad).openConnection() as HttpURLConnection
        v.connectTimeout = 15000; v.readTimeout = 60000; v.useCaches = false
        if (v.responseCode != 200) { v.disconnect(); throw IOException("App-Update nicht erreichbar ($pfad: ${v.responseCode})") }
        return v
    }

    /** Prüft auf eine neuere APK und lädt sie (einmal) herunter. Läuft im Hintergrund. */
    @Synchronized
    fun pruefen(): JsonElement {
        val v = oeffne("android-update.json")
        val m = try { Json.parseToJsonElement(v.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)).jsonObject } finally { v.disconnect() }
        val eigene = PackageInfoCompat.getLongVersionCode(activity.packageManager.getPackageInfo(activity.packageName, 0))
        val name = m["versionName"]?.jsonPrimitive?.content ?: ""
        bereit = ""
        if (m["versionCode"]!!.jsonPrimitive.long > eigene) {
            val soll = m["sha256"]!!.jsonPrimitive.content
            if (!apk.isFile || sha(apk) != soll) {
                apk.parentFile!!.mkdirs()
                val teil = File(apk.path + ".teil")
                val l = oeffne("Ayse-CRM.apk")
                try { l.inputStream.use { ein -> teil.outputStream().use { ein.copyTo(it) } } } finally { l.disconnect() }
                if (sha(teil) != soll) { teil.delete(); throw IOException("Prüfsumme der neuen App stimmt nicht") }
                if (!teil.renameTo(apk)) throw IOException("App-Update konnte nicht gespeichert werden")
            }
            bereit = name
        }
        return buildJsonObject { put("bereit", bereit.isNotEmpty()); put("version", name) }
    }

    /** Startet die Installation (Android fragt den Benutzer). Läuft auf dem Haupt-Thread. */
    fun installieren() {
        if (bereit.isEmpty() || !apk.isFile) throw IOException("Kein App-Update geladen")
        if (!activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${activity.packageName}".toUri()))
            throw IOException("Bitte „Apps aus dieser Quelle zulassen“ einschalten und dann erneut auf Installieren tippen.")
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun sha(datei: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        datei.inputStream().use { ein -> val puffer = ByteArray(1 shl 16); while (true) { val n = ein.read(puffer); if (n < 0) break; md.update(puffer, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
