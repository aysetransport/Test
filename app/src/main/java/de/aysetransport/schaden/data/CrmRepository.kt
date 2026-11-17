package de.aysetransport.schaden.data

import androidx.room.withTransaction
import de.aysetransport.schaden.data.db.SchadensfallDatenbank
import de.aysetransport.schaden.data.model.Adresse
import de.aysetransport.schaden.data.model.Aktivitaet
import de.aysetransport.schaden.data.model.Aufgabe
import de.aysetransport.schaden.data.model.AuftraggeberEintrag
import de.aysetransport.schaden.data.model.Chance
import de.aysetransport.schaden.data.model.CrmEintrag
import de.aysetransport.schaden.data.model.Dokument
import de.aysetransport.schaden.data.model.Kontaktperson
import de.aysetransport.schaden.data.model.Loeschung
import de.aysetransport.schaden.data.model.MailNachricht
import de.aysetransport.schaden.data.model.Preis
import de.aysetransport.schaden.data.model.Schadensfall
import kotlinx.coroutines.flow.Flow
import java.time.Clock

/** Alles zu den Firmen (Auftraggebern) im CRM. Jede Änderung stößt den Abgleich mit der Cloud an. */
class CrmRepository(
    private val db: SchadensfallDatenbank,
    private val uhr: Clock = Clock.systemDefaultZone(),
    private val nachAenderung: () -> Unit = {},
) {
    private val dao = db.dao()
    private val crm = db.crm()
    val verknuepfer = CrmVerknuepfer(db, uhr)

    fun beobachteFirmen(): Flow<List<AuftraggeberEintrag>> = dao.beobachteAuftraggeber()
    fun beobachteFirma(uuid: String): Flow<AuftraggeberEintrag?> = dao.beobachteFirma(uuid)
    suspend fun ladeFirma(uuid: String): AuftraggeberEintrag? = dao.auftraggeberPerKennung(uuid)
    suspend fun ladeFirmen(): List<AuftraggeberEintrag> = dao.ladeAlleAuftraggeber()

    fun beobachteAdressen(firma: String) = crm.beobachteAdressen(firma)
    fun beobachteKontakte(firma: String) = crm.beobachteKontaktpersonen(firma)
    fun beobachtePreise(firma: String) = crm.beobachtePreise(firma)
    fun beobachteAktivitaeten(firma: String) = crm.beobachteAktivitaeten(firma)
    fun beobachteAufgaben(firma: String) = crm.beobachteAufgaben(firma)
    fun beobachteDokumente(firma: String) = crm.beobachteDokumente(firma)
    fun beobachteChancen(firma: String) = crm.beobachteChancen(firma)
    fun beobachteAlleChancen() = crm.beobachteAlleChancen()
    fun beobachteFaelle(firma: AuftraggeberEintrag): Flow<List<Schadensfall>> = dao.beobachteFaelleDerFirma(firma.uuid, firma.name)
    fun beobachteMails(firma: AuftraggeberEintrag): Flow<List<MailNachricht>> = crm.beobachteMailsDerFirma(firma.uuid, firma.name)

    fun beobachteAlleKontakte() = crm.beobachteAlleKontaktpersonen()
    fun beobachteAlleAdressen() = crm.beobachteAlleAdressen()
    fun beobachteAlleAktivitaeten() = crm.beobachteAlleAktivitaeten()
    fun beobachteAlleDokumente() = crm.beobachteAlleDokumente()
    fun beobachteAllePreise() = crm.beobachteAllePreise()
    fun beobachteOffeneAufgaben() = crm.beobachteOffeneAufgaben()

    suspend fun ladeKontakte(firma: String) = crm.ladeKontaktpersonen(firma)

    data class AlleDaten(
        val kontakte: List<Kontaktperson>, val adressen: List<Adresse>, val preise: List<Preis>,
        val aktivitaeten: List<Aktivitaet>, val aufgaben: List<Aufgabe>, val dokumente: List<Dokument>,
        val chancen: List<Chance> = emptyList(),
    )

    suspend fun alleDaten() = AlleDaten(
        crm.ladeAlleKontaktpersonen(), crm.ladeAlleAdressen(), crm.ladeAllePreise(),
        crm.ladeAlleAktivitaeten(), crm.ladeAlleAufgaben(), crm.ladeAlleDokumente(), crm.ladeAlleChancen(),
    )

    /**
     * Legt eine Firma an oder ändert sie. Trägt eine neue Firma den Namen einer vorhandenen, wird nichts
     * doppelt angelegt, sondern die vorhandene geliefert.
     * @return Kennung der gespeicherten Firma
     */
    suspend fun firmaSpeichern(firma: AuftraggeberEintrag): String = aenderung {
        val bisher = dao.auftraggeberPerKennung(firma.uuid)
        if (bisher == null) {
            dao.ladeAlleAuftraggeber().firstOrNull { CrmVerknuepfer.normal(it.name) == CrmVerknuepfer.normal(firma.name) }?.let { return@aenderung it.uuid }
        }
        val neu = firma.copy(
            name = firma.name.trim(), rechtsform = firma.rechtsform.trim(), telefon = firma.telefon.trim(), email = firma.email.trim(),
            website = firma.website.trim(), notizen = firma.notizen.trim(), schlagworte = firma.schlagwortListe.joinToString(", "), geaendertAm = uhr.instant(), syncOffen = true,
        )
        dao.speichereAuftraggeber(neu)
        // Fälle tragen den Namen als Text – bei einer Umbenennung mitziehen.
        if (bisher != null && bisher.name != neu.name) {
            for (fall in dao.faelleDerFirma(neu.uuid)) dao.aktualisieren(fall.copy(auftraggeber = neu.name, geaendertAm = uhr.instant(), syncOffen = true))
        }
        neu.uuid
    }

    /** Löscht die Firma samt allem, was an ihr hängt. Fälle bleiben erhalten (mit dem Firmennamen als Text). */
    suspend fun firmaLoeschen(firma: AuftraggeberEintrag) = aenderung {
        crm.ladeAdressen(firma.uuid).forEach { crm.loescheAdresse(it.uuid) }
        crm.ladeKontaktpersonen(firma.uuid).forEach { crm.loescheKontaktperson(it.uuid) }
        crm.ladePreise(firma.uuid).forEach { crm.loeschePreis(it.uuid) }
        crm.ladeAktivitaeten(firma.uuid).forEach { crm.loescheAktivitaet(it.uuid) }
        crm.ladeAufgaben(firma.uuid).forEach { crm.loescheAufgabe(it.uuid) }
        crm.ladeDokumente(firma.uuid).forEach { crm.loescheDokument(it.uuid) }
        crm.ladeChancen(firma.uuid).forEach { crm.loescheChance(it.uuid) }
        crm.loeseMailsVonFirma(firma.uuid)
        dao.loeseFaelleVonFirma(firma.uuid)
        dao.loescheAuftraggeber(firma.uuid)
        // Die Cloud löscht alles, was an der Firma hängt, mit.
        dao.merkeLoeschung(Loeschung(firma.uuid, Loeschung.Art.AUFTRAGGEBER, "", uhr.instant()))
    }

    /** Speichert einen Eintrag (Adresse, Ansprechpartner, Preis …) mit aktuellem Änderungszeitpunkt. */
    suspend fun speichern(eintrag: CrmEintrag) = aenderung { speichereOhneAbgleich(eintrag) }

    internal suspend fun speichereOhneAbgleich(eintrag: CrmEintrag) {
        val jetzt = uhr.instant()
        when (eintrag) {
            is Adresse -> crm.speichere(eintrag.copy(strasse = eintrag.strasse.trim(), plz = eintrag.plz.trim(), ort = eintrag.ort.trim(), notiz = eintrag.notiz.trim(), geaendertAm = jetzt, syncOffen = true))
            is Kontaktperson -> crm.speichere(eintrag.copy(name = eintrag.name.trim(), thema = eintrag.thema.trim(), telefon = eintrag.telefon.trim(), email = eintrag.email.trim(), notiz = eintrag.notiz.trim(), geaendertAm = jetzt, syncOffen = true))
            is Preis -> crm.speichere(eintrag.copy(bezeichnung = eintrag.bezeichnung.trim(), einheit = eintrag.einheit.trim(), notiz = eintrag.notiz.trim(), geaendertAm = jetzt, syncOffen = true))
            is Aktivitaet -> crm.speichere(eintrag.copy(text = eintrag.text.trim(), kontakt = eintrag.kontakt.trim(), geaendertAm = jetzt, syncOffen = true))
            is Aufgabe -> crm.speichere(eintrag.copy(text = eintrag.text.trim(), kontakt = eintrag.kontakt.trim(), geaendertAm = jetzt, syncOffen = true))
            is Dokument -> crm.speichere(eintrag.copy(titel = eintrag.titel.trim(), link = eintrag.link.trim(), notiz = eintrag.notiz.trim(), geaendertAm = jetzt, syncOffen = true))
            is Chance -> crm.speichere(eintrag.copy(titel = eintrag.titel.trim(), notiz = eintrag.notiz.trim(), geaendertAm = jetzt, syncOffen = true))
            else -> error("Unbekannter Eintrag: $eintrag")
        }
    }

    suspend fun loeschen(eintrag: CrmEintrag) = aenderung { loescheOhneAbgleich(eintrag) }

    internal suspend fun loescheOhneAbgleich(eintrag: CrmEintrag) {
        val art = when (eintrag) {
            is Adresse -> { crm.loescheAdresse(eintrag.uuid); Loeschung.Art.ADRESSE }
            is Kontaktperson -> { crm.loescheKontaktperson(eintrag.uuid); Loeschung.Art.ANSPRECHPARTNER }
            is Preis -> { crm.loeschePreis(eintrag.uuid); Loeschung.Art.PREIS }
            is Aktivitaet -> { crm.loescheAktivitaet(eintrag.uuid); Loeschung.Art.AKTIVITAET }
            is Aufgabe -> { crm.loescheAufgabe(eintrag.uuid); Loeschung.Art.AUFGABE }
            is Dokument -> { crm.loescheDokument(eintrag.uuid); Loeschung.Art.DOKUMENT }
            is Chance -> { crm.loescheChance(eintrag.uuid); Loeschung.Art.CHANCE }
            else -> error("Unbekannter Eintrag: $eintrag")
        }
        dao.merkeLoeschung(Loeschung(eintrag.uuid, art, eintrag.auftraggeberUuid, uhr.instant()))
    }

    suspend fun aufgabeErledigt(aufgabe: Aufgabe, erledigt: Boolean) = speichern(aufgabe.copy(erledigt = erledigt))

    /** Führt [weg] in [behalten] zusammen (doppelt angelegte Firma). */
    suspend fun zusammenfuehren(weg: AuftraggeberEintrag, behalten: AuftraggeberEintrag) = aenderung {
        val ziel = dao.auftraggeberPerKennung(behalten.uuid) ?: return@aenderung
        verknuepfer.zusammenfuehren(ziel, weg)
        // Fälle tragen den Namen als Text – auf den der verbleibenden Firma setzen.
        for (fall in dao.faelleDerFirma(ziel.uuid)) if (fall.auftraggeber != ziel.name) dao.aktualisieren(fall.copy(auftraggeber = ziel.name, geaendertAm = uhr.instant(), syncOffen = true))
    }

    /** Fälle ohne Firmenverweis verknüpfen und doppelte Firmen zusammenführen (nach Abgleich, Backup, Import). */
    suspend fun aufraeumen(): Int = db.withTransaction {
        verknuepfer.doppelteFirmenZusammenfuehren() + verknuepfer.verknuepfeAlle()
    }.also { if (it > 0) nachAenderung() }

    private suspend fun <T> aenderung(block: suspend () -> T): T = db.withTransaction { block() }.also { nachAenderung() }
}
