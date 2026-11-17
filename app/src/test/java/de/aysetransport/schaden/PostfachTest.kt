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

        val erst = postfach.hole(konfig("kunde@moebel.example"), "kunde", JsonObject(emptyMap()))
        val mails = erst["mails"]!!.jsonArray
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

        // Zweiter Abruf mit dem gemerkten Stand: nichts Neues – dann eine weitere Mail
        val stand = buildJsonObject { put("letzteUid", erst["letzteUid"]!!.jsonPrimitive.long); put("uidValidity", erst["uidValidity"]!!.jsonPrimitive.long) }
        assertEquals(0, postfach.hole(konfig("kunde@moebel.example"), "kunde", stand)["mails"]!!.jsonArray.size)
        val s = Session.getInstance(Properties().apply { put("mail.smtp.host", "127.0.0.1"); put("mail.smtp.port", ServerSetupTest.SMTP.port.toString()) })
        jakarta.mail.Transport.send(MimeMessage(s).apply {
            setFrom(InternetAddress("dispo@moebel.example")); setRecipient(Message.RecipientType.TO, InternetAddress("kunde@moebel.example"))
            subject = "Nur HTML"; setContent("<p>Hallo&nbsp;Welt</p><p>Zeile 2</p>", "text/html; charset=utf-8")
        })
        val neu = postfach.hole(konfig("kunde@moebel.example"), "kunde", stand)["mails"]!!.jsonArray
        assertEquals(listOf("Nur HTML"), neu.map { it.jsonObject["betreff"]!!.jsonPrimitive.content })
        assertEquals("Hallo Welt\nZeile 2", neu[0].jsonObject["text"]!!.jsonPrimitive.content)
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
