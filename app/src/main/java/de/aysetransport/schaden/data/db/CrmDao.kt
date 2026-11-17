package de.aysetransport.schaden.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.aysetransport.schaden.data.model.Adresse
import de.aysetransport.schaden.data.model.Aktivitaet
import de.aysetransport.schaden.data.model.Aufgabe
import de.aysetransport.schaden.data.model.Dokument
import de.aysetransport.schaden.data.model.Kontaktperson
import de.aysetransport.schaden.data.model.MailNachricht
import de.aysetransport.schaden.data.model.Preis
import kotlinx.coroutines.flow.Flow

/** Zugriff auf die CRM-Tabellen (alles, was an einer Firma hängt) und auf die E-Mails. */
@Dao
interface CrmDao {
    // --- Adressen ---

    @Query("SELECT * FROM adresse WHERE auftraggeberUuid = :firma ORDER BY art, ort")
    fun beobachteAdressen(firma: String): Flow<List<Adresse>>

    @Query("SELECT * FROM adresse WHERE auftraggeberUuid = :firma ORDER BY art, ort")
    suspend fun ladeAdressen(firma: String): List<Adresse>

    @Query("SELECT * FROM adresse ORDER BY auftraggeberUuid, art, ort")
    suspend fun ladeAlleAdressen(): List<Adresse>

    @Query("SELECT * FROM adresse ORDER BY auftraggeberUuid, art, ort")
    fun beobachteAlleAdressen(): Flow<List<Adresse>>

    @Query("SELECT * FROM adresse WHERE uuid = :uuid")
    suspend fun adressePerKennung(uuid: String): Adresse?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun speichere(eintrag: Adresse)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun speichereAdressen(eintraege: List<Adresse>)

    @Query("DELETE FROM adresse WHERE uuid = :uuid")
    suspend fun loescheAdresse(uuid: String)

    @Query("DELETE FROM adresse")
    suspend fun loescheAlleAdressen()

    @Query("SELECT * FROM adresse WHERE syncOffen = 1")
    suspend fun offeneAdressen(): List<Adresse>

    @Query("UPDATE adresse SET syncOffen = 0 WHERE uuid = :uuid AND geaendertAm = :geaendertAm")
    suspend fun adresseIstSynchron(uuid: String, geaendertAm: Long)

    @Query("UPDATE adresse SET syncOffen = 1")
    suspend fun alleAdressenOffen()

    @Query("UPDATE adresse SET auftraggeberUuid = :nach, syncOffen = 1, geaendertAm = :jetzt WHERE auftraggeberUuid = :von")
    suspend fun haengeAdressenUm(von: String, nach: String, jetzt: Long)

    // --- Kontaktpersonen ---

    @Query("SELECT * FROM ansprechpartner WHERE auftraggeberUuid = :firma ORDER BY name COLLATE NOCASE")
    fun beobachteKontaktpersonen(firma: String): Flow<List<Kontaktperson>>

    @Query("SELECT * FROM ansprechpartner WHERE auftraggeberUuid = :firma ORDER BY name COLLATE NOCASE")
    suspend fun ladeKontaktpersonen(firma: String): List<Kontaktperson>

    @Query("SELECT * FROM ansprechpartner ORDER BY auftraggeberUuid, name COLLATE NOCASE")
    suspend fun ladeAlleKontaktpersonen(): List<Kontaktperson>

    @Query("SELECT * FROM ansprechpartner ORDER BY auftraggeberUuid, name COLLATE NOCASE")
    fun beobachteAlleKontaktpersonen(): Flow<List<Kontaktperson>>

    @Query("SELECT * FROM ansprechpartner WHERE uuid = :uuid")
    suspend fun kontaktpersonPerKennung(uuid: String): Kontaktperson?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun speichere(eintrag: Kontaktperson)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun speichereKontaktpersonen(eintraege: List<Kontaktperson>)

    @Query("DELETE FROM ansprechpartner WHERE uuid = :uuid")
    suspend fun loescheKontaktperson(uuid: String)

    @Query("DELETE FROM ansprechpartner")
    suspend fun loescheAlleKontaktpersonen()

    @Query("SELECT * FROM ansprechpartner WHERE syncOffen = 1")
    suspend fun offeneKontaktpersonen(): List<Kontaktperson>

    @Query("UPDATE ansprechpartner SET syncOffen = 0 WHERE uuid = :uuid AND geaendertAm = :geaendertAm")
    suspend fun kontaktpersonIstSynchron(uuid: String, geaendertAm: Long)

    @Query("UPDATE ansprechpartner SET syncOffen = 1")
    suspend fun alleKontaktpersonenOffen()

    @Query("UPDATE ansprechpartner SET auftraggeberUuid = :nach, syncOffen = 1, geaendertAm = :jetzt WHERE auftraggeberUuid = :von")
    suspend fun haengeKontaktpersonenUm(von: String, nach: String, jetzt: Long)

    // --- Preise ---

    @Query("SELECT * FROM preis WHERE auftraggeberUuid = :firma ORDER BY bezeichnung COLLATE NOCASE, gueltigAb DESC")
    fun beobachtePreise(firma: String): Flow<List<Preis>>

    @Query("SELECT * FROM preis WHERE auftraggeberUuid = :firma ORDER BY bezeichnung COLLATE NOCASE, gueltigAb DESC")
    suspend fun ladePreise(firma: String): List<Preis>

    @Query("SELECT * FROM preis ORDER BY auftraggeberUuid, bezeichnung COLLATE NOCASE, gueltigAb DESC")
    suspend fun ladeAllePreise(): List<Preis>

    @Query("SELECT * FROM preis ORDER BY auftraggeberUuid, bezeichnung COLLATE NOCASE, gueltigAb DESC")
    fun beobachteAllePreise(): Flow<List<Preis>>

    @Query("SELECT * FROM preis WHERE uuid = :uuid")
    suspend fun preisPerKennung(uuid: String): Preis?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun speichere(eintrag: Preis)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun speicherePreise(eintraege: List<Preis>)

    @Query("DELETE FROM preis WHERE uuid = :uuid")
    suspend fun loeschePreis(uuid: String)

    @Query("DELETE FROM preis")
    suspend fun loescheAllePreise()

    @Query("SELECT * FROM preis WHERE syncOffen = 1")
    suspend fun offenePreise(): List<Preis>

    @Query("UPDATE preis SET syncOffen = 0 WHERE uuid = :uuid AND geaendertAm = :geaendertAm")
    suspend fun preisIstSynchron(uuid: String, geaendertAm: Long)

    @Query("UPDATE preis SET syncOffen = 1")
    suspend fun allePreiseOffen()

    @Query("UPDATE preis SET auftraggeberUuid = :nach, syncOffen = 1, geaendertAm = :jetzt WHERE auftraggeberUuid = :von")
    suspend fun haengePreiseUm(von: String, nach: String, jetzt: Long)

    // --- Aktivitaeten ---

    @Query("SELECT * FROM aktivitaet WHERE auftraggeberUuid = :firma ORDER BY datum DESC, geaendertAm DESC")
    fun beobachteAktivitaeten(firma: String): Flow<List<Aktivitaet>>

    @Query("SELECT * FROM aktivitaet WHERE auftraggeberUuid = :firma ORDER BY datum DESC, geaendertAm DESC")
    suspend fun ladeAktivitaeten(firma: String): List<Aktivitaet>

    @Query("SELECT * FROM aktivitaet ORDER BY auftraggeberUuid, datum DESC, geaendertAm DESC")
    suspend fun ladeAlleAktivitaeten(): List<Aktivitaet>

    @Query("SELECT * FROM aktivitaet ORDER BY auftraggeberUuid, datum DESC, geaendertAm DESC")
    fun beobachteAlleAktivitaeten(): Flow<List<Aktivitaet>>

    @Query("SELECT * FROM aktivitaet WHERE uuid = :uuid")
    suspend fun aktivitaetPerKennung(uuid: String): Aktivitaet?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun speichere(eintrag: Aktivitaet)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun speichereAktivitaeten(eintraege: List<Aktivitaet>)

    @Query("DELETE FROM aktivitaet WHERE uuid = :uuid")
    suspend fun loescheAktivitaet(uuid: String)

    @Query("DELETE FROM aktivitaet")
    suspend fun loescheAlleAktivitaeten()

    @Query("SELECT * FROM aktivitaet WHERE syncOffen = 1")
    suspend fun offeneAktivitaeten(): List<Aktivitaet>

    @Query("UPDATE aktivitaet SET syncOffen = 0 WHERE uuid = :uuid AND geaendertAm = :geaendertAm")
    suspend fun aktivitaetIstSynchron(uuid: String, geaendertAm: Long)

    @Query("UPDATE aktivitaet SET syncOffen = 1")
    suspend fun alleAktivitaetenOffen()

    @Query("UPDATE aktivitaet SET auftraggeberUuid = :nach, syncOffen = 1, geaendertAm = :jetzt WHERE auftraggeberUuid = :von")
    suspend fun haengeAktivitaetenUm(von: String, nach: String, jetzt: Long)

    // --- Aufgaben ---

    @Query("SELECT * FROM aufgabe WHERE auftraggeberUuid = :firma ORDER BY erledigt, faelligAm IS NULL, faelligAm, text")
    fun beobachteAufgaben(firma: String): Flow<List<Aufgabe>>

    @Query("SELECT * FROM aufgabe WHERE auftraggeberUuid = :firma ORDER BY erledigt, faelligAm IS NULL, faelligAm, text")
    suspend fun ladeAufgaben(firma: String): List<Aufgabe>

    @Query("SELECT * FROM aufgabe ORDER BY auftraggeberUuid, erledigt, faelligAm IS NULL, faelligAm, text")
    suspend fun ladeAlleAufgaben(): List<Aufgabe>

    @Query("SELECT * FROM aufgabe ORDER BY auftraggeberUuid, erledigt, faelligAm IS NULL, faelligAm, text")
    fun beobachteAlleAufgaben(): Flow<List<Aufgabe>>

    @Query("SELECT * FROM aufgabe WHERE uuid = :uuid")
    suspend fun aufgabePerKennung(uuid: String): Aufgabe?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun speichere(eintrag: Aufgabe)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun speichereAufgaben(eintraege: List<Aufgabe>)

    @Query("DELETE FROM aufgabe WHERE uuid = :uuid")
    suspend fun loescheAufgabe(uuid: String)

    @Query("DELETE FROM aufgabe")
    suspend fun loescheAlleAufgaben()

    @Query("SELECT * FROM aufgabe WHERE syncOffen = 1")
    suspend fun offeneAufgaben(): List<Aufgabe>

    @Query("UPDATE aufgabe SET syncOffen = 0 WHERE uuid = :uuid AND geaendertAm = :geaendertAm")
    suspend fun aufgabeIstSynchron(uuid: String, geaendertAm: Long)

    @Query("UPDATE aufgabe SET syncOffen = 1")
    suspend fun alleAufgabenOffen()

    @Query("UPDATE aufgabe SET auftraggeberUuid = :nach, syncOffen = 1, geaendertAm = :jetzt WHERE auftraggeberUuid = :von")
    suspend fun haengeAufgabenUm(von: String, nach: String, jetzt: Long)

    // --- Dokumente ---

    @Query("SELECT * FROM dokument WHERE auftraggeberUuid = :firma ORDER BY titel COLLATE NOCASE")
    fun beobachteDokumente(firma: String): Flow<List<Dokument>>

    @Query("SELECT * FROM dokument WHERE auftraggeberUuid = :firma ORDER BY titel COLLATE NOCASE")
    suspend fun ladeDokumente(firma: String): List<Dokument>

    @Query("SELECT * FROM dokument ORDER BY auftraggeberUuid, titel COLLATE NOCASE")
    suspend fun ladeAlleDokumente(): List<Dokument>

    @Query("SELECT * FROM dokument ORDER BY auftraggeberUuid, titel COLLATE NOCASE")
    fun beobachteAlleDokumente(): Flow<List<Dokument>>

    @Query("SELECT * FROM dokument WHERE uuid = :uuid")
    suspend fun dokumentPerKennung(uuid: String): Dokument?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun speichere(eintrag: Dokument)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun speichereDokumente(eintraege: List<Dokument>)

    @Query("DELETE FROM dokument WHERE uuid = :uuid")
    suspend fun loescheDokument(uuid: String)

    @Query("DELETE FROM dokument")
    suspend fun loescheAlleDokumente()

    @Query("SELECT * FROM dokument WHERE syncOffen = 1")
    suspend fun offeneDokumente(): List<Dokument>

    @Query("UPDATE dokument SET syncOffen = 0 WHERE uuid = :uuid AND geaendertAm = :geaendertAm")
    suspend fun dokumentIstSynchron(uuid: String, geaendertAm: Long)

    @Query("UPDATE dokument SET syncOffen = 1")
    suspend fun alleDokumenteOffen()

    @Query("UPDATE dokument SET auftraggeberUuid = :nach, syncOffen = 1, geaendertAm = :jetzt WHERE auftraggeberUuid = :von")
    suspend fun haengeDokumenteUm(von: String, nach: String, jetzt: Long)

    @Query("SELECT * FROM aufgabe WHERE erledigt = 0 ORDER BY faelligAm IS NULL, faelligAm")
    fun beobachteOffeneAufgaben(): Flow<List<Aufgabe>>

    @Query(
        "SELECT (SELECT COUNT(*) FROM adresse WHERE syncOffen = 1) + (SELECT COUNT(*) FROM ansprechpartner WHERE syncOffen = 1) + " +
            "(SELECT COUNT(*) FROM preis WHERE syncOffen = 1) + (SELECT COUNT(*) FROM aktivitaet WHERE syncOffen = 1) + " +
            "(SELECT COUNT(*) FROM aufgabe WHERE syncOffen = 1) + (SELECT COUNT(*) FROM dokument WHERE syncOffen = 1)",
    )
    fun beobachteOffeneCrmAenderungen(): Flow<Int>

    // --- E-Mails ---

    @Query("SELECT * FROM mail ORDER BY zeitpunkt DESC")
    fun beobachteAlleMails(): Flow<List<MailNachricht>>

    @Query("SELECT COUNT(*) FROM mail WHERE fallId IS NULL AND auftraggeberUuid IS NULL AND kundeRef = ''")
    fun beobachteNichtZugeordnet(): Flow<Int>

    @Query("SELECT * FROM mail ORDER BY id")
    suspend fun ladeAlleMails(): List<MailNachricht>

    @Query("SELECT * FROM mail WHERE id = :id")
    suspend fun mail(id: Long): MailNachricht?

    @Query("SELECT * FROM mail WHERE id = :id")
    fun beobachteMail(id: Long): Flow<MailNachricht?>

    /** Mails der Firma: direkt zugeordnet oder über einen ihrer Fälle. */
    @Query(
        "SELECT * FROM mail WHERE auftraggeberUuid = :firma OR fallId IN " +
            "(SELECT id FROM schadensfall WHERE auftraggeberUuid = :firma OR (auftraggeberUuid IS NULL AND lower(trim(auftraggeber)) = lower(trim(:name)) AND trim(:name) != '')) " +
            "ORDER BY zeitpunkt DESC",
    )
    fun beobachteMailsDerFirma(firma: String, name: String): Flow<List<MailNachricht>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun speichereMail(mail: MailNachricht): Long

    @Insert
    suspend fun einfuegenMails(mails: List<MailNachricht>)

    @Query("DELETE FROM mail")
    suspend fun loescheAlleMails()

    @Query("UPDATE mail SET auftraggeberUuid = NULL, aktivitaetUuid = '' WHERE auftraggeberUuid = :firma")
    suspend fun loeseMailsVonFirma(firma: String)

    @Query("UPDATE mail SET auftraggeberUuid = :nach WHERE auftraggeberUuid = :von")
    suspend fun haengeMailsUm(von: String, nach: String)
}
