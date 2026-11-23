package de.aysetransport.schaden

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import de.aysetransport.schaden.mail.MailKonten
import de.aysetransport.schaden.mail.Tresor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class AppTest {

    @Test fun appStartetMitDerWebAppUndDerBruecke() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val web = activity.webView
        val schatten = shadowOf(web)
        assertEquals(WebInhalt.START, schatten.lastLoadedUrl)
        assertNotNull(schatten.getJavascriptInterface("AndroidBruecke"))
        assertTrue(web.settings.javaScriptEnabled)
        assertTrue(web.settings.domStorageEnabled)
        assertFalse(web.settings.allowFileAccess)
    }

    @Test fun webAppIstInDerAppUndBekommtDieBruecke() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val html = ctx.assets.open("index.html").use { it.readBytes().toString(Charsets.UTF_8) }
        assertTrue("aktuelle Web-App", html.contains("Fuhrpark"))
        val mit = WebInhalt.mitBruecke(html)
        assertTrue(mit.indexOf("<script src=\"android.js\"></script>") < mit.indexOf("<script", mit.indexOf("android.js") + 20))
        assertTrue(mit.indexOf("android.js") < mit.indexOf("window.API_URL"))
        val bruecke = ctx.assets.open("android.js").use { it.readBytes().toString(Charsets.UTF_8) }
        for (f in listOf("mail:einrichten", "mail:ruf", "mail:sende", "datei:speichern", "datei:oeffnen", "pdf:erzeugen", "drucken", "benachrichtige")) {
            assertTrue(f, bruecke.contains("'$f'"))
        }
        assertEquals("<head><script src=\"android.js\"></script><title>x</title></head>", WebInhalt.mitBruecke("<head><title>x</title></head>"))
    }

    @Test fun mailKontenUeberDieBruecke() {
        val server = GreenMail(ServerSetupTest.SMTP_IMAP).apply { start(); setUser("info@ayse.example", "pw-allgemein"); setUser("kunde@x.example", "k") }
        try {
            val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
            val tresor = object : Tresor {
                override fun verschluessele(klartext: String) = "v:" + klartext.reversed()
                override fun entschluessele(geheim: String) = geheim.removePrefix("v:").reversed()
            }
            val bruecke = Bruecke(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), MailKonten(ctx, tresor), { error("kein Gerät im Test") }, {})
            val konfig = buildJsonObject {
                put("adresse", "info@ayse.example"); put("imapHost", "127.0.0.1"); put("imapPort", ServerSetupTest.IMAP.port); put("imapSicherheit", "KEINE")
                put("smtpHost", "127.0.0.1"); put("smtpPort", ServerSetupTest.SMTP.port); put("smtpSicherheit", "KEINE")
            }
            runBlocking {
                assertEquals(JsonNull, bruecke.ausfuehren("mail:konto", buildJsonArray { add(JsonPrimitive("ALLGEMEIN")) }))
                bruecke.ausfuehren("mail:einrichten", buildJsonArray { add(konfig); add(JsonPrimitive("pw-allgemein")); add(JsonPrimitive("ALLGEMEIN")) })
                val konto = bruecke.ausfuehren("mail:konto", buildJsonArray { add(JsonPrimitive("ALLGEMEIN")) }).jsonObject
                assertEquals("info@ayse.example", konto["adresse"]!!.jsonPrimitive.content)
                assertFalse("Passwort bleibt im Gerät", konto.containsKey("passwort"))
                assertEquals("das Schadens-Postfach ist ein anderes", JsonNull, bruecke.ausfuehren("mail:konto", buildJsonArray { add(JsonPrimitive("SCHADEN")) }))
                bruecke.ausfuehren("mail:sende", buildJsonArray { add(buildJsonObject { put("an", "kunde@x.example"); put("betreff", "Angebot"); put("text", "Hallo") }); add(JsonPrimitive("ALLGEMEIN")) })
            }
            assertEquals(1, server.getReceivedMessagesForDomain("x.example").size)
            assertTrue(Bruecke.alsJs(JsonPrimitive("a b")).contains("\\u2028"))
            val stand = runBlocking { bruecke.ausfuehren("mail:ruf", buildJsonArray { add(JsonPrimitive("stand")); add(JsonObject(emptyMap())); add(JsonPrimitive("ALLGEMEIN")) }) }
            assertEquals(0, stand.jsonObject["eintraege"]!!.jsonArray.size)
        } finally {
            server.stop()
        }
    }

}
