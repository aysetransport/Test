package de.aysetransport.schaden.data

import android.net.Uri
import androidx.room.withTransaction
import de.aysetransport.schaden.data.db.SchadensfallDatenbank
import de.aysetransport.schaden.data.model.Anlage
import de.aysetransport.schaden.data.model.AuftraggeberEintrag
import de.aysetransport.schaden.data.model.Loeschung
import de.aysetransport.schaden.data.model.Ansprechpartner
import de.aysetransport.schaden.data.model.FallStatus
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.data.model.VerlaufEintrag
import de.aysetransport.schaden.data.model.VerlaufTyp
import de.aysetransport.schaden.data.model.neueKennung
import de.aysetransport.schaden.domain.Datum
import de.aysetransport.schaden.domain.Fallnummer
import de.aysetransport.schaden.domain.faelligkeit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDate

/**
 * Zentrale Stelle für alle Änderungen an Schadensfällen. Jede fachlich relevante Änderung
 * (Status, Wiedervorlage, Kontakt, Anlage) wird zusätzlich im Verlauf des Falls festgehalten,
 * damit später nachvollziehbar ist, wer wann was veranlasst hat.
 */
class SchadensfallRepository(
    private val db: SchadensfallDatenbank,
    private val anlagenSpeicher: AnlagenSpeicher,
    private val uhr: Clock = Clock.systemDefaultZone(),
    /** Wird nach jeder Änderung aufgerufen – stößt den Abgleich mit der Cloud an. */
    private val nachAenderung: () -> Unit = {},
) {
    private val dao = db.dao()

    fun beobachteFaelle(): Flow<List<Schadensfall>> = dao.beobachteAlle()

    fun beobachteFall(id: Long): Flow<Schadensfall?> = dao.beobachte(id)

    fun beobachteVerlauf(fallId: Long): Flow<List<VerlaufEintrag>> = dao.beobachteVerlauf(fallId)

    fun beobachteAnlagen(fallId: Long): Flow<List<Anlage>> = dao.beobachteAnlagen(fallId)

    fun beobachteAnlagenAnzahl(): Flow<Map<Long, Int>> =
        dao.beobachteAnlagenAnzahl().map { liste -> liste.associate { it.fallId to it.anzahl } }

    suspend fun ladeFall(id: Long): Schadensfall? = dao.lade(id)

    suspend fun ladeAlleFaelle(): List<Schadensfall> = dao.ladeAlle()

    suspend fun ladeAnlagenAnzahl(): Map<Long, Int> = dao.ladeAnlagenAnzahl().associate { it.fallId to it.anzahl }

    /** Offene Fälle, deren Wiedervorlage heute oder früher liegt – älteste zuerst. */
    suspend fun faelligeFaelle(heute: LocalDate = LocalDate.now(uhr)): List<Schadensfall> =
        dao.ladeAlle()
            .filter { it.faelligkeit(heute).istFaellig }
            .sortedBy { it.wiedervorlageAm }

    /** Legt einen neuen Fall an und vergibt die nächste freie Fallnummer des Jahres. */
    suspend fun anlegen(entwurf: Schadensfall): Long = db.withTransaction {
        val jetzt = uhr.instant()
        val jahr = LocalDate.now(uhr).year
        val nummer = Fallnummer.naechste(jahr, dao.fallnummernMitPraefix(Fallnummer.praefix(jahr)))
        val id = dao.einfuegen(
            entwurf.copy(id = 0, uuid = neueKennung(), fallnummer = nummer, erstelltAm = jetzt, geaendertAm = jetzt).bereinigt(),
        )
        dao.einfuegen(VerlaufEintrag(fallId = id, zeitpunkt = jetzt, typ = VerlaufTyp.ANGELEGT, text = "Fall $nummer angelegt"))
        merkeAuftraggeber(entwurf)
        id
    }.also { nachAenderung() }

    /**
     * Speichert die bearbeiteten Stammdaten. Fallnummer und Anlagedatum bleiben unverändert;
     * Status- und Terminänderungen werden im Verlauf vermerkt.
     */
    suspend fun speichern(geaendert: Schadensfall) = aenderung {
        val alt = dao.lade(geaendert.id) ?: return@aenderung
        val jetzt = uhr.instant()
        val neu = geaendert.copy(uuid = alt.uuid, fallnummer = alt.fallnummer, erstelltAm = alt.erstelltAm, geaendertAm = jetzt).bereinigt()
        dao.aktualisieren(neu)
        merkeAuftraggeber(neu)
        if (alt.status != neu.status) {
            vermerke(neu.id, VerlaufTyp.STATUS, "Status: ${alt.status.label} → ${neu.status.label}")
        }
        if (neu.status.offen && alt.wiedervorlageAm != neu.wiedervorlageAm) {
            vermerke(neu.id, VerlaufTyp.WIEDERVORLAGE, wiedervorlageText(neu.wiedervorlageAm, neu.wartetAuf, neu.wiedervorlageNotiz))
        }
    }

    suspend fun statusAendern(fallId: Long, neuerStatus: FallStatus, notiz: String = "") = aenderung {
        val alt = dao.lade(fallId) ?: return@aenderung
        if (alt.status == neuerStatus && notiz.isBlank()) return@aenderung
        dao.aktualisieren(alt.copy(status = neuerStatus, geaendertAm = uhr.instant()).bereinigt())
        val text = buildString {
            append("Status: ${alt.status.label} → ${neuerStatus.label}")
            if (notiz.isNotBlank()) append("\n${notiz.trim()}")
        }
        vermerke(fallId, VerlaufTyp.STATUS, text)
    }

    /**
     * Hält fest, dass nachgehakt wurde, und plant gleich den nächsten Termin.
     *
     * @param kanal wie nachgehakt wurde (Telefonat, E-Mail, …)
     * @param ergebnis was dabei herauskam
     * @param naechsteWiedervorlage `null`, wenn vorerst kein weiterer Termin nötig ist
     */
    suspend fun nachhakenErfassen(
        fallId: Long,
        kanal: VerlaufTyp,
        ergebnis: String,
        naechsteWiedervorlage: LocalDate?,
        wartetAuf: Ansprechpartner?,
        naechsterSchritt: String,
    ) = aenderung {
        val alt = dao.lade(fallId) ?: return@aenderung
        dao.aktualisieren(
            alt.copy(
                wiedervorlageAm = naechsteWiedervorlage,
                wartetAuf = wartetAuf,
                wiedervorlageNotiz = naechsterSchritt.trim(),
                geaendertAm = uhr.instant(),
            ).bereinigt(),
        )
        val text = buildString {
            append(ergebnis.trim().ifBlank { "Nachgehakt" })
            append("\n")
            append(wiedervorlageText(naechsteWiedervorlage, wartetAuf, naechsterSchritt))
        }
        vermerke(fallId, kanal, text)
    }

    suspend fun wiedervorlageSetzen(
        fallId: Long,
        datum: LocalDate?,
        wartetAuf: Ansprechpartner?,
        naechsterSchritt: String,
    ) = aenderung {
        val alt = dao.lade(fallId) ?: return@aenderung
        val neu = alt.copy(
            wiedervorlageAm = datum,
            wartetAuf = wartetAuf,
            wiedervorlageNotiz = naechsterSchritt.trim(),
            geaendertAm = uhr.instant(),
        ).bereinigt()
        if (neu.copy(geaendertAm = alt.geaendertAm, syncOffen = alt.syncOffen) == alt) return@aenderung
        dao.aktualisieren(neu)
        vermerke(fallId, VerlaufTyp.WIEDERVORLAGE, wiedervorlageText(neu.wiedervorlageAm, neu.wartetAuf, neu.wiedervorlageNotiz))
    }

    suspend fun verlaufHinzufuegen(fallId: Long, typ: VerlaufTyp, text: String) = aenderung {
        if (text.isBlank()) return@aenderung
        val fall = dao.lade(fallId) ?: return@aenderung
        vermerke(fallId, typ, text.trim())
        dao.aktualisieren(fall.copy(geaendertAm = uhr.instant(), syncOffen = true))
    }

    suspend fun verlaufLoeschen(eintragId: Long) = aenderung {
        val eintrag = dao.ladeVerlaufEintrag(eintragId) ?: return@aenderung
        val fall = dao.lade(eintrag.fallId) ?: return@aenderung
        dao.loescheVerlaufEintrag(eintragId)
        dao.merkeLoeschung(Loeschung(eintrag.uuid, Loeschung.Art.VERLAUF, fall.uuid, uhr.instant()))
    }

    /**
     * Kopiert die Datei hinter [quelle] in den App-Speicher und hängt sie an den Fall.
     * Schlägt das Kopieren fehl, bleibt die Datenbank unverändert.
     */
    suspend fun anlageHinzufuegen(fallId: Long, quelle: Uri, anzeigename: String? = null): Anlage {
        val datei = anlagenSpeicher.importiere(fallId, quelle, anzeigename)
        val anlage = Anlage(
            fallId = fallId,
            dateiname = datei.dateiname,
            anzeigename = datei.anzeigename,
            mimeType = datei.mimeType,
            groesseBytes = datei.groesseBytes,
            hinzugefuegtAm = uhr.instant(),
        )
        return try {
            db.withTransaction {
                val id = dao.einfuegen(anlage)
                vermerke(fallId, VerlaufTyp.ANLAGE, "Anlage hinzugefügt: ${datei.anzeigename}")
                anlage.copy(id = id)
            }.also { nachAenderung() }
        } catch (e: Exception) {
            // Fall existiert nicht (mehr) – die kopierte Datei nicht verwaist liegen lassen.
            anlagenSpeicher.loesche(anlage)
            throw e
        }
    }

    suspend fun anlageLoeschen(anlage: Anlage) {
        db.withTransaction {
            val fall = dao.lade(anlage.fallId)
            dao.loesche(anlage)
            if (fall != null) dao.merkeLoeschung(Loeschung(anlage.uuid, Loeschung.Art.ANLAGE, fall.uuid, uhr.instant()))
            vermerke(anlage.fallId, VerlaufTyp.ANLAGE, "Anlage entfernt: ${anlage.anzeigename}")
        }
        anlagenSpeicher.loesche(anlage)
        nachAenderung()
    }

    /** Löscht den Fall samt Verlauf, Anlagen-Einträgen und Dateien. */
    suspend fun fallLoeschen(fallId: Long) {
        db.withTransaction {
            val fall = dao.lade(fallId) ?: return@withTransaction
            dao.loescheFall(fallId)
            // Verlauf und Anlagen löscht die Cloud zusammen mit dem Fall.
            dao.merkeLoeschung(Loeschung(fall.uuid, Loeschung.Art.FALL, fall.uuid, uhr.instant()))
        }
        anlagenSpeicher.loescheOrdner(fallId)
        nachAenderung()
    }

    // --- Auftraggeber-Liste ---

    fun beobachteAuftraggeber(): Flow<List<AuftraggeberEintrag>> = dao.beobachteAuftraggeber()

    /** Legt einen Auftraggeber an oder ändert ihn. Bereits erfasste Fälle bleiben unverändert. */
    suspend fun auftraggeberSpeichern(eintrag: AuftraggeberEintrag) = aenderung {
        dao.speichereAuftraggeber(
            eintrag.copy(
                name = eintrag.name.trim(),
                kontakt = eintrag.kontakt.trim(),
                telefon = eintrag.telefon.trim(),
                email = eintrag.email.trim(),
                geaendertAm = uhr.instant(),
                syncOffen = true,
            ),
        )
    }

    suspend fun auftraggeberLoeschen(eintrag: AuftraggeberEintrag) = aenderung {
        dao.loescheAuftraggeber(eintrag.uuid)
        dao.merkeLoeschung(Loeschung(eintrag.uuid, Loeschung.Art.AUFTRAGGEBER, "", uhr.instant()))
    }

    /** Ein bisher unbekannter Auftraggeber aus einem Fall kommt automatisch in die Liste. */
    private suspend fun merkeAuftraggeber(fall: Schadensfall) {
        val name = fall.auftraggeber.trim()
        if (name.isEmpty() || dao.auftraggeberPerName(name) != null) return
        dao.speichereAuftraggeber(
            AuftraggeberEintrag(
                name = name,
                kontakt = fall.auftraggeberKontakt.trim(),
                telefon = fall.auftraggeberTelefon.trim(),
                email = fall.auftraggeberEmail.trim(),
                geaendertAm = uhr.instant(),
            ),
        )
    }

    /** Führt eine Änderung in einer Transaktion aus und stößt danach den Abgleich an. */
    private suspend fun <T> aenderung(block: suspend () -> T): T = db.withTransaction { block() }.also { nachAenderung() }

    private suspend fun vermerke(fallId: Long, typ: VerlaufTyp, text: String) {
        dao.einfuegen(VerlaufEintrag(fallId = fallId, zeitpunkt = uhr.instant(), typ = typ, text = text))
    }

    private fun wiedervorlageText(datum: LocalDate?, wartetAuf: Ansprechpartner?, naechsterSchritt: String): String =
        if (datum == null) {
            "Keine Wiedervorlage geplant"
        } else {
            buildString {
                append("Wiedervorlage am ${Datum.format(datum)}")
                if (wartetAuf != null) append(" · wartet auf ${wartetAuf.label}")
                if (naechsterSchritt.isNotBlank()) append(" · ${naechsterSchritt.trim()}")
            }
        }

    /** Ein abgeschlossener Fall braucht keine Wiedervorlage mehr. */
    private fun Schadensfall.bereinigt(): Schadensfall =
        (if (status.offen) this else copy(wiedervorlageAm = null, wartetAuf = null, wiedervorlageNotiz = "")).copy(syncOffen = true)
}
