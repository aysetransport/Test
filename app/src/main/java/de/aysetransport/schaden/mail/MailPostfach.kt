package de.aysetransport.schaden.mail

import android.net.Uri
import androidx.room.withTransaction
import de.aysetransport.schaden.data.SchadensfallRepository
import de.aysetransport.schaden.data.db.SchadensfallDatenbank
import de.aysetransport.schaden.data.model.MailNachricht
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.data.model.VerlaufEintrag
import de.aysetransport.schaden.data.model.VerlaufTyp
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Clock
import java.time.LocalDate

/** Eine neu zugeordnete Mail – für die Benachrichtigung. */
data class NeueMail(val fall: Schadensfall, val von: String, val betreff: String)

/**
 * Verbindet das Postfach mit den Fällen.
 *
 * Senden: Der Betreff bekommt die Fallnummer („[Fall 2026-001]“), die Mail landet im Verlauf.
 * Abrufen: Eine Mail gehört zu einem Fall, wenn ihr Betreff die Fallnummer trägt, wenn sie auf eine
 * Mail des Falls antwortet oder wenn ihr Absender bei genau einem offenen Fall als Geschädigter
 * oder Auftraggeber eingetragen ist. Alle anderen Mails bleiben unberührt.
 */
class MailPostfach(
    private val db: SchadensfallDatenbank,
    private val repository: SchadensfallRepository,
    private val speicher: MailSpeicher,
    private val dienst: MailDienst,
    private val arbeitsOrdner: File,
    private val uhr: Clock = Clock.systemDefaultZone(),
) {
    private val dao = db.dao()
    private val sperre = Mutex()

    /** Prüft die Zugangsdaten und speichert das Konto. */
    suspend fun einrichten(konfig: MailKonfig, passwort: String) {
        dienst.pruefe(konfig, passwort)
        speicher.speichere(konfig, passwort)
    }

    /**
     * Sendet eine Mail zu einem Fall und vermerkt sie im Verlauf.
     * @throws java.io.IOException wenn das Postfach nicht erreichbar ist oder ablehnt – dann wird nichts vermerkt
     */
    suspend fun senden(fallId: Long, entwurf: MailEntwurf) {
        val (konfig, passwort) = zugang()
        val fall = repository.ladeFall(fallId) ?: return
        val mitKennung = entwurf.copy(betreff = mitFallnummer(entwurf.betreff, fall.fallnummer))
        val messageId = dienst.sende(konfig, passwort, mitKennung)
        val anhangText = if (entwurf.anhaenge.isEmpty()) "" else "\nAnhänge: " + entwurf.anhaenge.joinToString(", ") { it.name }
        vermerke(
            fall,
            MailNachricht(
                fallId = fall.id, eingehend = false, messageId = messageId, antwortAuf = entwurf.antwortAuf, von = konfig.adresse,
                an = entwurf.an, betreff = mitKennung.betreff, text = entwurf.text, zeitpunkt = uhr.instant(),
            ),
            verlaufText = "An: ${entwurf.an}\nBetreff: ${mitKennung.betreff}$anhangText\n\n${entwurf.text}",
        )
    }

    /**
     * Ruft das Postfach ab und übernimmt Mails, die zu einem Fall gehören.
     * @return die neu übernommenen Mails
     */
    suspend fun abrufen(): List<NeueMail> = sperre.withLock {
        val z = speicher.aktuell()
        val (konfig, passwort) = zugang()
        val ergebnis = try {
            dienst.hole(konfig, passwort, z.letzteUid, z.uidValidity)
        } catch (e: java.io.IOException) {
            speicher.fehler(e.message ?: "Postfach nicht erreichbar")
            throw e
        }
        val neu = mutableListOf<NeueMail>()
        for (mail in ergebnis.mails.sortedBy { it.zeitpunkt }) {
            if (mail.von.equals(konfig.adresse, ignoreCase = true)) continue // eigene Mails (z. B. Kopie an sich selbst)
            if (dao.mailPerMessageId(mail.messageId) != null) continue
            val fall = zuordnen(mail) ?: continue
            for (anhang in mail.anhaenge) {
                runCatching {
                    val datei = File(arbeitsOrdner, "mail-${System.nanoTime()}").apply { writeBytes(anhang.bytes) }
                    try { repository.anlageHinzufuegen(fall.id, Uri.fromFile(datei), anhang.name) } finally { datei.delete() }
                }
            }
            val absender = if (mail.vonName.isNotBlank()) "${mail.vonName} <${mail.von}>" else mail.von
            val anhangText = if (mail.anhaenge.isEmpty()) "" else "\nAnhänge (zum Fall genommen): " + mail.anhaenge.joinToString(", ") { it.name }
            vermerke(
                fall,
                MailNachricht(
                    fallId = fall.id, eingehend = true, messageId = mail.messageId, antwortAuf = mail.antwortAuf.firstOrNull().orEmpty(),
                    von = mail.von, an = mail.an, betreff = mail.betreff, text = mail.text, zeitpunkt = mail.zeitpunkt,
                ),
                verlaufText = "Von: $absender\nBetreff: ${mail.betreff}$anhangText\n\n${mail.text}",
                wiedervorlageHeute = true,
            )
            neu += NeueMail(fall, absender, mail.betreff)
        }
        speicher.abgerufen(ergebnis.letzteUid, ergebnis.uidValidity, uhr.millis())
        neu
    }

    private suspend fun zuordnen(mail: EmpfangeneMail): Schadensfall? {
        FALLNUMMER.find(mail.betreff)?.let { treffer -> dao.fallPerNummer(treffer.groupValues[1])?.let { return it } }
        for (bezug in mail.antwortAuf) {
            dao.mailPerMessageId(bezug)?.let { return repository.ladeFall(it.fallId) }
        }
        return dao.offeneFaellePerEmail(mail.von).singleOrNull()
    }

    /**
     * Legt die Mail ab und schreibt sie in den Verlauf (von dort wandert sie in die Cloud).
     * Bei einer eingehenden Mail rückt die Wiedervorlage auf heute vor, damit sie nicht untergeht.
     */
    private suspend fun vermerke(fall: Schadensfall, mail: MailNachricht, verlaufText: String, wiedervorlageHeute: Boolean = false) {
        val heute = LocalDate.now(uhr)
        db.withTransaction {
            val eintrag = VerlaufEintrag(
                fallId = fall.id,
                zeitpunkt = mail.zeitpunkt,
                typ = if (mail.eingehend) VerlaufTyp.EMAIL_EINGANG else VerlaufTyp.EMAIL_AUSGANG,
                text = verlaufText.take(MAX_VERLAUF_TEXT),
            )
            dao.einfuegen(eintrag)
            dao.einfuegen(mail.copy(verlaufUuid = eintrag.uuid, text = mail.text.take(MAX_VERLAUF_TEXT)))
            val aktuell = dao.lade(fall.id) ?: return@withTransaction
            val vorziehen = wiedervorlageHeute && aktuell.status.offen && (aktuell.wiedervorlageAm == null || aktuell.wiedervorlageAm.isAfter(heute))
            dao.aktualisieren(
                aktuell.copy(
                    wiedervorlageAm = if (vorziehen) heute else aktuell.wiedervorlageAm,
                    wiedervorlageNotiz = if (vorziehen) "Neue E-Mail beantworten" else aktuell.wiedervorlageNotiz,
                    geaendertAm = uhr.instant(),
                    syncOffen = true,
                ),
            )
        }
        repository.angestossen()
    }

    private suspend fun zugang(): Pair<MailKonfig, String> {
        val konfig = speicher.aktuell().konfig ?: throw java.io.IOException("Kein E-Mail-Konto eingerichtet")
        val passwort = speicher.passwort() ?: throw MailAnmeldungFehler("Das Passwort des E-Mail-Kontos fehlt. Bitte das Konto neu einrichten.")
        return konfig to passwort
    }

    companion object {
        private const val MAX_VERLAUF_TEXT = 15_000
        val FALLNUMMER = Regex("\\[Fall (\\d{4}-\\d+)]")

        /** Hängt „[Fall 2026-001]“ an den Betreff, sofern er noch nicht drinsteht. */
        fun mitFallnummer(betreff: String, fallnummer: String): String =
            if (FALLNUMMER.containsMatchIn(betreff)) betreff else "${betreff.trim()} [Fall $fallnummer]".trim()
    }
}
