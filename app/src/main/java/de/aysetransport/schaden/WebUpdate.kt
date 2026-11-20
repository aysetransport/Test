package de.aysetransport.schaden

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Aktualisiert die Oberfläche (Web-App) ohne neue APK: Die neue Fassung wird von der festen Adresse der Web-App
 * geladen (nur dort, nie von einer Adresse, die die Seite vorgibt), jede Datei per SHA-256 gegen app-update.json
 * geprüft und erst dann aktiviert. Startet die neue Fassung zweimal nicht sauber (kein „web:ok“), fällt die App
 * auf die mitgelieferte Fassung zurück. Speicher (localStorage, IndexedDB) bleibt erhalten – die Adresse ändert sich nicht.
 */
class WebUpdate(private val context: Context) {
    companion object {
        /** Was die App selbst kann (Brücke). Steigt nur, wenn eine Oberfläche neue Gerätefunktionen braucht. */
        const val NATIV_API = 2
        const val BASIS = "https://aysetransport.github.io/schaden/"
        const val MANIFEST = "app-update.json"
    }

    private val ordner get() = File(context.filesDir, "webupdate")
    private val zustand get() = File(ordner, "zustand.json")

    private fun lies(datei: File): JsonObject? = runCatching { Json.parseToJsonElement(datei.readText()).jsonObject }.getOrNull()
    private fun gebuendelt(): JsonObject? = runCatching { context.assets.open(MANIFEST).use { Json.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)).jsonObject } }.getOrNull()
    private fun stand(o: JsonObject?): Long = runCatching { o!!["stand"]!!.jsonPrimitive.long }.getOrDefault(0L)

    /** Beim App-Start: kaputte oder veraltete Fassung verwerfen, Startversuch zählen. */
    @Synchronized
    fun beimStart() {
        val z = lies(zustand) ?: return
        val neu = stand(z) > stand(gebuendelt())
        val versuche = runCatching { z["versuche"]!!.jsonPrimitive.long }.getOrDefault(0L)
        val ok = runCatching { z["ok"]!!.jsonPrimitive.content == "true" }.getOrDefault(false)
        if (!neu || (!ok && versuche >= 2)) { ordner.deleteRecursively(); return }
        zustand.writeText(buildJsonObject { put("stand", stand(z)); put("ok", ok); put("versuche", versuche + 1); put("version", z["version"]?.jsonPrimitive?.content ?: "") }.toString())
    }

    /** Geladene Datei, falls eine neuere Fassung aktiv ist und sie enthält – sonst null (dann aus den Assets). */
    fun datei(pfad: String): File? {
        if (!zustand.exists()) return null
        val f = File(ordner, "dateien/$pfad").canonicalFile
        return if (f.path.startsWith(File(ordner, "dateien").canonicalPath + File.separator) && f.isFile) f else null
    }

    fun info(): JsonElement = buildJsonObject {
        val z = lies(zustand)
        put("nativ", NATIV_API)
        put("app", runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "")
        put("gebuendelt", stand(gebuendelt()))
        put("aktiv", if (z != null) stand(z) else 0L)
        put("version", z?.get("version")?.jsonPrimitive?.content ?: "")
    }

    @Synchronized
    fun ok() {
        val z = lies(zustand) ?: return
        zustand.writeText(buildJsonObject { put("stand", stand(z)); put("ok", true); put("versuche", 0); put("version", z["version"]?.jsonPrimitive?.content ?: "") }.toString())
    }

    @Synchronized
    fun zuruecksetzen() { ordner.deleteRecursively() }

    private fun hole(pfad: String): ByteArray {
        val v = URL(BASIS + pfad + "?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
        v.connectTimeout = 15000; v.readTimeout = 60000; v.useCaches = false
        try {
            if (v.responseCode != 200) throw IllegalStateException("Update nicht erreichbar ($pfad: ${v.responseCode})")
            return v.inputStream.use { it.readBytes() }
        } finally { v.disconnect() }
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Lädt die neueste Fassung (nur geänderte Dateien), prüft sie und aktiviert sie beim nächsten Laden. */
    @Synchronized
    fun installieren(): JsonElement {
        val m = Json.parseToJsonElement(hole(MANIFEST).toString(Charsets.UTF_8)).jsonObject
        val minNativ = runCatching { m["minNativ"]!!.jsonPrimitive.long }.getOrDefault(1L)
        if (minNativ > NATIV_API) throw IllegalStateException("Für diese Version ist ein App-Update nötig")
        val neuStand = stand(m)
        if (neuStand <= maxOf(stand(gebuendelt()), stand(lies(zustand)))) return buildJsonObject { put("stand", stand(lies(zustand))); put("neu", false) }
        val alt = gebuendelt()?.get("dateien")?.jsonObject
        val staging = File(context.filesDir, "webupdate-neu").apply { deleteRecursively(); mkdirs() }
        val dateien = m["dateien"]!!.jsonObject
        for ((pfad, wert) in dateien) {
            require(!pfad.contains("..") && !pfad.startsWith("/")) { "Ungültiger Pfad im Update" }
            val soll = wert.jsonObject["sha256"]!!.jsonPrimitive.content
            if (alt?.get(pfad)?.jsonObject?.get("sha256")?.jsonPrimitive?.content == soll) continue // unverändert: aus der App
            val vorhanden = datei(pfad)?.readBytes()
            val bytes = if (vorhanden != null && sha(vorhanden) == soll) vorhanden else hole(pfad)
            if (sha(bytes) != soll) throw IllegalStateException("Prüfsumme stimmt nicht: $pfad")
            File(staging, "dateien/$pfad").apply { parentFile?.mkdirs() }.writeBytes(bytes)
        }
        File(staging, "zustand.json").writeText(buildJsonObject { put("stand", neuStand); put("ok", false); put("versuche", 0); put("version", m["version"]?.jsonPrimitive?.content ?: "") }.toString())
        ordner.deleteRecursively()
        if (!staging.renameTo(ordner)) throw IllegalStateException("Update konnte nicht gespeichert werden")
        return buildJsonObject { put("stand", neuStand); put("neu", true) }
    }
}
