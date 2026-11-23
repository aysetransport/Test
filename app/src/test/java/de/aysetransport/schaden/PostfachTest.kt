package de.aysetransport.schaden

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import de.aysetransport.schaden.mail.MailFehler
import de.aysetransport.schaden.mail.MailKonfig
import de.aysetransport.schaden.mail.Postfach
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.add
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.Properties

/** Postfach gegen einen echten (lokalen) IMAP/SMTP-Server – dasselbe Verhalten wie in der Windows-App. */
class PostfachTest {
    private lateinit var server: GreenMail
    private val postfach = Postfach()

    private fun konfig(adresse: String) = MailKonfig.aus(buildJsonObject {
        put("adresse", adresse); put("name", "Ayse Transport")
        put("imapHost", "127.0.0.1"); put("imapPort", ServerSetupTest.IMAP.port); put("imapSicherheit", "KEINE")
        put("smtpHost", "127.0.0.1"); put("smtpPort", ServerSetupTest.SMTP.port); put("smtpSicherheit", "KEINE")
    })

    @Before fun start() {
        server = GreenMail(ServerSetupTest.SMTP_IMAP).apply {
            start()
            setUser("schaden@ayse.example", "geheim")
            setUser("kunde@moebel.example", "kunde")
        }
    }

    @After fun stop() = server.stop()

    @Test fun anmeldungWirdGeprueft() {
        postfach.pruefe(konfig("schaden@ayse.example"), "geheim")
        try {
            postfach.pruefe(konfig("schaden@ayse.example"), "falsch")
            fail("falsches Passwort muss abgelehnt werden")
        } catch (e: MailFehler) {
            assertTrue(e.anmeldung)
        }
    }

    @Test fun sendenMitAnhangUndAbholenBeimEmpfaenger() {
        val id = postfach.sende(konfig("schaden@ayse.example"), "geheim", buildJsonObject {
            put("an", "kunde@moebel.example"); put("betreff", "Rechnung R2026-0001"); put("text", "Guten Tag,\nanbei die Rechnung.")
            put("antwortAuf", "frage-1@moebel.example")
            put("anhaenge", buildJsonArray {
                add(buildJsonObject { put("name", "Rechnung R2026-0001.pdf"); put("mimeType", "application/pdf"); put("base64", Base64.getEncoder().encodeToString("%PDF-1.4".toByteArray())) })
            })
        })
        assertTrue(id.endsWith("@ayse.example"))

        val kunde = konfig("kunde@moebel.example")
        fun alle() = postfach.ruf(kunde, "kunde", "stand", JsonObject(emptyMap())).jsonObject["eintraege"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.long }
        fun lade(uids: List<Long>) = postfach.ruf(kunde, "kunde", "lade", buildJsonObject { put("uids", buildJsonArray { uids.forEach { add(JsonPrimitive(it)) } }) }).jsonArray
        val erst = alle()
        val mails = lade(erst)
        assertEquals(1, mails.size)
        val m = mails[0].jsonObject
        assertEquals(id, m["messageId"]!!.jsonPrimitive.content)
        assertEquals("schaden@ayse.example", m["von"]!!.jsonPrimitive.content)
        assertEquals("Ayse Transport", m["vonName"]!!.jsonPrimitive.content)
        assertEquals("Rechnung R2026-0001", m["betreff"]!!.jsonPrimitive.content)
        assertEquals("Guten Tag,\nanbei die Rechnung.", m["text"]!!.jsonPrimitive.content.replace("\r\n", "\n"))
        assertEquals(listOf("frage-1@moebel.example"), m["antwortAuf"]!!.jsonArray.map { it.jsonPrimitive.content })
        val anhang = m["anhaenge"]!!.jsonArray.single().jsonObject
        assertEquals("Rechnung R2026-0001.pdf", anhang["name"]!!.jsonPrimitive.content)
        assertEquals("%PDF-1.4", String(Base64.getDecoder().decode(anhang["base64"]!!.jsonPrimitive.content)))

        // Eine weitere Mail, nur HTML: wird als lesbarer Text geliefert
        val s = Session.getInstance(Properties().apply { put("mail.smtp.host", "127.0.0.1"); put("mail.smtp.port", ServerSetupTest.SMTP.port.toString()) })
        jakarta.mail.Transport.send(MimeMessage(s).apply {
            setFrom(InternetAddress("dispo@moebel.example")); setRecipient(Message.RecipientType.TO, InternetAddress("kunde@moebel.example"))
            subject = "Nur HTML"; setContent("<p>Hallo&nbsp;Welt</p><p>Zeile 2</p>", "text/html; charset=utf-8")
        })
        val neu = lade(alle() - erst.toSet())
        assertEquals(listOf("Nur HTML"), neu.map { it.jsonObject["betreff"]!!.jsonPrimitive.content })
        assertEquals("Hallo Welt\nZeile 2", neu[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    private fun ruf(befehl: String, d: JsonObject = JsonObject(emptyMap())) = postfach.ruf(konfig("schaden@ayse.example"), "geheim", befehl, d)
    private fun obj(vararg p: Pair<String, Any>) = buildJsonObject {
        p.forEach { (k, v) ->
            when (v) {
                is String -> put(k, v); is Number -> put(k, v); is Boolean -> put(k, v)
                is List<*> -> put(k, buildJsonArray { v.forEach { add(JsonPrimitive(it as Number)) } })
            }
        }
    }

    @Test fun schnittstelle2OrdnerStandLadenAktionenSucheAnhangQuelle() {
        ruf("ordnerAktion", obj("aktion" to "anlegen", "pfad" to "Archiv"))
        ruf("ordnerAktion", obj("aktion" to "anlegen", "pfad" to "Trash"))
        val ordner = ruf("ordner").jsonArray.map { it.jsonObject }
        assertEquals("INBOX", ordner.first { it["pfad"]!!.jsonPrimitive.content == "INBOX" }["spezial"]!!.jsonPrimitive.content)
        assertEquals("ARCHIVE", ordner.first { it["pfad"]!!.jsonPrimitive.content == "Archiv" }["spezial"]!!.jsonPrimitive.content)
        assertEquals("TRASH", ordner.first { it["pfad"]!!.jsonPrimitive.content == "Trash" }["spezial"]!!.jsonPrimitive.content)

        val s = Session.getInstance(Properties().apply { put("mail.smtp.host", "127.0.0.1"); put("mail.smtp.port", ServerSetupTest.SMTP.port.toString()) })
        jakarta.mail.Transport.send(MimeMessage(s).apply {
            setFrom(InternetAddress("eva@fink.example", "Eva Fink")); setRecipient(Message.RecipientType.TO, InternetAddress("schaden@ayse.example"))
            setRecipient(Message.RecipientType.CC, InternetAddress("chef@fink.example")); replyTo = arrayOf(InternetAddress("buchhaltung@fink.example"))
            subject = "Rechnung Oktober"; setHeader("X-Priority", "1 (Highest)"); setHeader("Disposition-Notification-To", "eva@fink.example")
            setHeader("List-Unsubscribe", "<mailto:ab@fink.example>, <https://fink.example/ab>")
            setContent(jakarta.mail.internet.MimeMultipart().apply {
                addBodyPart(jakarta.mail.internet.MimeBodyPart().apply { setText("<p>Hallo <b>Team</b></p><img src=\"cid:logo@fink\">", "UTF-8", "html") })
                addBodyPart(jakarta.mail.internet.MimeBodyPart().apply { setContent(ByteArray(300) { 1 }, "image/png"); setHeader("Content-ID", "<logo@fink>"); disposition = "inline" })
                addBodyPart(jakarta.mail.internet.MimeBodyPart().apply {
                    dataHandler = jakarta.activation.DataHandler(jakarta.mail.util.ByteArrayDataSource(ByteArray(5000) { 2 }, "application/pdf")); fileName = "Rechnung.pdf"
                })
            })
        })
        val st = ruf("stand", obj("ordner" to "INBOX")).jsonObject
        assertTrue(st["uidValidity"]!!.jsonPrimitive.long > 0)
        val uid = st["eintraege"]!!.jsonArray.last().jsonArray[0].jsonPrimitive.long
        val m = ruf("lade", obj("ordner" to "INBOX", "uids" to listOf(uid), "maxAnhang" to 1000)).jsonArray.single().jsonObject
        assertEquals("Rechnung Oktober", m["betreff"]!!.jsonPrimitive.content)
        assertEquals("Eva Fink", m["vonName"]!!.jsonPrimitive.content)
        assertTrue(m["cc"]!!.jsonPrimitive.content.contains("chef@fink.example"))
        assertTrue(m["antwortAn"]!!.jsonPrimitive.content.contains("buchhaltung@fink.example"))
        assertTrue(m["html"]!!.jsonPrimitive.content.contains("<b>Team</b>"))
        assertEquals("HOCH", m["wichtig"]!!.jsonPrimitive.content)
        assertEquals("eva@fink.example", m["lesebestaetigung"]!!.jsonPrimitive.content)
        assertEquals("https://fink.example/ab", m["abmelden"]!!.jsonPrimitive.content)
        assertEquals(false, m["gelesen"]!!.jsonPrimitive.boolean)
        val anh = m["anhaenge"]!!.jsonArray.map { it.jsonObject }
        val logo = anh.first { it["cid"]!!.jsonPrimitive.content == "logo@fink" }
        assertTrue("kleines Inline-Bild direkt dabei", logo["base64"] != null && logo["inline"]!!.jsonPrimitive.boolean)
        val pdf = anh.first { it["name"]!!.jsonPrimitive.content == "Rechnung.pdf" }
        assertTrue("großer Anhang nur angekündigt", pdf["base64"] == null && pdf["groesse"]!!.jsonPrimitive.int == 5000)
        val geladen = ruf("anhang", obj("ordner" to "INBOX", "uid" to uid.toString(), "nr" to anh.indexOf(pdf).toString())).jsonObject
        assertEquals(5000, Base64.getDecoder().decode(geladen["base64"]!!.jsonPrimitive.content).size)
        assertTrue(String(Base64.getDecoder().decode(ruf("quelle", obj("ordner" to "INBOX", "uid" to uid.toString())).jsonPrimitive.content)).contains("Subject: Rechnung Oktober"))

        ruf("aktion", obj("ordner" to "INBOX", "uids" to listOf(uid), "aktion" to "gelesen"))
        ruf("aktion", obj("ordner" to "INBOX", "uids" to listOf(uid), "aktion" to "markieren"))
        val e = ruf("stand", obj("ordner" to "INBOX")).jsonObject["eintraege"]!!.jsonArray.map { it.jsonArray }.first { it[0].jsonPrimitive.long == uid }
        assertEquals(listOf(1L, 1L), listOf(e[1].jsonPrimitive.long, e[2].jsonPrimitive.long))
        assertEquals(listOf(uid), ruf("suche", obj("ordner" to "INBOX", "betreff" to "Rechnung Oktober")).jsonArray.map { it.jsonPrimitive.long })
        assertEquals(0, ruf("suche", obj("ordner" to "INBOX", "text" to "gibt-es-nicht-xyz")).jsonArray.size)

        ruf("aktion", obj("ordner" to "INBOX", "uids" to listOf(uid), "aktion" to "verschieben", "ziel" to "Archiv"))
        assertTrue(ruf("stand", obj("ordner" to "INBOX")).jsonObject["eintraege"]!!.jsonArray.none { it.jsonArray[0].jsonPrimitive.long == uid })
        val archiv = ruf("stand", obj("ordner" to "Archiv")).jsonObject["eintraege"]!!.jsonArray
        assertEquals(1, archiv.size)
        ruf("aktion", obj("ordner" to "Archiv", "uids" to listOf(archiv[0].jsonArray[0].jsonPrimitive.long), "aktion" to "loeschen"))
        assertEquals(0, ruf("stand", obj("ordner" to "Archiv")).jsonObject["eintraege"]!!.jsonArray.size)
        ruf("ordnerAktion", obj("aktion" to "umbenennen", "pfad" to "Archiv", "neu" to "Archiv2"))
        assertTrue(ruf("ordner").jsonArray.any { it.jsonObject["pfad"]!!.jsonPrimitive.content == "Archiv2" })
        try { ruf("gibtsnicht"); fail() } catch (x: MailFehler) { assertTrue(x.message!!.contains("Unbekannter Befehl")) }
    }

    @Test fun sendenErweitertHtmlInlineCcWichtigkeitLesebestaetigungBezug() {
        val id = ruf("sende", buildJsonObject {
            put("an", "kunde@moebel.example"); put("cc", "kunde@moebel.example"); put("betreff", "Angebot"); put("text", "Hallo Ben")
            put("html", "<p>Hallo <b>Ben</b></p><img src=\"cid:sig1\">"); put("wichtig", "HOCH"); put("lesebestaetigung", true)
            put("antwortAuf", "b@x"); put("referenzen", buildJsonArray { add("a@x"); add("b@x") })
            put("kopfzeilen", buildJsonObject { put("Auto-Submitted", "auto-replied"); put("Bcc", "boese@x") })
            put("anhaenge", buildJsonArray { add(buildJsonObject { put("name", "sig.png"); put("mimeType", "image/png"); put("cid", "sig1"); put("base64", Base64.getEncoder().encodeToString(ByteArray(50) { 3 })) }) })
        }).jsonPrimitive.content
        val k = konfig("kunde@moebel.example")
        val uid = postfach.ruf(k, "kunde", "stand", obj("ordner" to "INBOX")).jsonObject["eintraege"]!!.jsonArray.last().jsonArray[0].jsonPrimitive.long
        val roh = String(Base64.getDecoder().decode(postfach.ruf(k, "kunde", "quelle", obj("ordner" to "INBOX", "uid" to uid.toString())).jsonPrimitive.content))
        assertTrue(roh.contains("<$id>"))
        assertTrue(Regex("(?i)Cc: kunde@moebel\\.example").containsMatchIn(roh))
        assertTrue(roh.contains("X-Priority: 1"))
        assertTrue(roh.contains("Disposition-Notification-To: schaden@ayse.example"))
        assertTrue(roh.contains("References: <a@x> <b@x>"))
        assertTrue(roh.contains("In-Reply-To: <b@x>"))
        assertTrue(roh.contains("text/html")); assertTrue(roh.contains("Content-ID: <sig1>"))
        assertTrue(roh.contains("Auto-Submitted: auto-replied")); assertTrue("nur erlaubte Kopfzeilen", !roh.contains("boese@x"))
        val m = postfach.ruf(k, "kunde", "lade", obj("ordner" to "INBOX", "uids" to listOf(uid))).jsonArray.single().jsonObject
        assertEquals("HOCH", m["wichtig"]!!.jsonPrimitive.content)
        assertTrue(m["html"]!!.jsonPrimitive.content.contains("<b>Ben</b>"))
    }

    @Test fun konfigurationOhneServerWirdAbgelehnt() {
        try {
            MailKonfig.aus(buildJsonObject { put("adresse", "a@b.de") })
            fail()
        } catch (e: MailFehler) {
            assertTrue(e.message!!.contains("IMAP"))
        }
    }
}
