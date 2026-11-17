@file:UseSerializers(LocalDateSerializer::class, InstantSerializer::class)

package de.aysetransport.schaden.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.time.LocalDate

/*
 * CRM für die Auftraggeber. Alle Einträge hängen über `auftraggeberUuid` an einer Firma (Tabelle
 * `auftraggeber`). Fremdschlüssel gibt es bewusst nicht: Beim Abgleich mit der Cloud können Einträge
 * vor ihrer Firma ankommen. Gelöscht wird gemeinsam im Repository.
 *
 * Die Namen der Aufzählungs-Konstanten stehen in Datenbank, Backup und Cloud und dürfen nicht
 * umbenannt werden.
 */

enum class FirmenStatus(val label: String) {
    INTERESSENT("Interessent"),
    AKTIV("Aktiv"),
    PAUSIERT("Pausiert"),
    EHEMALIG("Ehemalig"),
}

enum class AdressArt(val label: String) {
    HAUPTSITZ("Hauptsitz"),
    LAGER("Lager"),
    FILIALE("Filiale"),
    RECHNUNG("Rechnungsadresse"),
    SONSTIGE("Sonstige"),
}

enum class AktivitaetArt(val label: String) {
    ANRUF("Anruf"),
    EMAIL("E-Mail"),
    TREFFEN("Treffen"),
    ANGEBOT("Angebot"),
    SONSTIGES("Sonstiges"),
}

/** Gemeinsame Felder aller CRM-Einträge – für Abgleich, Backup und Löschen. */
interface CrmEintrag {
    val uuid: String
    val auftraggeberUuid: String
    val geaendertAm: Instant
    val syncOffen: Boolean
}

/** Ein Auftraggeber (Firma). Die Tabelle heißt aus Kompatibilitätsgründen weiter `auftraggeber`. */
@Serializable
@Entity(tableName = "auftraggeber", indices = [Index("name"), Index("syncOffen")])
data class AuftraggeberEintrag(
    @PrimaryKey val uuid: String = neueKennung(),
    val name: String,
    @ColumnInfo(defaultValue = "''") val rechtsform: String = "",
    @ColumnInfo(defaultValue = "'AKTIV'") val status: FirmenStatus = FirmenStatus.AKTIV,
    val telefon: String = "",
    val email: String = "",
    @ColumnInfo(defaultValue = "''") val website: String = "",
    @ColumnInfo(defaultValue = "''") val notizen: String = "",
    /** Schlagworte, durch Komma getrennt (z. B. „Möbel, Montage“). */
    @ColumnInfo(defaultValue = "''") val schlagworte: String = "",
    val geaendertAm: Instant = Instant.EPOCH,
    val syncOffen: Boolean = true,
) {
    val schlagwortListe: List<String> get() = schlagworte.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

    val anzeigeName: String get() = listOf(name, rechtsform).filter { it.isNotBlank() }.joinToString(" ")
}

@Serializable
@Entity(tableName = "adresse", indices = [Index("auftraggeberUuid"), Index("syncOffen")])
data class Adresse(
    @PrimaryKey override val uuid: String = neueKennung(),
    override val auftraggeberUuid: String,
    val art: AdressArt = AdressArt.HAUPTSITZ,
    val strasse: String = "",
    val plz: String = "",
    val ort: String = "",
    val notiz: String = "",
    override val geaendertAm: Instant = Instant.EPOCH,
    override val syncOffen: Boolean = true,
) : CrmEintrag {
    val einzeilig: String get() = listOf(strasse, listOf(plz, ort).filter { it.isNotBlank() }.joinToString(" ")).filter { it.isNotBlank() }.joinToString(", ")
}

/** Ansprechpartner einer Firma (nicht zu verwechseln mit [Ansprechpartner] = „wartet auf“ im Fall). */
@Serializable
@Entity(tableName = "ansprechpartner", indices = [Index("auftraggeberUuid"), Index("syncOffen")])
data class Kontaktperson(
    @PrimaryKey override val uuid: String = neueKennung(),
    override val auftraggeberUuid: String,
    val name: String,
    /** Zuständigkeit, z. B. Disposition, Abrechnung, Schäden, Geschäftsführung. */
    val thema: String = "",
    val telefon: String = "",
    val email: String = "",
    val notiz: String = "",
    override val geaendertAm: Instant = Instant.EPOCH,
    override val syncOffen: Boolean = true,
) : CrmEintrag

@Serializable
@Entity(tableName = "preis", indices = [Index("auftraggeberUuid"), Index("syncOffen")])
data class Preis(
    @PrimaryKey override val uuid: String = neueKennung(),
    override val auftraggeberUuid: String,
    /** z. B. Tourpauschale, Montage, Extra-km, Etage, Maut. */
    val bezeichnung: String,
    val betragCent: Long? = null,
    /** z. B. „pro Tour“, „pro km“, „pro Stunde“. */
    val einheit: String = "",
    val gueltigAb: LocalDate? = null,
    val notiz: String = "",
    override val geaendertAm: Instant = Instant.EPOCH,
    override val syncOffen: Boolean = true,
) : CrmEintrag

/** Ein Eintrag der Kontakthistorie. Mails an eine Firma (ohne Fall) landen hier mit [art] E-Mail. */
@Serializable
@Entity(tableName = "aktivitaet", indices = [Index("auftraggeberUuid"), Index("syncOffen")])
data class Aktivitaet(
    @PrimaryKey override val uuid: String = neueKennung(),
    override val auftraggeberUuid: String,
    val datum: LocalDate,
    val art: AktivitaetArt = AktivitaetArt.SONSTIGES,
    val text: String = "",
    /** Name des Ansprechpartners. */
    val kontakt: String = "",
    override val geaendertAm: Instant = Instant.EPOCH,
    override val syncOffen: Boolean = true,
) : CrmEintrag

/** Wiedervorlage bzw. Aufgabe zu einer Firma. */
@Serializable
@Entity(tableName = "aufgabe", indices = [Index("auftraggeberUuid"), Index("syncOffen"), Index("faelligAm")])
data class Aufgabe(
    @PrimaryKey override val uuid: String = neueKennung(),
    override val auftraggeberUuid: String,
    val faelligAm: LocalDate? = null,
    val text: String,
    val kontakt: String = "",
    val erledigt: Boolean = false,
    override val geaendertAm: Instant = Instant.EPOCH,
    override val syncOffen: Boolean = true,
) : CrmEintrag

/** Verweis auf ein Dokument (Link in Google Drive, Pfad auf dem Server …). */
@Serializable
@Entity(tableName = "dokument", indices = [Index("auftraggeberUuid"), Index("syncOffen")])
data class Dokument(
    @PrimaryKey override val uuid: String = neueKennung(),
    override val auftraggeberUuid: String,
    val titel: String,
    val link: String = "",
    val notiz: String = "",
    override val geaendertAm: Instant = Instant.EPOCH,
    override val syncOffen: Boolean = true,
) : CrmEintrag

enum class ChancenPhase(val label: String, val offen: Boolean) {
    ANFRAGE("Anfrage", true),
    ANGEBOT("Angebot", true),
    VERHANDLUNG("Verhandlung", true),
    GEWONNEN("Gewonnen", false),
    VERLOREN("Verloren", false),
}

/** Verkaufschance: neues Geschäft mit einer Firma (z. B. zusätzliche Touren, neue Filiale). */
@Serializable
@Entity(tableName = "chance", indices = [Index("auftraggeberUuid"), Index("syncOffen")])
data class Chance(
    @PrimaryKey override val uuid: String = neueKennung(),
    override val auftraggeberUuid: String,
    val titel: String,
    /** Geschätzter Wert (z. B. Umsatz pro Jahr) in Cent. */
    val wertCent: Long? = null,
    val phase: ChancenPhase = ChancenPhase.ANFRAGE,
    /** Bis wann mit einer Entscheidung zu rechnen ist. */
    val erwartetAm: LocalDate? = null,
    val notiz: String = "",
    override val geaendertAm: Instant = Instant.EPOCH,
    override val syncOffen: Boolean = true,
) : CrmEintrag

/** Über welches Postfach eine Mail lief. */
enum class MailKontoArt(val label: String) {
    /** Postfach für Schadensfälle. */
    SCHADEN("Schäden"),

    /** Postfach für alles andere (Disposition, Angebote, Abrechnung …). */
    ALLGEMEIN("Allgemein"),
}
