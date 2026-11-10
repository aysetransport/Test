package de.aysetransport.schaden.data

import android.net.Uri
import androidx.room.withTransaction
import de.aysetransport.schaden.data.db.SchadensfallDatenbank
import de.aysetransport.schaden.data.model.Anlage
import de.aysetransport.schaden.data.model.Ansprechpartner
import de.aysetransport.schaden.data.model.FallStatus
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.data.model.VerlaufEintrag
import de.aysetransport.schaden.data.model.VerlaufTyp
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
            entwurf.copy(id = 0, fallnummer = nummer, erstelltAm = jetzt, geaendertAm = jetzt).bereinigt(),
        )
        dao.einfuegen(VerlaufEintrag(fallId = id, zeitpunkt = jetzt, typ = VerlaufTyp.ANGELEGT, text = "Fall $nummer angelegt"))
        id
    }

    /**
     * Speichert die bearbeiteten Stammdaten. Fallnummer und Anlagedatum bleiben unverändert;
     * Status- und Terminänderungen werden im Verlauf vermerkt.
     */
    suspend fun speichern(geaendert: Schadensfall) = db.withTransaction {
        val alt = dao.lade(geaendert.id) ?: return@withTransaction
        val jetzt = uhr.instant()
        val neu = geaendert.copy(fallnummer = alt.fallnummer, erstelltAm = alt.erstelltAm, geaendertAm = jetzt).bereinigt()
        dao.aktualisieren(neu)
        if (alt.status != neu.status) {
            vermerke(neu.id, VerlaufTyp.STATUS, "Status: ${alt.status.label} → ${neu.status.label}")
        }
        if (neu.status.offen && alt.wiedervorlageAm != neu.wiedervorlageAm) {
            vermerke(neu.id, VerlaufTyp.WIEDERVORLAGE, wiedervorlageText(neu.wiedervorlageAm, neu.wartetAuf, neu.wiedervorlageNotiz))
        }
    }

    suspend fun statusAendern(fallId: Long, neuerStatus: FallStatus, notiz: String = "") = db.withTransaction {
        val alt = dao.lade(fallId) ?: return@withTransaction
        if (alt.status == neuerStatus && notiz.isBlank()) return@withTransaction
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
    ) = db.withTransaction {
        val alt = dao.lade(fallId) ?: return@withTransaction
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
    ) = db.withTransaction {
        val alt = dao.lade(fallId) ?: return@withTransaction
        val neu = alt.copy(
            wiedervorlageAm = datum,
            wartetAuf = wartetAuf,
            wiedervorlageNotiz = naechsterSchritt.trim(),
            geaendertAm = uhr.instant(),
        ).bereinigt()
        if (neu.copy(geaendertAm = alt.geaendertAm) == alt) return@withTransaction
        dao.aktualisieren(neu)
        vermerke(fallId, VerlaufTyp.WIEDERVORLAGE, wiedervorlageText(neu.wiedervorlageAm, neu.wartetAuf, neu.wiedervorlageNotiz))
    }

    suspend fun verlaufHinzufuegen(fallId: Long, typ: VerlaufTyp, text: String) = db.withTransaction {
        if (text.isBlank()) return@withTransaction
        val fall = dao.lade(fallId) ?: return@withTransaction
        vermerke(fallId, typ, text.trim())
        dao.aktualisieren(fall.copy(geaendertAm = uhr.instant()))
    }

    suspend fun verlaufLoeschen(eintragId: Long) = dao.loescheVerlaufEintrag(eintragId)

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
            }
        } catch (e: Exception) {
            // Fall existiert nicht (mehr) – die kopierte Datei nicht verwaist liegen lassen.
            anlagenSpeicher.loesche(anlage)
            throw e
        }
    }

    suspend fun anlageLoeschen(anlage: Anlage) {
        db.withTransaction {
            dao.loesche(anlage)
            vermerke(anlage.fallId, VerlaufTyp.ANLAGE, "Anlage entfernt: ${anlage.anzeigename}")
        }
        anlagenSpeicher.loesche(anlage)
    }

    /** Löscht den Fall samt Verlauf, Anlagen-Einträgen und Dateien. */
    suspend fun fallLoeschen(fallId: Long) {
        dao.loescheFall(fallId)
        anlagenSpeicher.loescheOrdner(fallId)
    }

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
        if (status.offen) this else copy(wiedervorlageAm = null, wartetAuf = null, wiedervorlageNotiz = "")
}
