package de.aysetransport.schaden

import android.webkit.JavascriptInterface
import de.aysetransport.schaden.mail.MailFehler
import de.aysetransport.schaden.mail.MailKonten
import de.aysetransport.schaden.mail.Postfach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Die Schnittstelle, über die die Web-App (android.js → window.desktop) Gerätefunktionen aufruft – dieselben
 * Aufrufe wie in der Windows-App (desktop/preload.js). Jeder Aufruf antwortet über window.__androidAntwort.
 */
class Bruecke(
    private val bereich: CoroutineScope,
    private val konten: MailKonten,
    private val geraet: () -> Geraet,
    private val antworte: (String) -> Unit,
    private val zielBereit: () -> Unit = {},
    private val webUpdate: WebUpdate? = null,
    private val appUpdate: AppUpdate? = null,
) {
    @JavascriptInterface
    fun aufruf(id: String, name: String, argumente: String) {
        bereich.launch {
            val antwort = try {
                val args = Json.parseToJsonElement(argumente.ifBlank { "[]" }).jsonArray
                buildJsonObject { put("ok", ausfuehren(name, args)) }
            } catch (e: Throwable) {
                buildJsonObject {
                    put("fehler", e.message ?: e.javaClass.simpleName)
                    put("anmeldung", e is MailFehler && e.anmeldung)
                }
            }
            antworte("window.__androidAntwort(${JsonPrimitive(id)}, ${alsJs(antwort)})")
        }
    }

    suspend fun ausfuehren(name: String, a: JsonArray): JsonElement {
        fun t(i: Int): String? = (a.getOrNull(i) as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun o(i: Int): JsonObject = (a.getOrNull(i) as? JsonObject) ?: JsonObject(emptyMap())
        fun bytes(i: Int) = Base64.getDecoder().decode(t(i).orEmpty())
        return when (name) {
            "mail:anbieter" -> Postfach.ANBIETER
            "mail:konto" -> konten.konto(t(0))
            "mail:einrichten" -> io { konten.einrichten(o(0), t(1).orEmpty(), t(2)) }
            "mail:trennen" -> JsonPrimitive(konten.trennen(t(0)))
            "mail:sende" -> io { JsonPrimitive(konten.sende(o(0), t(1))) }
            "mail:ruf" -> io { konten.ruf(t(0).orEmpty(), o(1), t(2)) }
            "benachrichtige" -> JsonPrimitive(geraet().benachrichtige(t(0).orEmpty(), t(1).orEmpty(), t(2)))
            "datei:oeffnen" -> { geraet().oeffne(t(0) ?: "Anlage", bytes(1)); JsonPrimitive(true) }
            "datei:speichern" -> JsonPrimitive(io { geraet().speichere(t(0) ?: "Datei", bytes(1)) })
            "extern:oeffnen" -> { geraet().oeffneExtern(t(0).orEmpty()); JsonPrimitive(true) }
            "pdf:erzeugen" -> JsonPrimitive(Base64.getEncoder().encodeToString(geraet().pdf(t(0).orEmpty())))
            "drucken" -> { geraet().drucke(t(0).orEmpty()); JsonPrimitive(true) }
            "ziel:bereit" -> { zielBereit(); JsonNull }
            "web:info" -> webUpdate?.info() ?: JsonNull
            "web:installieren" -> io { requireNotNull(webUpdate).installieren() }
            "web:ok" -> { webUpdate?.ok(); JsonPrimitive(true) }
            "web:zuruecksetzen" -> { webUpdate?.zuruecksetzen(); JsonPrimitive(true) }
            "app:pruefen" -> io { requireNotNull(appUpdate).pruefen() }
            "app:installieren" -> { requireNotNull(appUpdate).installieren(); JsonPrimitive(true) }
            else -> throw IllegalArgumentException("Unbekannte Funktion: $name")
        }
    }

    private suspend fun <T> io(arbeit: () -> T): T = withContext(Dispatchers.IO) { arbeit() }

    companion object {
        /** JSON als JavaScript-Ausdruck (U+2028/2029 sind in JSON erlaubt, in älterem JavaScript nicht). */
        fun alsJs(j: JsonElement): String = j.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    }
}
