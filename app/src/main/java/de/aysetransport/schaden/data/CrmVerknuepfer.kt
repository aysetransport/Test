package de.aysetransport.schaden.data

import de.aysetransport.schaden.data.db.SchadensfallDatenbank
import de.aysetransport.schaden.data.model.AuftraggeberEintrag
import de.aysetransport.schaden.data.model.FirmenStatus
import de.aysetransport.schaden.data.model.Kontaktperson
import de.aysetransport.schaden.data.model.Loeschung
import de.aysetransport.schaden.data.model.Schadensfall
import java.time.Clock

/**
 * Verbindet Schadensfälle mit den Firmen im CRM. Läuft immer innerhalb einer Transaktion des Aufrufers.
 *
 * - Ein Fall verweist über `auftraggeberUuid` auf seine Firma. Fehlt die Firma, wird sie angelegt.
 * - Ansprechpartner, Telefon und E-Mail des Auftraggebers aus dem Fall kommen als Ansprechpartner
 *   (Zuständigkeit „Schäden“) zur Firma, sofern es ihn dort noch nicht gibt.
 * - Zwei Firmen mit gleichem Namen werden zusammengeführt (z. B. wenn zwei Geräte dieselbe Firma
 *   angelegt haben). Es bleibt immer die mit der kleineren Kennung – so kommen alle Geräte zum selben Ergebnis.
 */
class CrmVerknuepfer(private val db: SchadensfallDatenbank, private val uhr: Clock) {
    private val dao = db.dao()
    private val crm = db.crm()

    /** Liefert den Fall mit Verweis auf seine Firma; legt Firma und Ansprechpartner bei Bedarf an. */
    suspend fun verknuepfe(fall: Schadensfall): Schadensfall {
        val name = fall.auftraggeber.trim()
        if (name.isEmpty()) return fall.copy(auftraggeberUuid = null)
        val firma = firmaFuer(name, fall.auftraggeberUuid)
        kontaktUebernehmen(firma.uuid, fall.auftraggeberKontakt, fall.auftraggeberTelefon, fall.auftraggeberEmail)
        return fall.copy(auftraggeberUuid = firma.uuid)
    }

    /**
     * Fälle ohne Verweis (aus einem Backup, aus der Cloud oder der Web-App) mit ihrer Firma verbinden.
     * @return Anzahl der verknüpften Fälle
     */
    suspend fun verknuepfeAlle(): Int {
        var n = 0
        for (fall in dao.faelleOhneFirma()) {
            val neu = verknuepfe(fall)
            if (neu.auftraggeberUuid == null) continue
            // Eine Millisekunde später: so gilt der Verweis beim Abgleich als neuerer Stand.
            dao.aktualisieren(neu.copy(syncOffen = true, geaendertAm = neu.geaendertAm.plusMillis(1).coerceAtLeastEpoch()))
            n++
        }
        return n
    }

    /** Firma zum Namen; [bevorzugt] gewinnt, wenn sie existiert und denselben Namen trägt. */
    suspend fun firmaFuer(name: String, bevorzugt: String? = null): AuftraggeberEintrag {
        bevorzugt?.let { uuid ->
            dao.auftraggeberPerKennung(uuid)?.takeIf { normal(it.name) == normal(name) }?.let { return it }
        }
        dao.ladeAlleAuftraggeber().filter { normal(it.name) == normal(name) }.minByOrNull { it.uuid }?.let { return it }
        val neu = AuftraggeberEintrag(name = name.trim(), status = FirmenStatus.AKTIV, geaendertAm = uhr.instant())
        dao.speichereAuftraggeber(neu)
        return neu
    }

    /** Legt einen Ansprechpartner an oder ergänzt fehlende Telefonnummer bzw. E-Mail eines vorhandenen. */
    suspend fun kontaktUebernehmen(firma: String, name: String, telefon: String, email: String, thema: String = "Schäden") {
        val n = name.trim()
        val t = telefon.trim()
        val e = email.trim()
        if (n.isEmpty() && t.isEmpty() && e.isEmpty()) return
        val vorhandene = crm.ladeKontaktpersonen(firma)
        val treffer = when {
            n.isNotEmpty() -> vorhandene.firstOrNull { normal(it.name) == normal(n) }
            e.isNotEmpty() -> vorhandene.firstOrNull { it.email.equals(e, ignoreCase = true) }
            else -> vorhandene.firstOrNull { it.telefon.filter(Char::isDigit) == t.filter(Char::isDigit) }
        }
        if (treffer == null) {
            crm.speichere(Kontaktperson(auftraggeberUuid = firma, name = n.ifEmpty { e.ifEmpty { t } }, thema = thema, telefon = t, email = e, geaendertAm = uhr.instant()))
        } else if ((treffer.telefon.isBlank() && t.isNotEmpty()) || (treffer.email.isBlank() && e.isNotEmpty())) {
            crm.speichere(
                treffer.copy(
                    telefon = treffer.telefon.ifBlank { t },
                    email = treffer.email.ifBlank { e },
                    geaendertAm = uhr.instant(),
                    syncOffen = true,
                ),
            )
        }
    }

    /**
     * Führt [weg] in [behalten] zusammen: alles, was an [weg] hängt, wandert zu [behalten], leere Felder
     * werden ergänzt, Notizen aneinandergehängt. [weg] wird gelöscht (auch in der Cloud).
     */
    suspend fun zusammenfuehren(behalten: AuftraggeberEintrag, weg: AuftraggeberEintrag) {
        if (behalten.uuid == weg.uuid) return
        val jetzt = uhr.instant()
        val ms = jetzt.toEpochMilli()
        crm.haengeAdressenUm(weg.uuid, behalten.uuid, ms)
        crm.haengeKontaktpersonenUm(weg.uuid, behalten.uuid, ms)
        crm.haengePreiseUm(weg.uuid, behalten.uuid, ms)
        crm.haengeAktivitaetenUm(weg.uuid, behalten.uuid, ms)
        crm.haengeAufgabenUm(weg.uuid, behalten.uuid, ms)
        crm.haengeDokumenteUm(weg.uuid, behalten.uuid, ms)
        crm.haengeMailsUm(weg.uuid, behalten.uuid)
        for (fall in dao.faelleDerFirma(weg.uuid)) {
            dao.aktualisieren(fall.copy(auftraggeberUuid = behalten.uuid, geaendertAm = jetzt, syncOffen = true))
        }
        doppelteKontakteEntfernen(behalten.uuid)
        val zusammen = behalten.copy(
            rechtsform = behalten.rechtsform.ifBlank { weg.rechtsform },
            telefon = behalten.telefon.ifBlank { weg.telefon },
            email = behalten.email.ifBlank { weg.email },
            website = behalten.website.ifBlank { weg.website },
            notizen = listOf(behalten.notizen.trim(), weg.notizen.trim()).filter { it.isNotEmpty() }.distinct().joinToString("\n\n"),
            geaendertAm = jetzt,
            syncOffen = true,
        )
        if (zusammen.copy(geaendertAm = behalten.geaendertAm, syncOffen = behalten.syncOffen) != behalten) dao.speichereAuftraggeber(zusammen)
        dao.loescheAuftraggeber(weg.uuid)
        dao.merkeLoeschung(Loeschung(weg.uuid, Loeschung.Art.AUFTRAGGEBER, "", jetzt))
    }

    /** Nach dem Zusammenführen: gleichnamige Ansprechpartner nur einmal behalten. */
    private suspend fun doppelteKontakteEntfernen(firma: String) {
        val jetzt = uhr.instant()
        crm.ladeKontaktpersonen(firma).groupBy { normal(it.name) }.values.filter { it.size > 1 }.forEach { gruppe ->
            val sortiert = gruppe.sortedBy { it.uuid }
            val erster = sortiert.first()
            var neu = erster
            for (k in sortiert.drop(1)) {
                neu = neu.copy(
                    thema = neu.thema.ifBlank { k.thema }, telefon = neu.telefon.ifBlank { k.telefon },
                    email = neu.email.ifBlank { k.email }, notiz = neu.notiz.ifBlank { k.notiz },
                )
                crm.loescheKontaktperson(k.uuid)
                dao.merkeLoeschung(Loeschung(k.uuid, Loeschung.Art.ANSPRECHPARTNER, firma, jetzt))
            }
            if (neu != erster) crm.speichere(neu.copy(geaendertAm = jetzt, syncOffen = true))
        }
    }

    /** Gleichnamige Firmen zusammenführen. @return Anzahl der entfernten Doppelungen */
    suspend fun doppelteFirmenZusammenfuehren(): Int {
        var n = 0
        dao.ladeAlleAuftraggeber().groupBy { normal(it.name) }.values.filter { it.size > 1 }.forEach { gruppe ->
            val sortiert = gruppe.sortedBy { it.uuid }
            for (weg in sortiert.drop(1)) {
                zusammenfuehren(dao.auftraggeberPerKennung(sortiert.first().uuid) ?: return@forEach, weg)
                n++
            }
        }
        return n
    }

    private fun java.time.Instant.coerceAtLeastEpoch() = if (isBefore(java.time.Instant.EPOCH)) java.time.Instant.EPOCH else this

    companion object {
        /** Vergleichsform eines Namens: ohne Groß-/Kleinschreibung und doppelte Leerzeichen. */
        fun normal(name: String): String = name.trim().lowercase().replace(Regex("\\s+"), " ")
    }
}
