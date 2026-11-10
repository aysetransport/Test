@file:UseSerializers(LocalDateSerializer::class, InstantSerializer::class)

package de.aysetransport.schaden.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Ein Schadensfall zu einem Transportauftrag.
 *
 * Geldbeträge werden in Cent gespeichert, damit beim Rechnen keine Rundungsfehler entstehen.
 */
@Serializable
@Entity(
    tableName = "schadensfall",
    indices = [Index("status"), Index("wiedervorlageAm"), Index(value = ["uuid"], unique = true), Index("syncOffen")],
)
data class Schadensfall(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Geräteübergreifende Kennung für den Abgleich mit der Cloud. */
    @ColumnInfo(defaultValue = "''") val uuid: String = neueKennung(),
    /** `true`, solange die letzte Änderung noch nicht in der Cloud angekommen ist. */
    @ColumnInfo(defaultValue = "1") val syncOffen: Boolean = true,
    /** Laufende interne Nummer, z. B. `2026-003`. Wird beim Anlegen vergeben. */
    val fallnummer: String = "",

    // Geschädigter (die Felder heißen aus Kompatibilitätsgründen weiter „kunde…“)
    val kundeName: String = "",
    val kundeFirma: String = "",
    val kundeTelefon: String = "",
    val kundeEmail: String = "",
    val kundeAnschrift: String = "",
    /** Aktenzeichen bzw. Bezug der Gegenseite. */
    val kundenAktenzeichen: String = "",

    // Auftraggeber
    val auftraggeber: String = "",
    @ColumnInfo(defaultValue = "''") val auftraggeberKontakt: String = "",
    @ColumnInfo(defaultValue = "''") val auftraggeberTelefon: String = "",
    @ColumnInfo(defaultValue = "''") val auftraggeberEmail: String = "",

    // Transportauftrag
    val auftragsReferenz: String = "",
    val tour: String = "",
    val fahrzeug: String = "",
    val fahrer: String = "",
    @ColumnInfo(defaultValue = "''") val beifahrer1: String = "",
    @ColumnInfo(defaultValue = "''") val beifahrer2: String = "",
    val schadensort: String = "",

    // Schaden
    val schadensdatum: LocalDate? = null,
    val meldedatum: LocalDate? = null,
    val schadensart: Schadensart = Schadensart.WARE,
    val beschreibung: String = "",
    val forderungCent: Long? = null,

    // Bearbeitung
    val status: FallStatus = FallStatus.NEU,
    val wartetAuf: Ansprechpartner? = null,
    val wiedervorlageAm: LocalDate? = null,
    val wiedervorlageNotiz: String = "",
    /** Vereinbarter bzw. gezahlter Betrag (Vergleich / Regulierung). */
    val vergleichCent: Long? = null,
    val zahlungsfrist: LocalDate? = null,
    val versicherungsNummer: String = "",

    val erstelltAm: Instant = Instant.EPOCH,
    val geaendertAm: Instant = Instant.EPOCH,
) {
    /** Anzeigename: Person, sonst Firma, sonst die Fallnummer. */
    val anzeigeName: String
        get() = when {
            kundeName.isNotBlank() && kundeFirma.isNotBlank() -> "$kundeName · $kundeFirma"
            kundeName.isNotBlank() -> kundeName
            kundeFirma.isNotBlank() -> kundeFirma
            else -> "Fall $fallnummer"
        }
}

/** Ein Eintrag in der Fallhistorie (Notiz, Telefonat, Statusänderung …). */
@Serializable
@Entity(
    tableName = "verlauf",
    foreignKeys = [
        ForeignKey(
            entity = Schadensfall::class,
            parentColumns = ["id"],
            childColumns = ["fallId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("fallId"), Index(value = ["uuid"], unique = true), Index("syncOffen")],
)
data class VerlaufEintrag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "''") val uuid: String = neueKennung(),
    @ColumnInfo(defaultValue = "1") val syncOffen: Boolean = true,
    val fallId: Long,
    val zeitpunkt: Instant,
    val typ: VerlaufTyp,
    val text: String,
)

/** Foto, Beleg oder sonstige Datei zu einem Fall. Die Datei selbst liegt im App-Speicher. */
@Serializable
@Entity(
    tableName = "anlage",
    foreignKeys = [
        ForeignKey(
            entity = Schadensfall::class,
            parentColumns = ["id"],
            childColumns = ["fallId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("fallId"), Index(value = ["uuid"], unique = true), Index("syncOffen")],
)
data class Anlage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "''") val uuid: String = neueKennung(),
    @ColumnInfo(defaultValue = "1") val syncOffen: Boolean = true,
    /** Die Datei selbst liegt in der Cloud (Eintrag und Inhalt sind dort vollständig). */
    @ColumnInfo(defaultValue = "0") val inCloud: Boolean = false,
    val fallId: Long,
    /**
     * Dateiname innerhalb des Fallordners (zufällig vergeben, ohne Pfadanteile). Leer, solange
     * eine auf einem anderen Gerät hinzugefügte Anlage noch nicht heruntergeladen ist.
     */
    val dateiname: String,
    /** Ursprünglicher Name, wie er dem Nutzer angezeigt wird. */
    val anzeigename: String,
    val mimeType: String,
    val groesseBytes: Long,
    val hinzugefuegtAm: Instant,
) {
    val istBild: Boolean get() = mimeType.startsWith("image/")
    val istLokalVorhanden: Boolean get() = dateiname.isNotEmpty()
}

/** Ein Auftraggeber aus der Stammdatenliste – zum Auswählen im Formular. */
@Serializable
@Entity(tableName = "auftraggeber", indices = [Index("name"), Index("syncOffen")])
data class AuftraggeberEintrag(
    @PrimaryKey val uuid: String = neueKennung(),
    val name: String,
    val kontakt: String = "",
    val telefon: String = "",
    val email: String = "",
    val geaendertAm: Instant = Instant.EPOCH,
    val syncOffen: Boolean = true,
)

/**
 * Eine gesendete oder empfangene E-Mail zu einem Fall. Bleibt auf dem Gerät (für „Antworten“ und
 * damit eine Mail nicht doppelt übernommen wird); lesbar für alle Geräte ist sie über den Verlauf.
 */
@Entity(
    tableName = "mail",
    foreignKeys = [ForeignKey(entity = Schadensfall::class, parentColumns = ["id"], childColumns = ["fallId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("fallId"), Index(value = ["messageId"], unique = true)],
)
data class MailNachricht(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fallId: Long,
    val eingehend: Boolean,
    /** Message-ID der Mail, ohne spitze Klammern. */
    val messageId: String,
    val antwortAuf: String = "",
    val von: String,
    val an: String,
    val betreff: String,
    val text: String,
    val zeitpunkt: Instant,
    val verlaufUuid: String = "",
)

/** Was gelöscht wurde, merkt sich die App, bis die Löschung in der Cloud angekommen ist. */
@Entity(tableName = "loeschung")
data class Loeschung(
    @PrimaryKey val uuid: String,
    val art: Art,
    val fallUuid: String,
    val zeitpunkt: Instant,
) {
    enum class Art { FALL, VERLAUF, ANLAGE, AUFTRAGGEBER }
}

fun neueKennung(): String = UUID.randomUUID().toString()
