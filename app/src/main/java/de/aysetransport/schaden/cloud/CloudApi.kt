package de.aysetransport.schaden.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Die Cloud hat die Anfrage beantwortet, aber mit einer Fehlermeldung. */
class CloudFehler(message: String) : Exception(message) {
    val pinFalsch: Boolean get() = message.orEmpty().contains("PIN")
    val gesperrt: Boolean get() = message.orEmpty().startsWith("GESPERRT")

    /** Meldung für die Anzeige, ohne technisches Präfix. */
    val anzeige: String get() = message.orEmpty().removePrefix("GESPERRT: ")
}

/**
 * Ruft das Cloud-Skript (Google Apps Script) auf – genauso wie die Web-App:
 * POST mit `{"fn": …, "args": […]}` als Text, Antwort `{"ok": …}` oder `{"error": …}`.
 */
open class CloudApi(private val url: String) {

    @Throws(IOException::class, CloudFehler::class)
    open suspend fun aufruf(fn: String, vararg args: JsonElement): JsonElement = withContext(Dispatchers.IO) {
        val anfrage = buildJsonObject {
            put("fn", fn)
            put("args", JsonArray(args.toList()))
        }.toString().toByteArray(Charsets.UTF_8)

        var antwort = verbinde(URL(url)).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "text/plain;charset=utf-8")
            outputStream.use { it.write(anfrage) }
        }
        // Google beantwortet den POST mit einer Weiterleitung; das Ergebnis liegt hinter der Zieladresse.
        repeat(MAX_WEITERLEITUNGEN) {
            if (antwort.responseCode !in 300..399) return@repeat
            val ziel = URL(URL(url), antwort.getHeaderField("Location") ?: throw IOException("Weiterleitung ohne Ziel"))
            antwort.disconnect()
            antwort = verbinde(ziel)
        }
        val text = try {
            if (antwort.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Die Cloud antwortet mit Fehler ${antwort.responseCode}")
            }
            antwort.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            antwort.disconnect()
        }
        val json = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: IllegalArgumentException) {
            throw IOException("Die Adresse gehört nicht zum Schadensfälle-Cloud-Skript.", e)
        }
        json["error"]?.let { throw CloudFehler(it.jsonPrimitive.content) }
        json["ok"] ?: JsonNull
    }

    private fun verbinde(ziel: URL) = (ziel.openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 20_000
        readTimeout = 120_000
    }

    companion object {
        private const val MAX_WEITERLEITUNGEN = 5

        fun text(wert: String?): JsonElement = if (wert == null) JsonNull else JsonPrimitive(wert)
        fun zahl(wert: Long?): JsonElement = if (wert == null) JsonNull else JsonPrimitive(wert)
        val leer: JsonObject = JsonObject(emptyMap())
    }
}
