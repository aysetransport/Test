package de.aysetransport.schaden.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import de.aysetransport.schaden.data.model.Anlage
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
}
