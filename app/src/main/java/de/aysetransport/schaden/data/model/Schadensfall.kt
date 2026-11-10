@file:UseSerializers(LocalDateSerializer::class, InstantSerializer::class)

package de.aysetransport.schaden.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.time.LocalDate

/**
 * Ein Schadensfall zu einem Transportauftrag.
 *
 * Geldbeträge werden in Cent gespeichert, damit beim Rechnen keine Rundungsfehler entstehen.
 */
@Serializable
@Entity(
    tableName = "schadensfall",
    indices = [Index("status"), Index("wiedervorlageAm")],
)
data class Schadensfall(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Laufende interne Nummer, z. B. `2026-003`. Wird beim Anlegen vergeben. */
    val fallnummer: String = "",

    // Anspruchsteller
    val kundeName: String = "",
    val kundeFirma: String = "",
    val kundeTelefon: String = "",
    val kundeEmail: String = "",
    val kundeAnschrift: String = "",
    /** Aktenzeichen bzw. Bezug der Gegenseite. */
    val kundenAktenzeichen: String = "",

    // Transportauftrag
    val auftraggeber: String = "",
    val auftragsReferenz: String = "",
    val tour: String = "",
    val fahrzeug: String = "",
    val fahrer: String = "",
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
    indices = [Index("fallId")],
)
data class VerlaufEintrag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
    indices = [Index("fallId")],
)
data class Anlage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fallId: Long,
    /** Dateiname innerhalb des Fallordners (zufällig vergeben, ohne Pfadanteile). */
    val dateiname: String,
    /** Ursprünglicher Name, wie er dem Nutzer angezeigt wird. */
    val anzeigename: String,
    val mimeType: String,
    val groesseBytes: Long,
    val hinzugefuegtAm: Instant,
) {
    val istBild: Boolean get() = mimeType.startsWith("image/")
}
