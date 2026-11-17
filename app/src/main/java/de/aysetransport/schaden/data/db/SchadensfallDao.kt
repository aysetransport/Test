package de.aysetransport.schaden.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import de.aysetransport.schaden.data.model.Anlage
import de.aysetransport.schaden.data.model.AuftraggeberEintrag
import de.aysetransport.schaden.data.model.Loeschung
import de.aysetransport.schaden.data.model.MailNachricht
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.data.model.VerlaufEintrag
import kotlinx.coroutines.flow.Flow

data class AnlagenAnzahl(val fallId: Long, val anzahl: Int)

@Dao
interface SchadensfallDao {

    // --- Fälle ---

    @Query("SELECT * FROM schadensfall ORDER BY geaendertAm DESC")
    fun beobachteAlle(): Flow<List<Schadensfall>>

    @Query("SELECT * FROM schadensfall WHERE id = :id")
    fun beobachte(id: Long): Flow<Schadensfall?>

    @Query("SELECT * FROM schadensfall WHERE id = :id")
    suspend fun lade(id: Long): Schadensfall?

    @Query("SELECT * FROM schadensfall ORDER BY id")
    suspend fun ladeAlle(): List<Schadensfall>

    @Query("SELECT fallnummer FROM schadensfall WHERE fallnummer LIKE :praefix || '%'")
    suspend fun fallnummernMitPraefix(praefix: String): List<String>

    @Insert
    suspend fun einfuegen(fall: Schadensfall): Long

    @Insert
    suspend fun einfuegenFaelle(faelle: List<Schadensfall>)

    @Update
    suspend fun aktualisieren(fall: Schadensfall)

    @Query("DELETE FROM schadensfall WHERE id = :id")
    suspend fun loescheFall(id: Long)

    @Query("DELETE FROM schadensfall")
    suspend fun loescheAlleFaelle()

    // --- Verlauf ---

    @Query("SELECT * FROM verlauf WHERE fallId = :fallId ORDER BY zeitpunkt DESC, id DESC")
    fun beobachteVerlauf(fallId: Long): Flow<List<VerlaufEintrag>>

    @Query("SELECT * FROM verlauf ORDER BY id")
    suspend fun ladeGesamtenVerlauf(): List<VerlaufEintrag>

    @Insert
    suspend fun einfuegen(eintrag: VerlaufEintrag): Long

    @Insert
    suspend fun einfuegenVerlauf(eintraege: List<VerlaufEintrag>)

    @Query("DELETE FROM verlauf WHERE id = :id")
    suspend fun loescheVerlaufEintrag(id: Long)

    // --- Anlagen ---

    @Query("SELECT * FROM anlage WHERE fallId = :fallId ORDER BY hinzugefuegtAm DESC, id DESC")
    fun beobachteAnlagen(fallId: Long): Flow<List<Anlage>>

    @Query("SELECT fallId, COUNT(*) AS anzahl FROM anlage GROUP BY fallId")
    fun beobachteAnlagenAnzahl(): Flow<List<AnlagenAnzahl>>

    @Query("SELECT fallId, COUNT(*) AS anzahl FROM anlage GROUP BY fallId")
    suspend fun ladeAnlagenAnzahl(): List<AnlagenAnzahl>

    @Query("SELECT * FROM anlage ORDER BY id")
    suspend fun ladeAlleAnlagen(): List<Anlage>

    @Insert
    suspend fun einfuegen(anlage: Anlage): Long

    @Insert
    suspend fun einfuegenAnlagen(anlagen: List<Anlage>)

    @Delete
    suspend fun loesche(anlage: Anlage)

    @Query("SELECT * FROM verlauf WHERE id = :id")
    suspend fun ladeVerlaufEintrag(id: Long): VerlaufEintrag?

    // --- Abgleich mit der Cloud ---

    @Query("SELECT * FROM schadensfall WHERE uuid = :uuid")
    suspend fun fallPerKennung(uuid: String): Schadensfall?

    @Query("SELECT * FROM verlauf WHERE uuid = :uuid")
    suspend fun verlaufPerKennung(uuid: String): VerlaufEintrag?

    @Query("SELECT * FROM anlage WHERE uuid = :uuid")
    suspend fun anlagePerKennung(uuid: String): Anlage?

    @Query("SELECT * FROM schadensfall WHERE syncOffen = 1")
    suspend fun offeneFaelle(): List<Schadensfall>

    @Query("SELECT * FROM verlauf WHERE syncOffen = 1")
    suspend fun offenerVerlauf(): List<VerlaufEintrag>

    @Query("SELECT * FROM anlage WHERE syncOffen = 1")
    suspend fun offeneAnlagen(): List<Anlage>

    @Query("SELECT * FROM loeschung")
    suspend fun loeschungen(): List<Loeschung>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun merkeLoeschung(loeschung: Loeschung)

    @Query("DELETE FROM loeschung WHERE uuid IN (:uuids)")
    suspend fun vergissLoeschungen(uuids: List<String>)

    /** Nur wenn der Fall seit dem Absenden nicht erneut geändert wurde. */
    @Query("UPDATE schadensfall SET syncOffen = 0 WHERE uuid = :uuid AND geaendertAm = :geaendertAm")
    suspend fun fallIstSynchron(uuid: String, geaendertAm: Long)

    @Query("UPDATE verlauf SET syncOffen = 0 WHERE uuid IN (:uuids)")
    suspend fun verlaufIstSynchron(uuids: List<String>)

    @Query("UPDATE anlage SET syncOffen = 0 WHERE uuid IN (:uuids)")
    suspend fun anlagenSindSynchron(uuids: List<String>)

    @Query("SELECT * FROM anlage WHERE syncOffen = 0 AND inCloud = 0 AND dateiname != ''")
    suspend fun anlagenZumHochladen(): List<Anlage>

    @Query("SELECT * FROM anlage WHERE inCloud = 1 AND dateiname = ''")
    suspend fun anlagenZumHerunterladen(): List<Anlage>

    @Query("UPDATE anlage SET inCloud = 1 WHERE uuid = :uuid")
    suspend fun anlageIstInCloud(uuid: String)

    @Query("UPDATE anlage SET dateiname = :dateiname, groesseBytes = :groesse WHERE uuid = :uuid")
    suspend fun anlageHeruntergeladen(uuid: String, dateiname: String, groesse: Long)

    @Query("DELETE FROM verlauf WHERE uuid = :uuid")
    suspend fun loescheVerlaufPerKennung(uuid: String)

    @Query("DELETE FROM anlage WHERE uuid = :uuid")
    suspend fun loescheAnlagePerKennung(uuid: String)

    /** Der Bestand in der Cloud ist neu: alles von diesem Gerät erneut hochladen. */
    @Query("UPDATE schadensfall SET syncOffen = 1")
    suspend fun alleFaelleOffen()

    @Query("UPDATE verlauf SET syncOffen = 1")
    suspend fun allerVerlaufOffen()

    @Query("UPDATE anlage SET syncOffen = 1, inCloud = 0")
    suspend fun alleAnlagenOffen()

    @Query("SELECT (SELECT COUNT(*) FROM schadensfall WHERE syncOffen = 1) + (SELECT COUNT(*) FROM verlauf WHERE syncOffen = 1) + " +
        "(SELECT COUNT(*) FROM anlage WHERE syncOffen = 1 OR (inCloud = 0 AND dateiname != '')) + (SELECT COUNT(*) FROM loeschung) + (SELECT COUNT(*) FROM auftraggeber WHERE syncOffen = 1)")
    fun beobachteOffeneAenderungen(): Flow<Int>

    @Query("DELETE FROM loeschung")
    suspend fun vergissAlleLoeschungen()

    // --- Auftraggeber-Liste ---

    @Query("SELECT * FROM auftraggeber ORDER BY name COLLATE NOCASE")
    fun beobachteAuftraggeber(): Flow<List<AuftraggeberEintrag>>

    @Query("SELECT * FROM auftraggeber ORDER BY name COLLATE NOCASE")
    suspend fun ladeAlleAuftraggeber(): List<AuftraggeberEintrag>

    @Query("SELECT * FROM auftraggeber WHERE lower(trim(name)) = lower(trim(:name)) LIMIT 1")
    suspend fun auftraggeberPerName(name: String): AuftraggeberEintrag?

    @Query("SELECT * FROM auftraggeber WHERE uuid = :uuid")
    suspend fun auftraggeberPerKennung(uuid: String): AuftraggeberEintrag?

    @Query("SELECT * FROM auftraggeber WHERE uuid = :uuid")
    fun beobachteFirma(uuid: String): Flow<AuftraggeberEintrag?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun speichereAuftraggeberListe(liste: List<AuftraggeberEintrag>)

    @Query("DELETE FROM auftraggeber")
    suspend fun loescheAlleAuftraggeber()

    /** Fälle einer Firma – verknüpft oder (z. B. frisch aus der Web-App) nur mit gleichem Namen. */
    @Query(
        "SELECT * FROM schadensfall WHERE auftraggeberUuid = :firma OR " +
            "((auftraggeberUuid IS NULL OR auftraggeberUuid = '') AND lower(trim(auftraggeber)) = lower(trim(:name)) AND trim(:name) != '') ORDER BY geaendertAm DESC",
    )
    fun beobachteFaelleDerFirma(firma: String, name: String): Flow<List<Schadensfall>>

    @Query("SELECT * FROM schadensfall WHERE (auftraggeberUuid IS NULL OR auftraggeberUuid = '') AND trim(auftraggeber) != ''")
    suspend fun faelleOhneFirma(): List<Schadensfall>

    @Query("SELECT * FROM schadensfall WHERE auftraggeberUuid = :firma")
    suspend fun faelleDerFirma(firma: String): List<Schadensfall>

    @Query("UPDATE schadensfall SET auftraggeberUuid = NULL WHERE auftraggeberUuid = :firma")
    suspend fun loeseFaelleVonFirma(firma: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun speichereAuftraggeber(eintrag: AuftraggeberEintrag)

    @Query("DELETE FROM auftraggeber WHERE uuid = :uuid")
    suspend fun loescheAuftraggeber(uuid: String)

    @Query("SELECT * FROM auftraggeber WHERE syncOffen = 1")
    suspend fun offeneAuftraggeber(): List<AuftraggeberEintrag>

    @Query("UPDATE auftraggeber SET syncOffen = 0 WHERE uuid = :uuid AND geaendertAm = :geaendertAm")
    suspend fun auftraggeberIstSynchron(uuid: String, geaendertAm: Long)

    @Query("UPDATE auftraggeber SET syncOffen = 1")
    suspend fun alleAuftraggeberOffen()

    // --- E-Mails ---

    @Insert
    suspend fun einfuegen(mail: MailNachricht): Long

    @Query("SELECT * FROM mail WHERE messageId = :messageId")
    suspend fun mailPerMessageId(messageId: String): MailNachricht?

    @Query("SELECT * FROM mail WHERE id = :id")
    suspend fun mailPerId(id: Long): MailNachricht?

    @Query("SELECT * FROM mail WHERE verlaufUuid = :uuid")
    suspend fun mailZuVerlauf(uuid: String): MailNachricht?

    @Query("SELECT * FROM mail WHERE fallId = :fallId ORDER BY zeitpunkt DESC")
    fun beobachteMails(fallId: Long): Flow<List<MailNachricht>>

    /** Offene Fälle, bei denen die Adresse als Geschädigter oder Auftraggeber eingetragen ist. */
    @Query(
        "SELECT * FROM schadensfall WHERE status NOT IN ('ERLEDIGT', 'ABGELEHNT') AND " +
            "(lower(trim(kundeEmail)) = lower(:adresse) OR lower(trim(auftraggeberEmail)) = lower(:adresse))",
    )
    suspend fun offeneFaellePerEmail(adresse: String): List<Schadensfall>

    @Query("SELECT * FROM schadensfall WHERE fallnummer = :fallnummer")
    suspend fun fallPerNummer(fallnummer: String): Schadensfall?
}
