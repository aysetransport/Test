package de.aysetransport.schaden.mail

import jakarta.activation.DataHandler
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.internet.MimeUtility
import jakarta.mail.util.ByteArrayDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.angus.mail.imap.IMAPFolder
import java.io.IOException
import java.time.Instant
import java.util.Date
import java.util.Properties

/** Das Postfach hat die Anmeldung abgelehnt (Adresse oder Passwort falsch). */
class MailAnmeldungFehler(message: String) : IOException(message)

data class MailAnhang(val name: String, val mimeType: String, val bytes: ByteArray)

data class MailEntwurf(
    val an: String,
    val betreff: String,
    val text: String,
    /** Message-ID der Mail, auf die geantwortet wird (für die Zuordnung im Postfach des Empfängers). */
    val antwortAuf: String = "",
    val anhaenge: List<MailAnhang> = emptyList(),
)

data class EmpfangeneMail(
    val uid: Long,
    val messageId: String,
    val antwortAuf: List<String>,
    val von: String,
    val vonName: String,
    val an: String,
    val betreff: String,
    val text: String,
    val zeitpunkt: Instant,
    val anhaenge: List<MailAnhang>,
)

data class AbrufErgebnis(val mails: List<EmpfangeneMail>, val letzteUid: Long, val uidValidity: Long)

/**
 * Spricht direkt mit dem Postfach: IMAP zum Lesen, SMTP zum Senden. Alle Aufrufe blockieren und
 * laufen deshalb im IO-Thread.
 */
open class MailDienst {

    private fun session(k: MailKonfig): Session {
        val p = Properties()
        val s = k.server
        p["mail.store.protocol"] = if (s.imapSicherheit == Sicherheit.SSL) "imaps" else "imap"
        for (protokoll in listOf("imap", "imaps")) {
            p["mail.$protokoll.host"] = s.imapHost
            p["mail.$protokoll.port"] = s.imapPort.toString()
            p["mail.$protokoll.connectiontimeout"] = "20000"
            p["mail.$protokoll.timeout"] = "60000"
            if (s.imapSicherheit == Sicherheit.STARTTLS) p["mail.$protokoll.starttls.required"] = "true"
        }
        val smtp = if (s.smtpSicherheit == Sicherheit.SSL) "smtps" else "smtp"
        p["mail.transport.protocol"] = smtp
        p["mail.$smtp.host"] = s.smtpHost
        p["mail.$smtp.port"] = s.smtpPort.toString()
        p["mail.$smtp.auth"] = "true"
        p["mail.$smtp.connectiontimeout"] = "20000"
        p["mail.$smtp.timeout"] = "60000"
        if (s.smtpSicherheit == Sicherheit.STARTTLS) {
            p["mail.smtp.starttls.enable"] = "true"
            p["mail.smtp.starttls.required"] = "true"
        }
        p["mail.mime.address.strict"] = "false"
        return Session.getInstance(p)
    }

    private fun <T> mitFehlertext(block: () -> T): T = try {
        block()
    } catch (e: AuthenticationFailedException) {
        throw MailAnmeldungFehler("Das Postfach hat die Anmeldung abgelehnt. Bitte Adresse und Passwort prüfen.")
    } catch (e: MessagingException) {
        throw IOException("Keine Verbindung zum Postfach: ${e.message ?: e.javaClass.simpleName}", e)
    }

    private fun oeffne(k: MailKonfig, passwort: String): Store =
        session(k).store.apply { connect(k.server.imapHost, k.server.imapPort, k.benutzer, passwort) }

    /** Meldet sich bei IMAP und SMTP an – so fallen falsche Einstellungen sofort auf. */
    open suspend fun pruefe(k: MailKonfig, passwort: String) = withContext(Dispatchers.IO) {
        mitFehlertext {
            oeffne(k, passwort).close()
            session(k).transport.apply { connect(k.server.smtpHost, k.server.smtpPort, k.benutzer, passwort) }.close()
        }
    }

    /** Sendet die Mail und legt eine Kopie im Ordner „Gesendet“ ab. Gibt die Message-ID zurück. */
    open suspend fun sende(k: MailKonfig, passwort: String, e: MailEntwurf): String = withContext(Dispatchers.IO) {
        mitFehlertext {
            val session = session(k)
            val nachricht = MimeMessage(session).apply {
                setFrom(InternetAddress(k.adresse, k.name.ifBlank { null }, "UTF-8"))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(e.an, false))
                setSubject(e.betreff, "UTF-8")
                sentDate = Date()
                if (e.antwortAuf.isNotEmpty()) {
                    setHeader("In-Reply-To", "<${e.antwortAuf}>")
                    setHeader("References", "<${e.antwortAuf}>")
                }
                if (e.anhaenge.isEmpty()) {
                    setText(e.text, "UTF-8")
                } else {
                    val teile = MimeMultipart()
                    teile.addBodyPart(MimeBodyPart().apply { setText(e.text, "UTF-8") })
                    for (a in e.anhaenge) {
                        teile.addBodyPart(
                            MimeBodyPart().apply {
                                dataHandler = DataHandler(ByteArrayDataSource(a.bytes, a.mimeType))
                                fileName = MimeUtility.encodeText(a.name, "UTF-8", null)
                                disposition = Part.ATTACHMENT
                            },
                        )
                    }
                    setContent(teile)
                }
                saveChanges()
            }
            session.transport.use { t ->
                t.connect(k.server.smtpHost, k.server.smtpPort, k.benutzer, passwort)
                t.sendMessage(nachricht, nachricht.allRecipients)
            }
            // Viele Anbieter legen per SMTP Gesendetes nicht selbst ab – dann hier nachholen.
            runCatching {
                oeffne(k, passwort).use { store ->
                    gesendetOrdner(store)?.let { ordner ->
                        nachricht.setFlag(Flags.Flag.SEEN, true)
                        ordner.appendMessages(arrayOf(nachricht))
                    }
                }
            }
            ohneKlammern(nachricht.messageID)
        }
    }

    private fun gesendetOrdner(store: Store): Folder? {
        val alle = store.defaultFolder.list("*")
        alle.firstOrNull { f -> (f as? IMAPFolder)?.attributes?.any { it.equals("\\Sent", ignoreCase = true) } == true }?.let { return it }
        val namen = listOf("Sent", "Gesendet", "Gesendete Objekte", "Gesendete Elemente", "INBOX.Sent", "Sent Items", "Sent Messages")
        return namen.firstNotNullOfOrNull { n -> alle.firstOrNull { it.fullName.equals(n, ignoreCase = true) } }
    }

    /**
     * Holt neue Mails aus dem Posteingang (nur lesen, nichts wird verändert oder als gelesen markiert).
     * Beim ersten Abruf werden nur die Mails der letzten [ersteTage] Tage geholt.
     */
    open suspend fun hole(k: MailKonfig, passwort: String, letzteUid: Long, uidValidity: Long, ersteTage: Long = 30): AbrufErgebnis =
        withContext(Dispatchers.IO) {
            mitFehlertext {
                oeffne(k, passwort).use { store ->
                    val ordner = store.getFolder("INBOX") as IMAPFolder
                    ordner.open(Folder.READ_ONLY)
                    try {
                        val gueltig = ordner.uidValidity
                        val neuBeginnen = gueltig != uidValidity || letzteUid <= 0
                        val nachrichten = if (neuBeginnen) {
                            val ab = Date.from(Instant.now().minusSeconds(ersteTage * 86_400))
                            ordner.messages.filter { (it.receivedDate ?: it.sentDate ?: Date(0)) >= ab }
                        } else {
                            ordner.getMessagesByUID(letzteUid + 1, IMAPFolder.LASTUID).filterNotNull().filter { ordner.getUID(it) > letzteUid }
                        }
                        val mails = nachrichten.mapNotNull { m -> runCatching { lies(ordner, m as MimeMessage) }.getOrNull() }
                        val hoechste = maxOf(letzteUid.takeIf { !neuBeginnen } ?: 0, (ordner.uidNext - 1).coerceAtLeast(0))
                        AbrufErgebnis(mails, hoechste, gueltig)
                    } finally {
                        ordner.close(false)
                    }
                }
            }
        }

    private fun lies(ordner: IMAPFolder, m: MimeMessage): EmpfangeneMail {
        val von = (m.from?.firstOrNull() as? InternetAddress)
        val anhaenge = mutableListOf<MailAnhang>()
        val text = StringBuilder()
        var html: String? = null
        fun durchlaufe(teil: Part) {
            val dateiname = teil.fileName?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) }
            when {
                dateiname != null || Part.ATTACHMENT.equals(teil.disposition, ignoreCase = true) -> {
                    val bytes = teil.inputStream.use { it.readBytes() }
                    if (bytes.isNotEmpty()) anhaenge += MailAnhang(dateiname ?: "Anhang", teil.contentType.substringBefore(';').trim().lowercase(), bytes)
                }
                teil.isMimeType("text/plain") && text.isEmpty() -> text.append(teil.content as String)
                teil.isMimeType("text/html") && html == null -> html = teil.content as String
                teil.isMimeType("multipart/*") -> (teil.content as Multipart).let { mp -> for (i in 0 until mp.count) durchlaufe(mp.getBodyPart(i)) }
                teil.isMimeType("message/rfc822") -> Unit
            }
        }
        durchlaufe(m)
        val inhalt = text.toString().ifBlank { html?.let(::htmlAlsText).orEmpty() }
        val kopfzeilen: List<String> = listOf("In-Reply-To", "References").flatMap { name -> m.getHeader(name)?.toList() ?: emptyList() }
        val bezug = kopfzeilen.flatMap { zeile -> Regex("<([^>]+)>").findAll(zeile).map { r -> r.groupValues[1] }.toList() }
        return EmpfangeneMail(
            uid = ordner.getUID(m),
            messageId = ohneKlammern(m.messageID).ifEmpty { "uid-${ordner.uidValidity}-${ordner.getUID(m)}@${ordner.store.urlName.host}" },
            antwortAuf = bezug.distinct(),
            von = von?.address.orEmpty().lowercase(),
            vonName = von?.personal.orEmpty(),
            an = m.getRecipients(Message.RecipientType.TO)?.joinToString(", ") { (it as? InternetAddress)?.address ?: it.toString() }.orEmpty(),
            betreff = m.subject.orEmpty(),
            text = inhalt.trim(),
            zeitpunkt = (m.receivedDate ?: m.sentDate)?.toInstant() ?: Instant.now(),
            anhaenge = anhaenge,
        )
    }

    companion object {
        fun ohneKlammern(id: String?): String = id.orEmpty().trim().removePrefix("<").removeSuffix(">")

        /** Sehr einfache Umwandlung für Mails, die nur als HTML kommen. */
        fun htmlAlsText(html: String): String = html
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</tr>|</li>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .lines().joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    }
}
