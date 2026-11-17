package de.aysetransport.schaden.mail

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import com.icegreen.greenmail.util.ServerSetupTest
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import java.util.Properties

/** Ein echter IMAP-/SMTP-Server im Test (GreenMail) mit dem Postfach der Firma. */
class TestPostfach(val adresse: String = "schaden@firma.example") : AutoCloseable {
    val passwort = "geheim-123"
    val server = GreenMail(ServerSetup.dynamicPort(ServerSetupTest.SMTP_IMAP)).apply {
        start()
        setUser(adresse, adresse, passwort)
    }

    val konfig: MailKonfig
        get() = MailKonfig(
            adresse = adresse,
            benutzer = adresse,
            name = "Muster Transport GmbH",
            server = MailServer(
                imapHost = "127.0.0.1", imapPort = server.imap.port, imapSicherheit = Sicherheit.KEINE,
                smtpHost = "127.0.0.1", smtpPort = server.smtp.port, smtpSicherheit = Sicherheit.KEINE,
            ),
        )

    /** Legt eine Mail direkt in den Posteingang der Firma – als hätte jemand geschrieben. */
    fun empfange(
        von: String,
        betreff: String,
        text: String,
        antwortAuf: String? = null,
        anhang: Pair<String, ByteArray>? = null,
        messageId: String? = null,
    ): MimeMessage {
        val m = object : MimeMessage(Session.getInstance(Properties())) {
            override fun updateMessageID() {
                if (messageId != null) setHeader("Message-ID", "<$messageId>") else super.updateMessageID()
            }
        }.apply {
            setFrom(InternetAddress(von))
            setRecipient(Message.RecipientType.TO, InternetAddress(adresse))
            setSubject(betreff, "UTF-8")
            if (antwortAuf != null) setHeader("In-Reply-To", "<$antwortAuf>")
            if (anhang == null) setText(text, "UTF-8")
            else setContent(MimeMultipart().apply {
                addBodyPart(MimeBodyPart().apply { setText(text, "UTF-8") })
                addBodyPart(MimeBodyPart().apply { setContent(anhang.second, "image/jpeg"); fileName = anhang.first })
            })
            saveChanges()
        }
        server.userManager.getUser(adresse).deliver(m)
        return m
    }

    override fun close() = server.stop()
}
