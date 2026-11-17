package de.aysetransport.schaden.mail

import android.net.Uri
import androidx.room.withTransaction
import de.aysetransport.schaden.data.SchadensfallRepository
import de.aysetransport.schaden.data.db.SchadensfallDatenbank
import de.aysetransport.schaden.data.model.Aktivitaet
import de.aysetransport.schaden.data.model.AktivitaetArt
import de.aysetransport.schaden.data.model.AuftraggeberEintrag
import de.aysetransport.schaden.data.model.Loeschung
import de.aysetransport.schaden.data.model.MailKontoArt
import de.aysetransport.schaden.data.model.MailNachricht
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.data.model.VerlaufEintrag
import de.aysetransport.schaden.data.model.VerlaufTyp
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.LocalDate

/** Eine neu abgerufene Mail – für die Benachrichtigung. Ohne Fall und Firma ist sie „nicht zugeordnet“. */
data class NeueMail(
    val fall: Schadensfall?,
    val von: String,
    val betreff: String,
    val firma: AuftraggeberEintrag? = null,
    val mailId: Long = 0,
)

/** Wohin eine Mail gehört. Alles leer = nicht zugeordnet. */
data class MailZuordnung(val fallId: Long? = null, val auftraggeberUuid: String? = null, val kundeRef: String = "") {
    companion object {
        val KEINE = MailZuordnung()
    }
}

/**
 * Verbindet die Postfächer mit Fällen und Firmen.
 *
 * Zwei Postfächer: [MailKontoArt.SCHADEN] für Schadensfälle und [MailKontoArt.ALLGEMEIN] für alles
 * andere. Mails aus einem Fall gehen über das Schadens-Postfach, Mails an Firmen und Ansprechpartner
 * über das allgemeine. Ist nur eines eingerichtet, läuft alles darüber.
 *
 * Senden: Aus einem Fall bekommt der Betreff die Fallnummer („[Fall 2026-001]“), die Mail landet im
 * Verlauf. An eine Firma landet sie in deren Kontakthistorie.
 *
 * Abrufen (beide Postfächer), in dieser Reihenfolge:
 * 1. Fallnummer im Betreff → Fall
 * 2. Antwort auf eine bekannte Mail → dorthin, wo diese hingehört
 * 3. nur Schadens-Postfach: Absender ist bei genau einem offenen Fall als Geschädigter oder Auftraggeber eingetragen → Fall
 * 4. Absender passt zur E-Mail der Firma, eines Ansprechpartners oder zur Domain der Firma → Firma
 * 5. sonst: Eingang „Nicht zugeordnet“
 * Im Postfach selbst wird nichts verändert.
 */
class MailPostfach(
    private val db: SchadensfallDatenbank,
    private val repository: SchadensfallRepository,
    private val konten: Map<MailKontoArt, MailSpeicher>,
    private val dienst: MailDienst,
    private val arbeitsOrdner: File,
    val anhangSpeicher: MailAnhangSpeicher,
    private val uhr: Clock = Clock.systemDefaultZone(),
) {
    /** Kurzform mit nur einem Postfach (für Schadensfälle). */
    constructor(db: SchadensfallDatenbank, repository: SchadensfallRepository, speicher: MailSpeicher, dienst: MailDienst, arbeitsOrdner: File) :
        this(db, repository, mapOf(MailKontoArt.SCHADEN to speicher), dienst, arbeitsOrdner, MailAnhangSpeicher(File(arbeitsOrdner, MailAnhangSpeicher.ORDNER)))

    private val dao = db.dao()
    private val crm = db.crm()
    private val sperre = Mutex()

    fun speicher(art: MailKontoArt): MailSpeicher? = konten[art]

    /** Prüft die Zugangsdaten und speichert das Konto. */
    suspend fun einrichten(art: MailKontoArt, konfig: MailKonfig, passwort: String) {
        val speicher = konten[art] ?: throw IOException("Dieses Postfach wird nicht unterstützt")
        dienst.pruefe(konfig, passwort)
        speicher.speichere(konfig, passwort)
    }

    suspend fun einrichten(konfig: MailKonfig, passwort: String) = einrichten(MailKontoArt.SCHADEN, konfig, passwort)

    /** Das Postfach, über das gesendet wird: das gewünschte, sonst das andere. `null`, wenn keines eingerichtet ist. */
    suspend fun kontoFuer(gewuenscht: MailKontoArt): MailKontoArt? =
        listOf(gewuenscht, *MailKontoArt.entries.filter { it != gewuenscht }.toTypedArray())
            .firstOrNull { konten[it]?.aktuell()?.konfig != null }

    suspend fun eingerichteteKonten(): List<Pair<MailKontoArt, MailKonfig>> =
        MailKontoArt.entries.mapNotNull { art -> konten[art]?.aktuell()?.konfig?.let { art to it } }

    /**
     * Sendet eine Mail zu einem Fall und vermerkt sie im Verlauf.
     * @throws IOException wenn das Postfach nicht erreichbar ist oder ablehnt – dann wird nichts vermerkt
     */
    suspend fun senden(fallId: Long, entwurf: MailEntwurf, konto: MailKontoArt = MailKontoArt.SCHADEN) =
        senden(entwurf, MailZuordnung(fallId = fallId), konto)

    /**
     * Sendet eine Mail und legt sie dort ab, wohin sie gehört: im Verlauf des Falls oder in der
     * Kontakthistorie der Firma.
     */
    suspend fun senden(entwurf: MailEntwurf, ziel: MailZuordnung, gewuenscht: MailKontoArt) {
        val art = kontoFuer(gewuenscht) ?: throw IOException("Kein E-Mail-Konto eingerichtet")
        val (konfig, passwort) = zugang(art)
        val fall = ziel.fallId?.let { repository.ladeFall(it) }
        val mitKennung = if (fall != null) entwurf.copy(betreff = mitFallnummer(entwurf.betreff, fall.fallnummer)) else entwurf
        val messageId = dienst.sende(konfig, passwort, mitKennung)
        val anhangText = if (entwurf.anhaenge.isEmpty()) "" else "\nAnhänge: " + entwurf.anhaenge.joinToString(", ") { it.name }
        val bccText = if (entwurf.bcc.isBlank()) "" else "\nBcc: ${entwurf.bcc}"
        val mail = MailNachricht(
            fallId = fall?.id, auftraggeberUuid = if (fall == null) ziel.auftraggeberUuid else null, kundeRef = ziel.kundeRef.trim(),
            konto = art, eingehend = false, messageId = messageId, antwortAuf = entwurf.antwortAuf, von = konfig.adresse,
            an = entwurf.an, betreff = mitKennung.betreff, text = entwurf.text.take(MAX_TEXT), zeitpunkt = uhr.instant(),
            anhaenge = entwurf.anhaenge.joinToString("\n") { it.name },
        )
        val text = "An: ${entwurf.an}$bccText\nBetreff: ${mitKennung.betreff}$anhangText\n\n${entwurf.text}"
        db.withTransaction { ablegen(mail, text) }
        repository.angestossen()
    }

    /**
     * Ruft alle eingerichteten Postfächer ab und legt jede neue Mail ab – zugeordnet oder im Eingang
     * „Nicht zugeordnet“. Fällt ein Postfach aus, wird das andere trotzdem abgerufen.
     * @return die neu übernommenen Mails
     * @throws IOException wenn ein Postfach nicht erreichbar war (nachdem die anderen abgerufen wurden)
     */
    suspend fun abrufen(): List<NeueMail> = sperre.withLock {
        val neu = mutableListOf<NeueMail>()
        var fehler: IOException? = null
        val eigene = eingerichteteKonten().map { it.second.adresse.lowercase() }.toSet()
        for (art in MailKontoArt.entries) {
            val speicher = konten[art] ?: continue
            val z = speicher.aktuell()
            if (z.konfig == null) continue
            val (konfig, passwort) = try { zugang(art) } catch (e: IOException) { fehler = fehler ?: e; continue }
            val ergebnis = try {
                dienst.hole(konfig, passwort, z.letzteUid, z.uidValidity)
            } catch (e: IOException) {
                speicher.fehler(e.message ?: "Postfach nicht erreichbar")
                fehler = fehler ?: e
                continue
            }
            for (mail in ergebnis.mails.sortedBy { it.zeitpunkt }) {
                if (mail.von.lowercase() in eigene) continue // eigene Mails (z. B. Kopie an sich selbst)
                if (dao.mailPerMessageId(mail.messageId) != null) continue
                neu += uebernimm(mail, art)
            }
            speicher.abgerufen(ergebnis.letzteUid, ergebnis.uidValidity, uhr.millis())
        }
        if (neu.isNotEmpty()) repository.angestossen()
        fehler?.let { if (neu.isEmpty()) throw it }
        neu
    }

    private suspend fun uebernimm(mail: EmpfangeneMail, art: MailKontoArt): NeueMail {
        val ziel = automatisch(mail, art)
        val fall = ziel.fallId?.let { repository.ladeFall(it) }
        // Anhänge einer Fall-Mail werden Anlagen des Falls, sonst bleiben sie bei der Mail.
        if (fall != null) {
            for (anhang in mail.anhaenge) {
                runCatching {
                    val datei = File(arbeitsOrdner, "mail-${System.nanoTime()}").apply { writeBytes(anhang.bytes) }
                    try { repository.anlageHinzufuegen(fall.id, Uri.fromFile(datei), anhang.name) } finally { datei.delete() }
                }
            }
        }
        val absender = if (mail.vonName.isNotBlank()) "${mail.vonName} <${mail.von}>" else mail.von
        val anhangText = when {
            mail.anhaenge.isEmpty() -> ""
            fall != null -> "\nAnhänge (zum Fall genommen): " + mail.anhaenge.joinToString(", ") { it.name }
            else -> "\nAnhänge: " + mail.anhaenge.joinToString(", ") { it.name }
        }
        val nachricht = MailNachricht(
            fallId = fall?.id, auftraggeberUuid = if (fall == null) ziel.auftraggeberUuid else null, konto = art,
            eingehend = true, messageId = mail.messageId, antwortAuf = mail.antwortAuf.firstOrNull().orEmpty(),
            von = mail.von, an = mail.an, betreff = mail.betreff, text = mail.text.take(MAX_TEXT), zeitpunkt = mail.zeitpunkt,
            anhaenge = mail.anhaenge.joinToString("\n") { it.name },
        )
        val id = db.withTransaction { ablegen(nachricht, "Von: $absender\nBetreff: ${mail.betreff}$anhangText\n\n${mail.text}", wiedervorlageHeute = true) }
        if (fall == null) anhangSpeicher.speichere(id, mail.anhaenge)
        val firma = nachricht.auftraggeberUuid?.let { dao.auftraggeberPerKennung(it) }
        return NeueMail(fall, absender, mail.betreff, firma, id)
    }

    /** Die automatische Zuordnung einer eingehenden Mail (siehe Klassenbeschreibung). */
    suspend fun automatisch(mail: EmpfangeneMail, art: MailKontoArt): MailZuordnung {
        FALLNUMMER.find(mail.betreff)?.let { treffer -> dao.fallPerNummer(treffer.groupValues[1])?.let { return MailZuordnung(fallId = it.id) } }
        for (bezug in mail.antwortAuf) {
            dao.mailPerMessageId(bezug)?.takeIf { it.fallId != null || it.auftraggeberUuid != null }?.let {
                return MailZuordnung(fallId = it.fallId, auftraggeberUuid = it.auftraggeberUuid)
            }
        }
        if (art == MailKontoArt.SCHADEN) dao.offeneFaellePerEmail(mail.von).singleOrNull()?.let { return MailZuordnung(fallId = it.id) }
        firmaZuAdresse(mail.von)?.let { return MailZuordnung(auftraggeberUuid = it.uuid) }
        return MailZuordnung.KEINE
    }

    /**
     * Firma zu einer Absenderadresse: zuerst exakt (E-Mail der Firma oder eines Ansprechpartners), dann
     * über die Domain (aus E-Mail oder Website der Firma). Freemail-Domains zählen nicht. Passen mehrere
     * Firmen, wird nicht geraten.
     */
    suspend fun firmaZuAdresse(adresse: String): AuftraggeberEintrag? {
        val a = adresse.trim().lowercase()
        if ('@' !in a) return null
        val firmen = dao.ladeAlleAuftraggeber()
        val kontakte = crm.ladeAlleKontaktpersonen()
        val genau = (firmen.filter { it.email.trim().lowercase() == a }.map { it.uuid } +
            kontakte.filter { it.email.trim().lowercase() == a }.map { it.auftraggeberUuid }).distinct()
        if (genau.size == 1) return firmen.firstOrNull { it.uuid == genau.single() }
        if (genau.size > 1) return null
        val domain = domainVon(a) ?: return null
        if (domain in FREEMAIL) return null
        val passend = firmen.filter { f ->
            domainVon(f.email) == domain || websiteDomain(f.website) == domain ||
                kontakte.any { it.auftraggeberUuid == f.uuid && domainVon(it.email) == domain }
        }
        return passend.singleOrNull()
    }

    /**
     * Ordnet eine Mail von Hand neu zu: einem Fall, einer Firma und/oder einem Kunden. Der bisherige
     * Eintrag im Verlauf bzw. in der Kontakthistorie wird entfernt, ein neuer angelegt. Gespeicherte
     * Anhänge werden bei einem Fall zu dessen Anlagen.
     */
    suspend fun zuordnen(mailId: Long, ziel: MailZuordnung) {
        val alt = crm.mail(mailId) ?: return
        val fall = ziel.fallId?.let { repository.ladeFall(it) }
        val neueFirma = if (fall == null) ziel.auftraggeberUuid?.takeIf { it.isNotBlank() } else null
        if (alt.fallId == fall?.id && alt.auftraggeberUuid == neueFirma && alt.kundeRef == ziel.kundeRef.trim()) return
        db.withTransaction {
            val jetzt = uhr.instant()
            if (alt.verlaufUuid.isNotEmpty() && alt.fallId != fall?.id) {
                val eintrag = dao.verlaufPerKennung(alt.verlaufUuid)
                val altFall = alt.fallId?.let { dao.lade(it) }
                if (eintrag != null) {
                    dao.loescheVerlaufPerKennung(eintrag.uuid)
                    if (altFall != null) dao.merkeLoeschung(Loeschung(eintrag.uuid, Loeschung.Art.VERLAUF, altFall.uuid, jetzt))
                }
            }
            if (alt.aktivitaetUuid.isNotEmpty() && (fall != null || alt.auftraggeberUuid != neueFirma)) {
                crm.aktivitaetPerKennung(alt.aktivitaetUuid)?.let {
                    crm.loescheAktivitaet(it.uuid)
                    dao.merkeLoeschung(Loeschung(it.uuid, Loeschung.Art.AKTIVITAET, it.auftraggeberUuid, jetzt))
                }
            }
            val behaltenVerlauf = alt.fallId == fall?.id && fall != null
            val behaltenAktivitaet = fall == null && alt.auftraggeberUuid == neueFirma && neueFirma != null
            val neu = alt.copy(
                fallId = fall?.id, auftraggeberUuid = neueFirma, kundeRef = ziel.kundeRef.trim(),
                verlaufUuid = if (behaltenVerlauf) alt.verlaufUuid else "", aktivitaetUuid = if (behaltenAktivitaet) alt.aktivitaetUuid else "",
            )
            val text = (if (alt.eingehend) "Von: ${alt.von}" else "An: ${alt.an}") +
                "\nBetreff: ${alt.betreff}" + (if (alt.anhaenge.isBlank()) "" else "\nAnhänge: " + alt.anhaenge.lines().joinToString(", ")) + "\n\n${alt.text}"
            if (!behaltenVerlauf && !behaltenAktivitaet) ablegen(neu, text) else crm.speichereMail(neu)
        }
        if (fall != null) {
            for (datei in anhangSpeicher.dateien(mailId)) {
                runCatching { repository.anlageHinzufuegen(fall.id, Uri.fromFile(datei), anhangSpeicher.anzeigename(datei)) }
            }
            anhangSpeicher.loesche(mailId)
        }
        repository.angestossen()
    }

    /**
     * Speichert die Mail und legt – je nach Zuordnung – einen Verlaufseintrag im Fall oder eine Aktivität
     * bei der Firma an. Bei einer eingehenden Fall-Mail rückt die Wiedervorlage auf heute vor.
     * Läuft innerhalb einer Transaktion. @return die Kennung der Mail
     */
    private suspend fun ablegen(mail: MailNachricht, text: String, wiedervorlageHeute: Boolean = false): Long {
        val fall = mail.fallId?.let { dao.lade(it) }
        if (fall != null) {
            val eintrag = VerlaufEintrag(
                fallId = fall.id, zeitpunkt = mail.zeitpunkt,
                typ = if (mail.eingehend) VerlaufTyp.EMAIL_EINGANG else VerlaufTyp.EMAIL_AUSGANG,
                text = text.take(MAX_TEXT),
            )
            dao.einfuegen(eintrag)
            val heute = LocalDate.now(uhr)
            val vorziehen = wiedervorlageHeute && mail.eingehend && fall.status.offen && (fall.wiedervorlageAm == null || fall.wiedervorlageAm.isAfter(heute))
            dao.aktualisieren(
                fall.copy(
                    wiedervorlageAm = if (vorziehen) heute else fall.wiedervorlageAm,
                    wiedervorlageNotiz = if (vorziehen) "Neue E-Mail beantworten" else fall.wiedervorlageNotiz,
                    geaendertAm = uhr.instant(),
                    syncOffen = true,
                ),
            )
            return crm.speichereMail(mail.copy(verlaufUuid = eintrag.uuid, aktivitaetUuid = ""))
        }
        val firma = mail.auftraggeberUuid
        if (firma != null) {
            val aktivitaet = Aktivitaet(
                auftraggeberUuid = firma,
                datum = mail.zeitpunkt.atZone(uhr.zone).toLocalDate(),
                art = AktivitaetArt.EMAIL,
                text = ((if (mail.eingehend) "E-Mail erhalten" else "E-Mail gesendet") + "\n" + text).take(MAX_TEXT),
                kontakt = kontaktName(firma, if (mail.eingehend) mail.von else mail.an),
                geaendertAm = uhr.instant(),
            )
            crm.speichere(aktivitaet)
            return crm.speichereMail(mail.copy(aktivitaetUuid = aktivitaet.uuid, verlaufUuid = ""))
        }
        return crm.speichereMail(mail.copy(verlaufUuid = "", aktivitaetUuid = ""))
    }

    private suspend fun kontaktName(firma: String, adressen: String): String {
        val liste = adressen.split(',', ';').map { it.trim().substringAfter('<').substringBefore('>').lowercase() }
        return crm.ladeKontaktpersonen(firma).firstOrNull { it.email.isNotBlank() && it.email.trim().lowercase() in liste }?.name.orEmpty()
    }

    private suspend fun zugang(art: MailKontoArt): Pair<MailKonfig, String> {
        val speicher = konten[art] ?: throw IOException("Kein E-Mail-Konto eingerichtet")
        val konfig = speicher.aktuell().konfig ?: throw IOException("Kein E-Mail-Konto eingerichtet")
        val passwort = speicher.passwort() ?: throw MailAnmeldungFehler("Das Passwort des E-Mail-Kontos fehlt. Bitte das Konto neu einrichten.")
        return konfig to passwort
    }

    companion object {
        private const val MAX_TEXT = 15_000
        val FALLNUMMER = Regex("\\[Fall (\\d{4}-\\d+)]")

        /** Adressen großer Mail-Anbieter verraten nichts über die Firma. */
        val FREEMAIL = setOf(
            "gmail.com", "googlemail.com", "gmx.de", "gmx.net", "gmx.at", "gmx.ch", "web.de", "t-online.de", "outlook.com", "outlook.de",
            "hotmail.com", "hotmail.de", "live.com", "live.de", "msn.com", "yahoo.com", "yahoo.de", "icloud.com", "me.com", "mac.com",
            "aol.com", "aol.de", "freenet.de", "posteo.de", "mail.de", "online.de", "arcor.de", "vodafone.de", "email.de", "mailbox.org",
            "ionos.de", "proton.me", "protonmail.com",
        )

        fun domainVon(adresse: String): String? =
            adresse.trim().lowercase().substringAfter('<').substringBefore('>').substringAfterLast('@', "").takeIf { it.contains('.') }

        /** `https://www.beispiel.de/kontakt` → `beispiel.de` */
        fun websiteDomain(website: String): String? =
            website.trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.")
                .substringBefore('/').substringBefore('?').takeIf { it.contains('.') && ' ' !in it }

        /** Hängt „[Fall 2026-001]“ an den Betreff, sofern er noch nicht drinsteht. */
        fun mitFallnummer(betreff: String, fallnummer: String): String =
            if (FALLNUMMER.containsMatchIn(betreff)) betreff else "${betreff.trim()} [Fall $fallnummer]".trim()
    }
}
