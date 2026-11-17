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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.eclipse.angus.mail.imap.IMAPFolder
import java.io.IOException
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.Properties
import java.util.UUID

/** Fehler, den die Oberfläche dem Benutzer zeigt; [anmeldung] = Adresse oder Passwort falsch. */
class MailFehler(message: String, val anmeldung: Boolean = false) : IOException(message)

/** Einstellungen eines Postfachs, wie sie die Oberfläche schickt (Felder wie in der Windows-App). */
data class MailKonfig(
    val adresse: String,
    val name: String,
    val benutzer: String,
    val imapHost: String,
    val imapPort: Int,
    val imapSicherheit: String,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpSicherheit: String,
) {
    companion object {
        fun aus(j: JsonObject): MailKonfig {
            fun t(n: String) = (j[n] as? JsonPrimitive)?.content?.trim().orEmpty()
            fun z(n: String, vorgabe: Int) = t(n).toIntOrNull() ?: vorgabe
            val adresse = t("adresse")
            if (!adresse.contains('@')) throw MailFehler("Bitte eine gültige E-Mail-Adresse eingeben.")
            return MailKonfig(
                adresse = adresse,
                name = t("name"),
                benutzer = t("benutzer").ifEmpty { adresse },
                imapHost = t("imapHost").ifEmpty { throw MailFehler("Bitte den IMAP-Server angeben.") },
                imapPort = z("imapPort", 993),
                imapSicherheit = t("imapSicherheit").ifEmpty { "SSL" },
                smtpHost = t("smtpHost").ifEmpty { throw MailFehler("Bitte den SMTP-Server angeben.") },
                smtpPort = z("smtpPort", 465),
                smtpSicherheit = t("smtpSicherheit").ifEmpty { "SSL" },
            )
        }
    }
}

/**
 * Spricht direkt mit dem Postfach: IMAP zum Lesen, SMTP zum Senden – dasselbe Verhalten wie die
 * Windows-App (desktop/mail.js). Alle Aufrufe blockieren und laufen deshalb im IO-Thread.
 */
class Postfach {

    private fun session(k: MailKonfig): Session {
        val p = Properties()
        val imap = if (k.imapSicherheit == "SSL") "imaps" else "imap"
        p["mail.store.protocol"] = imap
        for (protokoll in listOf("imap", "imaps")) {
            p["mail.$protokoll.connectiontimeout"] = "20000"
            p["mail.$protokoll.timeout"] = "60000"
            if (k.imapSicherheit == "STARTTLS") p["mail.$protokoll.starttls.required"] = "true"
        }
        val smtp = if (k.smtpSicherheit == "SSL") "smtps" else "smtp"
        p["mail.transport.protocol"] = smtp
        for (protokoll in listOf("smtp", "smtps")) {
            p["mail.$protokoll.auth"] = "true"
            p["mail.$protokoll.connectiontimeout"] = "20000"
            p["mail.$protokoll.timeout"] = "60000"
        }
        if (k.smtpSicherheit == "STARTTLS") {
            p["mail.smtp.starttls.enable"] = "true"
            p["mail.smtp.starttls.required"] = "true"
        }
        p["mail.mime.address.strict"] = "false"
        return Session.getInstance(p)
    }

    private fun <T> mitFehlertext(block: () -> T): T = try {
        block()
    } catch (e: MailFehler) {
        throw e
    } catch (e: AuthenticationFailedException) {
        throw MailFehler("Das Postfach hat die Anmeldung abgelehnt. Bitte Adresse und Passwort prüfen.", anmeldung = true)
    } catch (e: MessagingException) {
        throw MailFehler("Keine Verbindung zum Postfach: ${e.message ?: e.javaClass.simpleName}")
    }

    private fun oeffne(k: MailKonfig, passwort: String): Store =
        session(k).store.apply { connect(k.imapHost, k.imapPort, k.benutzer, passwort) }

    /** Meldet sich bei IMAP und SMTP an – so fallen falsche Einstellungen sofort auf. */
    fun pruefe(k: MailKonfig, passwort: String) = mitFehlertext {
        oeffne(k, passwort).close()
        session(k).transport.apply { connect(k.smtpHost, k.smtpPort, k.benutzer, passwort) }.close()
    }

    /**
     * Sendet die Mail und legt eine Kopie im Ordner „Gesendet“ ab.
     * entwurf: { an, bcc, betreff, text, antwortAuf, anhaenge: [{ name, mimeType, base64 }] } – Rückgabe: Message-ID ohne Klammern.
     */
    fun sende(k: MailKonfig, passwort: String, entwurf: JsonObject): String = mitFehlertext {
        fun t(n: String) = (entwurf[n] as? JsonPrimitive)?.content.orEmpty()
        val session = session(k)
        val domain = k.adresse.substringAfter('@', "ayse-crm.local").lowercase()
        val messageId = "${UUID.randomUUID()}@$domain"
        val anhaenge = (entwurf["anhaenge"] as? JsonArray).orEmpty().map { it.jsonObject }
        val nachricht = object : MimeMessage(session) {
            override fun updateMessageID() = setHeader("Message-ID", "<$messageId>")
        }.apply {
            setFrom(InternetAddress(k.adresse, k.name.ifBlank { null }, "UTF-8"))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(t("an"), false))
            if (t("bcc").isNotBlank()) setRecipients(Message.RecipientType.BCC, InternetAddress.parse(t("bcc"), false))
            setSubject(t("betreff"), "UTF-8")
            sentDate = Date()
            if (t("antwortAuf").isNotEmpty()) {
                setHeader("In-Reply-To", "<${t("antwortAuf")}>")
                setHeader("References", "<${t("antwortAuf")}>")
            }
            if (anhaenge.isEmpty()) {
                setText(t("text"), "UTF-8")
            } else {
                val teile = MimeMultipart()
                teile.addBodyPart(MimeBodyPart().apply { setText(t("text"), "UTF-8") })
                for (a in anhaenge) {
                    val name = a["name"]?.jsonPrimitive?.content ?: "Anhang"
                    val typ = a["mimeType"]?.jsonPrimitive?.content ?: "application/octet-stream"
                    val bytes = Base64.getDecoder().decode(a["base64"]?.jsonPrimitive?.content.orEmpty())
                    teile.addBodyPart(
                        MimeBodyPart().apply {
                            dataHandler = DataHandler(ByteArrayDataSource(bytes, typ))
                            fileName = MimeUtility.encodeText(name, "UTF-8", null)
                            disposition = Part.ATTACHMENT
                        },
                    )
                }
                setContent(teile)
            }
            saveChanges()
        }
        session.transport.use { tr ->
            tr.connect(k.smtpHost, k.smtpPort, k.benutzer, passwort)
            tr.sendMessage(nachricht, nachricht.allRecipients)
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
        messageId
    }

    private fun gesendetOrdner(store: Store): Folder? {
        val alle = store.defaultFolder.list("*")
        alle.firstOrNull { f -> (f as? IMAPFolder)?.attributes?.any { it.equals("\\Sent", ignoreCase = true) } == true }?.let { return it }
        val namen = listOf("Sent", "Gesendet", "Gesendete Objekte", "Gesendete Elemente", "INBOX.Sent", "Sent Items", "Sent Messages")
        return namen.firstNotNullOfOrNull { n -> alle.firstOrNull { it.fullName.equals(n, ignoreCase = true) } }
    }

    /**
     * Holt neue Mails aus dem Posteingang, ohne dort etwas zu verändern.
     * Beim ersten Abruf (oder wenn sich die UIDVALIDITY geändert hat) nur die letzten [ersteTage] Tage.
     * Rückgabe wie in der Windows-App: { mails: […], letzteUid, uidValidity }.
     */
    fun hole(k: MailKonfig, passwort: String, stand: JsonObject, ersteTage: Long = 30): JsonObject = mitFehlertext {
        val letzteUid = stand["letzteUid"]?.jsonPrimitive?.longOrNull ?: 0L
        val uidValidity = stand["uidValidity"]?.jsonPrimitive?.longOrNull ?: 0L
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
                    ordner.getMessagesByUID(letzteUid + 1, UIDFolderLetzte).filterNotNull().filter { ordner.getUID(it) > letzteUid }
                }
                val mails = nachrichten.mapNotNull { m -> runCatching { lies(ordner, m as MimeMessage) }.getOrNull() }
                val hoechste = maxOf(if (neuBeginnen) 0 else letzteUid, ordner.uidNext - 1, 0)
                buildJsonObject {
                    put("mails", JsonArray(mails))
                    put("letzteUid", hoechste)
                    put("uidValidity", gueltig)
                }
            } finally {
                ordner.close(false)
            }
        }
    }

    private fun lies(ordner: IMAPFolder, m: MimeMessage): JsonObject {
        val von = m.from?.firstOrNull() as? InternetAddress
        val anhaenge = mutableListOf<JsonObject>()
        val text = StringBuilder()
        var html: String? = null
        fun durchlaufe(teil: Part) {
            val dateiname = teil.fileName?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) }
            when {
                dateiname != null || Part.ATTACHMENT.equals(teil.disposition, ignoreCase = true) -> {
                    val bytes = teil.inputStream.use { it.readBytes() }
                    if (bytes.isNotEmpty()) {
                        anhaenge += buildJsonObject {
                            put("name", dateiname ?: "Anhang")
                            put("mimeType", teil.contentType.substringBefore(';').trim().lowercase())
                            put("base64", Base64.getEncoder().encodeToString(bytes))
                        }
                    }
                }
                teil.isMimeType("text/plain") && text.isEmpty() -> text.append(teil.content.toString())
                teil.isMimeType("text/html") && html == null -> html = teil.content.toString()
                teil.isMimeType("multipart/*") -> (teil.content as Multipart).let { mp -> for (i in 0 until mp.count) durchlaufe(mp.getBodyPart(i)) }
                else -> Unit
            }
        }
        durchlaufe(m)
        val inhalt = text.toString().ifBlank { html?.let(::htmlAlsText).orEmpty() }.replace(' ', ' ').trim()
        val kopfzeilen = listOf("In-Reply-To", "References").flatMap { name -> m.getHeader(name)?.toList() ?: emptyList() }
        val bezug = kopfzeilen.flatMap { zeile -> Regex("<([^>]+)>").findAll(zeile).map { it.groupValues[1] }.toList().ifEmpty { listOf(zeile.trim()) } }
            .filter { it.isNotBlank() }.distinct()
        val uid = ordner.getUID(m)
        return buildJsonObject {
            put("uid", uid)
            put("messageId", ohneKlammern(m.messageID).ifEmpty { "uid-${ordner.uidValidity}-$uid@imap" })
            put("antwortAuf", buildJsonArray { bezug.forEach { add(JsonPrimitive(it)) } })
            put("von", von?.address.orEmpty().lowercase())
            put("vonName", von?.personal.orEmpty())
            put("an", m.getRecipients(Message.RecipientType.TO)?.joinToString(", ") { (it as? InternetAddress)?.toUnicodeString() ?: it.toString() }.orEmpty())
            put("betreff", m.subject.orEmpty())
            put("text", inhalt)
            put("zeitpunkt", ((m.receivedDate ?: m.sentDate)?.toInstant() ?: Instant.now()).toString())
            put("anhaenge", JsonArray(anhaenge))
        }
    }

    companion object {
        private const val UIDFolderLetzte = jakarta.mail.UIDFolder.LASTUID

        /** Voreinstellungen gängiger Anbieter (identisch zur Windows-App). */
        val ANBIETER: JsonObject = buildJsonObject {
            fun anbieter(schluessel: String, label: String, imap: String, smtp: String, smtpPort: Int, smtpSicherheit: String) =
                put(schluessel, buildJsonObject {
                    put("label", label); put("imapHost", imap); put("imapPort", 993); put("imapSicherheit", "SSL")
                    put("smtpHost", smtp); put("smtpPort", smtpPort); put("smtpSicherheit", smtpSicherheit)
                })
            anbieter("IONOS", "IONOS (1&1)", "imap.ionos.de", "smtp.ionos.de", 465, "SSL")
            anbieter("STRATO", "STRATO", "imap.strato.de", "smtp.strato.de", 465, "SSL")
            anbieter("GMX", "GMX", "imap.gmx.net", "mail.gmx.net", 465, "SSL")
            anbieter("WEBDE", "WEB.DE", "imap.web.de", "smtp.web.de", 587, "STARTTLS")
            anbieter("TONLINE", "Telekom (T-Online)", "secureimap.t-online.de", "securesmtp.t-online.de", 465, "SSL")
        }

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

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
