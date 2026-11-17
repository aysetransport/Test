package de.aysetransport.schaden.domain

import de.aysetransport.schaden.data.model.Adresse
import de.aysetransport.schaden.data.model.Aktivitaet
import de.aysetransport.schaden.data.model.Aufgabe
import de.aysetransport.schaden.data.model.AuftraggeberEintrag
import de.aysetransport.schaden.data.model.Dokument
import de.aysetransport.schaden.data.model.FirmenStatus
import de.aysetransport.schaden.data.model.Kontaktperson
import de.aysetransport.schaden.data.model.Preis
import de.aysetransport.schaden.data.model.Schadensfall
import java.time.LocalDate

/** Alles, was die Firmenliste und die Suche brauchen – einmal geladen. */
data class CrmBestand(
    val firmen: List<AuftraggeberEintrag> = emptyList(),
    val kontakte: List<Kontaktperson> = emptyList(),
    val adressen: List<Adresse> = emptyList(),
    val aktivitaeten: List<Aktivitaet> = emptyList(),
    val dokumente: List<Dokument> = emptyList(),
    val offeneAufgaben: List<Aufgabe> = emptyList(),
    val faelle: List<Schadensfall> = emptyList(),
)

/** Ein Termin in der Wiedervorlage: entweder eine CRM-Aufgabe oder die Wiedervorlage eines Falls. */
sealed interface Termin {
    val datum: LocalDate
    val titel: String
    val zusatz: String

    data class CrmAufgabe(val aufgabe: Aufgabe, val firma: AuftraggeberEintrag?) : Termin {
        override val datum: LocalDate get() = aufgabe.faelligAm!!
        override val titel: String get() = aufgabe.text
        override val zusatz: String get() = listOfNotNull(firma?.name, aufgabe.kontakt.takeIf { it.isNotBlank() }).joinToString(" · ")
    }

    data class Fall(val fall: Schadensfall) : Termin {
        override val datum: LocalDate get() = fall.wiedervorlageAm!!
        override val titel: String get() = "Fall ${fall.fallnummer} · ${fall.anzeigeName}"
        override val zusatz: String
            get() = listOfNotNull(
                fall.auftraggeber.takeIf { it.isNotBlank() },
                fall.wartetAuf?.let { "wartet auf ${it.label}" },
                fall.wiedervorlageNotiz.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
    }
}

data class Wiedervorlagen(
    val ueberfaellig: List<Termin> = emptyList(),
    val heute: List<Termin> = emptyList(),
    val demnaechst: List<Termin> = emptyList(),
    /** Offene Aufgaben ohne Datum. */
    val ohneDatum: List<Aufgabe> = emptyList(),
) {
    val anzahlFaellig: Int get() = ueberfaellig.size + heute.size
}

/** Eine Zeile der Firmenliste. */
data class FirmenZeile(
    val firma: AuftraggeberEintrag,
    val naechsterTermin: Termin?,
    val offeneFaelle: Int,
    /** Bei einer Suche: wo der Treffer lag, z. B. „Ansprechpartner: Jana Krüger“. */
    val treffer: String? = null,
)

object Crm {

    /** Sammelt CRM-Aufgaben und Fall-Wiedervorlagen und teilt sie nach Fälligkeit ein. */
    fun wiedervorlagen(bestand: CrmBestand, heute: LocalDate): Wiedervorlagen {
        val firmen = bestand.firmen.associateBy { it.uuid }
        val termine = bestand.offeneAufgaben.filter { !it.erledigt && it.faelligAm != null }.map { Termin.CrmAufgabe(it, firmen[it.auftraggeberUuid]) } +
            bestand.faelle.filter { it.status.offen && it.wiedervorlageAm != null }.map { Termin.Fall(it) }
        val sortiert = termine.sortedWith(compareBy<Termin> { it.datum }.thenBy { it.titel })
        return Wiedervorlagen(
            ueberfaellig = sortiert.filter { it.datum.isBefore(heute) },
            heute = sortiert.filter { it.datum == heute },
            demnaechst = sortiert.filter { it.datum.isAfter(heute) },
            ohneDatum = bestand.offeneAufgaben.filter { !it.erledigt && it.faelligAm == null },
        )
    }

    /** Gehört der Fall zu dieser Firma (über die Kennung oder – noch unverknüpft – über den Namen)? */
    fun gehoertZu(fall: Schadensfall, firma: AuftraggeberEintrag): Boolean =
        if (!fall.auftraggeberUuid.isNullOrBlank()) fall.auftraggeberUuid == firma.uuid
        else fall.auftraggeber.isNotBlank() && normal(fall.auftraggeber) == normal(firma.name)

    /**
     * Firmenliste mit Statusfilter und Volltextsuche über Firmen, Ansprechpartner, Adressen, Notizen,
     * Aktivitäten, Dokumente und die Fälle der Firma. Ohne Suche alphabetisch.
     */
    fun firmenliste(bestand: CrmBestand, status: FirmenStatus?, suchtext: String, heute: LocalDate): List<FirmenZeile> {
        val such = suchtext.trim()
        val termineJeFirma = wiedervorlagen(bestand, heute).let { it.ueberfaellig + it.heute + it.demnaechst }
        return bestand.firmen
            .filter { status == null || it.status == status }
            .mapNotNull { firma ->
                val treffer = if (such.isEmpty()) null else treffer(bestand, firma, such) ?: return@mapNotNull null
                val faelle = bestand.faelle.filter { gehoertZu(it, firma) }
                val naechster = termineJeFirma.firstOrNull {
                    when (it) {
                        is Termin.CrmAufgabe -> it.aufgabe.auftraggeberUuid == firma.uuid
                        is Termin.Fall -> gehoertZu(it.fall, firma)
                    }
                }
                FirmenZeile(firma, naechster, faelle.count { it.status.offen }, treffer?.takeIf { it.isNotEmpty() })
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.firma.name })
    }

    /** `null` = kein Treffer, „“ = Treffer im Namen, sonst die Fundstelle. */
    private fun treffer(b: CrmBestand, firma: AuftraggeberEintrag, such: String): String? {
        fun String.passt() = contains(such, ignoreCase = true)
        if (firma.name.passt() || firma.rechtsform.passt()) return ""
        if (firma.notizen.passt()) return "Notizen"
        if (firma.email.passt() || firma.telefon.passt() || firma.website.passt()) return "Kontaktdaten"
        b.kontakte.firstOrNull { it.auftraggeberUuid == firma.uuid && listOf(it.name, it.thema, it.telefon, it.email, it.notiz).any { t -> t.passt() } }
            ?.let { return "Ansprechpartner: ${it.name}" }
        b.adressen.firstOrNull { it.auftraggeberUuid == firma.uuid && listOf(it.strasse, it.plz, it.ort, it.notiz).any { t -> t.passt() } }
            ?.let { return "Adresse: ${it.einzeilig}" }
        b.aktivitaeten.firstOrNull { it.auftraggeberUuid == firma.uuid && (it.text.passt() || it.kontakt.passt()) }
            ?.let { return "Kontakthistorie: ${Datum.format(it.datum)}" }
        b.offeneAufgaben.firstOrNull { it.auftraggeberUuid == firma.uuid && it.text.passt() }?.let { return "Wiedervorlage: ${it.text}" }
        b.dokumente.firstOrNull { it.auftraggeberUuid == firma.uuid && (it.titel.passt() || it.notiz.passt()) }?.let { return "Dokument: ${it.titel}" }
        b.faelle.firstOrNull { gehoertZu(it, firma) && Fallliste.passtZuSuche(it, such) }?.let { return "Fall ${it.fallnummer}" }
        return null
    }

    fun normal(text: String): String = text.trim().lowercase().replace(Regex("\\s+"), " ")
}
