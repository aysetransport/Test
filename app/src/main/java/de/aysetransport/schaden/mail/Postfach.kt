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
import jakarta.mail.FetchProfile
import jakarta.mail.UIDFolder
import jakarta.mail.search.AndTerm
import jakarta.mail.search.BodyTerm
import jakarta.mail.search.ComparisonTerm
import jakarta.mail.search.FlagTerm
import jakarta.mail.search.FromStringTerm
import jakarta.mail.search.OrTerm
import jakarta.mail.search.ReceivedDateTerm
import jakarta.mail.search.RecipientStringTerm
import jakarta.mail.search.SearchTerm
import jakarta.mail.search.SubjectTerm
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.internet.MimeUtility
import jakarta.mail.util.ByteArrayDataSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.eclipse.angus.mail.imap.IMAPFolder
import java.io.ByteArrayOutputStream
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
            if (t("cc").isNotBlank()) setRecipients(Message.RecipientType.CC, InternetAddress.parse(t("cc"), false))
            if (t("bcc").isNotBlank()) setRecipients(Message.RecipientType.BCC, InternetAddress.parse(t("bcc"), false))
            if (t("antwortAn").isNotBlank()) replyTo = InternetAddress.parse(t("antwortAn"), false)
            setSubject(t("betreff"), "UTF-8")
            sentDate = Date()
            when (t("wichtig")) {
                "HOCH" -> { setHeader("X-Priority", "1 (Highest)"); setHeader("Importance", "High") }
                "NIEDRIG" -> { setHeader("X-Priority", "5 (Lowest)"); setHeader("Importance", "Low") }
            }
            if ((entwurf["lesebestaetigung"] as? JsonPrimitive)?.booleanOrNull == true) setHeader("Disposition-Notification-To", k.adresse)
            (entwurf["kopfzeilen"] as? JsonObject)?.forEach { (n, w) ->
                if (Regex("(?i)^(x-[a-z0-9-]+|auto-submitted|precedence)$").matches(n)) setHeader(n, (w as? JsonPrimitive)?.content.orEmpty().replace(Regex("[\\r\\n]+"), " "))
            }
            val referenzen = ((entwurf["referenzen"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content } + listOfNotNull(t("antwortAuf").ifBlank { null }))
                .map(::ohneKlammern).filter { it.isNotBlank() }.distinct()
            if (t("antwortAuf").isNotEmpty()) setHeader("In-Reply-To", "<${ohneKlammern(t("antwortAuf"))}>")
            if (referenzen.isNotEmpty()) setHeader("References", referenzen.joinToString(" ") { "<$it>" })
            fun teil(a: JsonObject) = MimeBodyPart().apply {
                val name = a["name"]?.jsonPrimitive?.content ?: "Anhang"
                val typ = a["mimeType"]?.jsonPrimitive?.content ?: "application/octet-stream"
                dataHandler = DataHandler(ByteArrayDataSource(Base64.getDecoder().decode(a["base64"]?.jsonPrimitive?.content.orEmpty()), typ))
                fileName = MimeUtility.encodeText(name, "UTF-8", null)
                val cid = a["cid"]?.jsonPrimitive?.content.orEmpty()
                if (cid.isNotBlank()) { setHeader("Content-ID", "<$cid>"); disposition = Part.INLINE } else disposition = Part.ATTACHMENT
            }
            val inline = anhaenge.filter { it["cid"]?.jsonPrimitive?.content.orEmpty().isNotBlank() }
            val dateien = anhaenge - inline.toSet()
            val html = t("html")
            // Aufbau: mixed[ related[ alternative[text, html], Inline-Bilder ], Anhänge ]
            val koerper: MimeBodyPart = if (html.isBlank()) {
                MimeBodyPart().apply { setText(t("text"), "UTF-8") }
            } else {
                val alternative = MimeMultipart("alternative").apply {
                    addBodyPart(MimeBodyPart().apply { setText(t("text"), "UTF-8") })
                    addBodyPart(MimeBodyPart().apply { setText(html, "UTF-8", "html") })
                }
                if (inline.isEmpty()) MimeBodyPart().apply { setContent(alternative) } else {
                    val related = MimeMultipart("related").apply {
                        addBodyPart(MimeBodyPart().apply { setContent(alternative) })
                        inline.forEach { addBodyPart(teil(it)) }
                    }
                    MimeBodyPart().apply { setContent(related) }
                }
            }
            if (dateien.isEmpty()) {
                if (html.isBlank()) setText(t("text"), "UTF-8") else setContent(koerper.content as Multipart)
            } else {
                setContent(MimeMultipart().apply { addBodyPart(koerper); dateien.forEach { addBodyPart(teil(it)) } })
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


    // ============================== Postfach wie Outlook (Schnittstelle 2) ==============================
    // Dieselben Bausteine wie die Windows-App (desktop/mail.js): ordner, stand, lade, suche, aktion, ordnerAktion,
    // anhang, quelle, sende – die Oberfläche setzt daraus alles Weitere zusammen.

    fun ruf(k: MailKonfig, passwort: String, befehl: String, d: JsonObject): JsonElement = mitFehlertext {
        when (befehl) {
            "ordner" -> ordner(k, passwort)
            "stand" -> stand(k, passwort, d)
            "lade" -> lade(k, passwort, d)
            "suche" -> suche(k, passwort, d)
            "aktion" -> aktion(k, passwort, d)
            "ordnerAktion" -> ordnerAktion(k, passwort, d)
            "anhang" -> anhang(k, passwort, d)
            "quelle" -> quelle(k, passwort, d)
            "sende" -> JsonPrimitive(sende(k, passwort, d))
            else -> throw MailFehler("Unbekannter Befehl: $befehl")
        }
    }

    private fun JsonObject.text(n: String, vorgabe: String = "") = (this[n] as? JsonPrimitive)?.content ?: vorgabe
    private fun JsonObject.wahr(n: String) = (this[n] as? JsonPrimitive)?.booleanOrNull == true
    private fun JsonObject.uids() = (this["uids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.longOrNull }.toLongArray()
    private fun datum(iso: String): Date? = iso.takeIf { it.isNotBlank() }?.let { runCatching { Date.from(Instant.parse(if (it.length == 10) "${it}T00:00:00Z" else it)) }.getOrNull() }

    private fun spezial(f: IMAPFolder): String {
        if (f.fullName.equals("INBOX", ignoreCase = true)) return "INBOX"
        val attr = f.attributes.orEmpty().map { it.lowercase() }
        SPEZIAL.forEach { (a, s) -> if (a in attr) return s }
        return SPEZIAL_NAMEN.firstOrNull { (_, r) -> r.matches(f.fullName) }?.first.orEmpty()
    }

    private fun ordner(k: MailKonfig, passwort: String): JsonElement = oeffne(k, passwort).use { store ->
        JsonArray(store.defaultFolder.list("*").map { f ->
            val imap = f as IMAPFolder
            val waehlbar = imap.attributes.orEmpty().none { it.equals("\\Noselect", true) || it.equals("\\NonExistent", true) } && (imap.type and Folder.HOLDS_MESSAGES) != 0
            buildJsonObject {
                put("pfad", imap.fullName); put("name", imap.name); put("trenner", imap.separator.toString()); put("spezial", spezial(imap))
                put("gesamt", if (waehlbar) runCatching { imap.messageCount }.getOrDefault(0) else 0)
                put("ungelesen", if (waehlbar) runCatching { imap.unreadMessageCount }.getOrDefault(0) else 0)
                put("auswaehlbar", waehlbar)
            }
        })
    }

    private fun <T> imOrdner(k: MailKonfig, passwort: String, pfad: String, schreiben: Boolean = false, arbeit: (Store, IMAPFolder) -> T): T =
        oeffne(k, passwort).use { store ->
            val f = store.getFolder(pfad.ifBlank { "INBOX" }) as IMAPFolder
            if (!f.exists()) throw MailFehler("Den Ordner „$pfad“ gibt es auf dem Server nicht.")
            f.open(if (schreiben) Folder.READ_WRITE else Folder.READ_ONLY)
            try { arbeit(store, f) } finally { if (f.isOpen) f.close(false) }
        }

    private fun stand(k: MailKonfig, passwort: String, d: JsonObject): JsonElement = imOrdner(k, passwort, d.text("ordner", "INBOX")) { _, f ->
        val seit = datum(d.text("seit"))
        val nachrichten = if (f.messageCount == 0) emptyArray() else if (seit != null) f.search(ReceivedDateTerm(ComparisonTerm.GE, seit)) else f.messages
        f.fetch(nachrichten, FetchProfile().apply { add(UIDFolder.FetchProfileItem.UID); add(FetchProfile.Item.FLAGS) })
        val eintraege = nachrichten.map { m ->
            val fl = m.flags
            listOf(f.getUID(m), if (fl.contains(Flags.Flag.SEEN)) 1L else 0L, if (fl.contains(Flags.Flag.FLAGGED)) 1L else 0L,
                if (fl.contains(Flags.Flag.ANSWERED)) 1L else 0L, if (fl.contains(Flags.Flag.DRAFT)) 1L else 0L)
        }.sortedBy { it[0] }
        buildJsonObject {
            put("ordner", f.fullName); put("uidValidity", f.uidValidity); put("uidNext", f.uidNext); put("gesamt", f.messageCount)
            put("eintraege", buildJsonArray { eintraege.forEach { e -> add(buildJsonArray { e.forEach { add(JsonPrimitive(it)) } }) } })
        }
    }

    /** Anhänge in fester Reihenfolge (gleich für „lade“ und „anhang“). */
    private fun anhangTeile(m: Part): List<Part> {
        val liste = mutableListOf<Part>()
        fun durchlaufe(teil: Part) {
            when {
                teil.isMimeType("multipart/*") -> (teil.content as Multipart).let { mp -> for (i in 0 until mp.count) durchlaufe(mp.getBodyPart(i)) }
                teil.fileName != null || Part.ATTACHMENT.equals(teil.disposition, ignoreCase = true) ||
                    (teil.getHeader("Content-ID") != null && !teil.isMimeType("text/*")) -> liste += teil
                else -> Unit
            }
        }
        durchlaufe(m)
        return liste
    }

    private fun kopf(m: MimeMessage, n: String) = m.getHeader(n)?.firstOrNull().orEmpty()

    private fun alsMail(f: IMAPFolder, m: MimeMessage, maxAnhang: Int): JsonObject {
        val basis = lies(f, m)
        var html = ""
        fun suche(teil: Part) {
            when {
                teil.fileName != null || Part.ATTACHMENT.equals(teil.disposition, ignoreCase = true) -> Unit
                teil.isMimeType("text/html") && html.isEmpty() -> html = teil.content.toString()
                teil.isMimeType("multipart/*") -> (teil.content as Multipart).let { mp -> for (i in 0 until mp.count) suche(mp.getBodyPart(i)) }
                else -> Unit
            }
        }
        suche(m)
        val adressen = { t: Message.RecipientType -> m.getRecipients(t)?.joinToString(", ") { (it as? InternetAddress)?.toUnicodeString() ?: it.toString() }.orEmpty() }
        val wichtigkeit = kopf(m, "Importance").trim().lowercase(); val xPrio = kopf(m, "X-Priority").trim(); val prio = kopf(m, "Priority").trim().lowercase()
        val wichtig = when {
            wichtigkeit == "high" || xPrio.startsWith("1") || xPrio.startsWith("2") || prio == "urgent" -> "HOCH"
            wichtigkeit == "low" || xPrio.startsWith("4") || xPrio.startsWith("5") || prio == "non-urgent" -> "NIEDRIG"
            else -> ""
        }
        val abmeldenTeile = Regex("<([^>]+)>").findAll(kopf(m, "List-Unsubscribe")).map { it.groupValues[1] }.toList()
        val fl = m.flags
        return buildJsonObject {
            basis.forEach { (n, v) -> if (n != "anhaenge" && n != "uid") put(n, v) }
            put("uid", f.getUID(m)); put("ordner", f.fullName)
            put("cc", adressen(Message.RecipientType.CC))
            put("antwortAn", m.getHeader("Reply-To")?.let { m.replyTo?.joinToString(", ") { (it as? InternetAddress)?.toUnicodeString() ?: it.toString() } }.orEmpty())
            put("html", if (html.length <= MAX_HTML) html else "")
            put("groesse", m.size)
            put("gelesen", fl.contains(Flags.Flag.SEEN)); put("markiert", fl.contains(Flags.Flag.FLAGGED))
            put("beantwortet", fl.contains(Flags.Flag.ANSWERED)); put("entwurf", fl.contains(Flags.Flag.DRAFT))
            put("wichtig", wichtig)
            put("lesebestaetigung", kopf(m, "Disposition-Notification-To").substringAfter('<').substringBefore('>').trim())
            put("abmelden", abmeldenTeile.firstOrNull { it.startsWith("https:", true) } ?: abmeldenTeile.firstOrNull { it.startsWith("mailto:", true) }.orEmpty())
            put("anhaenge", JsonArray(anhangTeile(m).mapNotNull { teil ->
                val bytes = teil.inputStream.use { it.readBytes() }
                if (bytes.isEmpty()) return@mapNotNull null
                val cid = ohneKlammern((teil as? MimeBodyPart)?.contentID)
                val name = teil.fileName?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) }
                buildJsonObject {
                    put("name", name ?: if (cid.isNotEmpty()) "Bild" else "Anhang")
                    put("mimeType", teil.contentType.substringBefore(';').trim().lowercase())
                    put("groesse", bytes.size); put("cid", cid)
                    put("inline", Part.INLINE.equals(teil.disposition, true) || (cid.isNotEmpty() && name == null))
                    if (bytes.size <= maxAnhang) put("base64", Base64.getEncoder().encodeToString(bytes))
                }
            }))
        }
    }

    private fun lade(k: MailKonfig, passwort: String, d: JsonObject): JsonElement {
        val uids = d.uids(); if (uids.isEmpty()) return JsonArray(emptyList())
        val max = (d["maxAnhang"] as? JsonPrimitive)?.intOrNull ?: (3 * 1024 * 1024)
        return imOrdner(k, passwort, d.text("ordner", "INBOX")) { _, f ->
            val nachrichten = f.getMessagesByUID(uids).filterNotNull().toTypedArray()
            f.fetch(nachrichten, FetchProfile().apply { add(UIDFolder.FetchProfileItem.UID); add(FetchProfile.Item.FLAGS); add(FetchProfile.Item.SIZE); add(FetchProfile.Item.ENVELOPE) })
            JsonArray(nachrichten.mapNotNull { m -> runCatching { alsMail(f, m as MimeMessage, max) }.getOrNull() })
        }
    }

    private fun suche(k: MailKonfig, passwort: String, d: JsonObject): JsonElement = imOrdner(k, passwort, d.text("ordner", "INBOX")) { _, f ->
        val teile = mutableListOf<SearchTerm>()
        d.text("text").takeIf { it.isNotBlank() }?.let { teile += OrTerm(arrayOf(SubjectTerm(it), BodyTerm(it), FromStringTerm(it), RecipientStringTerm(Message.RecipientType.TO, it))) }
        d.text("von").takeIf { it.isNotBlank() }?.let { teile += FromStringTerm(it) }
        d.text("an").takeIf { it.isNotBlank() }?.let { teile += RecipientStringTerm(Message.RecipientType.TO, it) }
        d.text("betreff").takeIf { it.isNotBlank() }?.let { teile += SubjectTerm(it) }
        datum(d.text("seit"))?.let { teile += ReceivedDateTerm(ComparisonTerm.GE, it) }
        datum(d.text("bis"))?.let { teile += ReceivedDateTerm(ComparisonTerm.LT, it) }
        if (d.wahr("ungelesen")) teile += FlagTerm(Flags(Flags.Flag.SEEN), false)
        if (d.wahr("markiert")) teile += FlagTerm(Flags(Flags.Flag.FLAGGED), true)
        val treffer = if (f.messageCount == 0) emptyArray() else when (teile.size) { 0 -> f.messages; 1 -> f.search(teile[0]); else -> f.search(AndTerm(teile.toTypedArray())) }
        f.fetch(treffer, FetchProfile().apply { add(UIDFolder.FetchProfileItem.UID) })
        val max = (d["max"] as? JsonPrimitive)?.intOrNull ?: 200
        JsonArray(treffer.map { f.getUID(it) }.sortedDescending().take(max).map { JsonPrimitive(it) })
    }

    private fun aktion(k: MailKonfig, passwort: String, d: JsonObject): JsonElement {
        val uids = d.uids(); if (uids.isEmpty()) return buildJsonObject { put("neueUids", JsonObject(emptyMap())) }
        val was = d.text("aktion")
        return imOrdner(k, passwort, d.text("ordner", "INBOX"), schreiben = true) { store, f ->
            val msgs = f.getMessagesByUID(uids).filterNotNull().toTypedArray()
            val neue = mutableMapOf<String, JsonElement>()
            fun flag(flag: Flags.Flag, an: Boolean) = f.setFlags(msgs, Flags(flag), an)
            when (was) {
                "gelesen" -> flag(Flags.Flag.SEEN, true); "ungelesen" -> flag(Flags.Flag.SEEN, false)
                "markieren" -> flag(Flags.Flag.FLAGGED, true); "entmarkieren" -> flag(Flags.Flag.FLAGGED, false)
                "beantwortet" -> flag(Flags.Flag.ANSWERED, true)
                "verschieben", "kopieren" -> {
                    val ziel = store.getFolder(d.text("ziel").ifBlank { throw MailFehler("Kein Zielordner angegeben") })
                    val alteUids = msgs.map { f.getUID(it) }
                    val ergebnis = if (was == "kopieren") f.copyUIDMessages(msgs, ziel)
                        else if ((store as org.eclipse.angus.mail.imap.IMAPStore).hasCapability("MOVE")) f.moveUIDMessages(msgs, ziel)
                        else f.copyUIDMessages(msgs, ziel).also { flag(Flags.Flag.DELETED, true); f.expunge(msgs) }
                    ergebnis?.forEachIndexed { i, a -> if (a != null && i < alteUids.size) neue[alteUids[i].toString()] = JsonPrimitive(a.uid) }
                }
                "loeschen" -> { flag(Flags.Flag.DELETED, true); f.expunge(msgs) }
                else -> throw MailFehler("Unbekannte Aktion: $was")
            }
            buildJsonObject { put("neueUids", JsonObject(neue)) }
        }
    }

    private fun ordnerAktion(k: MailKonfig, passwort: String, d: JsonObject): JsonElement = oeffne(k, passwort).use { store ->
        val f = store.getFolder(d.text("pfad"))
        when (d.text("aktion")) {
            "anlegen" -> if (!f.exists() && !f.create(Folder.HOLDS_MESSAGES)) throw MailFehler("Ordner konnte nicht angelegt werden")
            "umbenennen" -> if (!f.renameTo(store.getFolder(d.text("neu")))) throw MailFehler("Ordner konnte nicht umbenannt werden")
            "loeschen" -> if (!f.delete(false)) throw MailFehler("Ordner konnte nicht gelöscht werden")
            else -> throw MailFehler("Unbekannte Ordneraktion: ${d.text("aktion")}")
        }
        JsonPrimitive(true)
    }

    private fun nachricht(f: IMAPFolder, uid: Long): MimeMessage =
        (f.getMessageByUID(uid) as? MimeMessage) ?: throw MailFehler("Die E-Mail gibt es auf dem Server nicht mehr.")

    private fun anhang(k: MailKonfig, passwort: String, d: JsonObject): JsonElement = imOrdner(k, passwort, d.text("ordner", "INBOX")) { _, f ->
        val nr = d.text("nr", "0").toIntOrNull() ?: 0
        val teil = anhangTeile(nachricht(f, d.text("uid").toLongOrNull() ?: 0)).filter { p -> p.inputStream.use { it.read() } != -1 }.getOrNull(nr)
            ?: throw MailFehler("Anhang nicht gefunden")
        buildJsonObject {
            put("name", teil.fileName?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) } ?: "Anhang")
            put("mimeType", teil.contentType.substringBefore(';').trim().lowercase())
            put("base64", Base64.getEncoder().encodeToString(teil.inputStream.use { it.readBytes() }))
        }
    }

    private fun quelle(k: MailKonfig, passwort: String, d: JsonObject): JsonElement = imOrdner(k, passwort, d.text("ordner", "INBOX")) { _, f ->
        val aus = ByteArrayOutputStream(); nachricht(f, d.text("uid").toLongOrNull() ?: 0).writeTo(aus)
        JsonPrimitive(Base64.getEncoder().encodeToString(aus.toByteArray()))
    }

    companion object {
        private const val UIDFolderLetzte = jakarta.mail.UIDFolder.LASTUID
        const val SCHNITTSTELLE = 2
        private const val MAX_HTML = 1_500_000
        private val SPEZIAL = listOf("\\sent" to "SENT", "\\drafts" to "DRAFTS", "\\trash" to "TRASH", "\\junk" to "JUNK", "\\archive" to "ARCHIVE", "\\all" to "ALLE")
        private val SPEZIAL_NAMEN = listOf(
            "SENT" to Regex("(?i)^(inbox[./])?(sent|gesendet|gesendete (objekte|elemente|nachrichten)|sent (items|messages|mail))$"),
            "DRAFTS" to Regex("(?i)^(inbox[./])?(drafts|entw(ü|ue)rfe|entwurf)$"),
            "TRASH" to Regex("(?i)^(inbox[./])?(trash|papierkorb|gel(ö|oe)schte (objekte|elemente)|deleted (items|messages)|bin)$"),
            "JUNK" to Regex("(?i)^(inbox[./])?(junk|spam|junk-e-mail|unerw(ü|ue)nscht|spamverdacht|bulk)$"),
            "ARCHIVE" to Regex("(?i)^(inbox[./])?(archiv|archive)$"),
        )

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
