package de.aysetransport.schaden

import android.content.Context
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream

/**
 * Liefert die Web-App aus den Assets unter https://appassets.androidplatform.net/ aus – eine echte
 * https-Adresse, damit Speicher (localStorage, IndexedDB) und der Abgleich mit der Cloud wie im Browser
 * funktionieren. In index.html wird vor allen anderen Skripten android.js eingehängt.
 */
object WebInhalt {
    const val HOST = "appassets.androidplatform.net"
    const val START = "https://$HOST/index.html"

    /** Hängt die Android-Brücke als erstes Skript in den Kopf der Seite. */
    fun mitBruecke(html: String): String {
        val kopf = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html) ?: return "<script src=\"android.js\"></script>$html"
        return html.substring(0, kopf.range.last + 1) + "<script src=\"android.js\"></script>" + html.substring(kopf.range.last + 1)
    }

    /** MIME-Typ für ausgelieferte App-Dateien. */
    private fun mime(pfad: String) = when (pfad.substringAfterLast('.').lowercase()) {
        "html" -> "text/html"; "js" -> "text/javascript"; "json", "webmanifest" -> "application/json"; "png" -> "image/png"; "css" -> "text/css"
        else -> "application/octet-stream"
    }

    /** Liefert zuerst die per In-App-Update geladene Fassung (falls vorhanden), sonst die mitgelieferte. */
    fun lader(context: Context, update: WebUpdate? = null): WebViewAssetLoader {
        val assets = WebViewAssetLoader.AssetsPathHandler(context)
        return WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/") { pfad ->
                val neu = if (pfad == "android.js") null else update?.datei(if (pfad == "") "index.html" else pfad)
                when {
                    pfad == "" || pfad == "index.html" -> {
                        val html = (neu?.readBytes() ?: context.assets.open("index.html").use { it.readBytes() }).toString(Charsets.UTF_8)
                        WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(mitBruecke(html).toByteArray(Charsets.UTF_8)))
                            .apply { responseHeaders = mapOf("Cache-Control" to "no-store") }
                    }
                    // Ohne Service Worker: die Dateien liegen ohnehin in der App, ein Cache würde nur Altes zeigen.
                    pfad == "sw.js" -> WebResourceResponse("text/plain", "utf-8", 404, "Nicht vorhanden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    neu != null -> WebResourceResponse(mime(pfad), "utf-8", neu.inputStream()).apply { responseHeaders = mapOf("Cache-Control" to "no-store") }
                    else -> assets.handle(pfad)
                }
            }
            .build()
    }
}
