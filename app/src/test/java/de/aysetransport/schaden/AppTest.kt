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


    @Test fun smsAdresseWirdZerlegt() {
        assertEquals(listOf("+491701234567") to "Liefertermin: 12.01. & 8–10 Uhr", Geraet.smsZiel("sms:+491701234567?body=" + java.net.URLEncoder.encode("Liefertermin: 12.01. & 8–10 Uhr", "UTF-8").replace("+", "%20")))
        assertEquals(listOf("+491", "+492"), Geraet.smsZiel("sms:+491,+492?body=Hallo")?.first)
        assertEquals(null, Geraet.smsZiel("sms:+491"))
        assertEquals(null, Geraet.smsZiel("sms://open?addresses=+491&body=x"))
    }

    @Test fun smsMassenversandPrueftDieEmpfaenger() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bruecke = Bruecke(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), MailKonten(ctx, object : Tresor {
            override fun verschluessele(klartext: String) = klartext
            override fun entschluessele(geheim: String) = geheim
        }), { error("kein Gerät im Test") }, {})
        fun liste(vararg p: Pair<String, String>) = buildJsonArray { add(buildJsonArray { for ((nr, text) in p) add(buildJsonObject { put("nummer", nr); put("text", text) }) }) }
        for (falsch in listOf(liste(), liste("abc" to "Hallo"), liste("+491701" to " "))) {
            val e = runCatching { runBlocking { bruecke.ausfuehren("sms:massen", falsch) } }.exceptionOrNull()
            assertTrue("abgelehnt: $falsch → $e", e is IllegalArgumentException)
        }
        // gültige Liste kommt bis zum Gerät (das es im Test nicht gibt)
        val ok = runCatching { runBlocking { bruecke.ausfuehren("sms:massen", liste("+491701234567" to "Tour 12")) } }.exceptionOrNull()
        assertTrue("$ok", ok is IllegalStateException && ok.message == "kein Gerät im Test")
    }

    @Test fun smsFehlerSindVerstaendlich() {
        assertTrue(Geraet.smsFehlerText(android.telephony.SmsManager.RESULT_ERROR_GENERIC_FAILURE).contains("Standard-SIM"))
        assertEquals("kein Netz", Geraet.smsFehlerText(android.telephony.SmsManager.RESULT_ERROR_NO_SERVICE))
        assertEquals("Fehlercode 999 von Android", Geraet.smsFehlerText(999))
    }

    @Test fun installationspaketeAusMailsWerdenNichtGeoeffnet() {
        for (n in listOf("Rechnung.apk", "rechnung.pdf.APK", "x.xapk", "app.apks ", "a.apk.")) assertEquals(n, true, Geraet.istInstallationspaket(n))
        for (n in listOf("Rechnung.pdf", "apk-liste.xlsx", "Foto.jpg", "apk")) assertEquals(n, false, Geraet.istInstallationspaket(n))
    }

    @Test fun smsTextImNormalenZeichensatz() {
        assertEquals("Tour am Mo - 12 Tel. 0171 \"Hamburg\" Größe äöüß ...", Geraet.gsm7("Tour am Mo – 12 ☎ 0171 „Hamburg“ Größe äöüß …"))
        assertEquals("Vater Café ", Geraet.gsm7("Váter Café 🚚"))
    }
}
