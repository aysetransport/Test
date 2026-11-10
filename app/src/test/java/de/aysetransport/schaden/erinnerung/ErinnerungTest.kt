package de.aysetransport.schaden.erinnerung

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import de.aysetransport.schaden.SchadensfallApp
import de.aysetransport.schaden.data.model.FallStatus
import de.aysetransport.schaden.data.model.Schadensfall
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class ErinnerungTest {

    private val app: SchadensfallApp = ApplicationProvider.getApplicationContext()
    private val context: Context = app
    private val heute = LocalDate.now()
    private val benachrichtigungen get() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    @After
    fun tearDown() {
        app.container.datenbank.close()
    }

    private fun erlaubeBenachrichtigungen() {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `ohne Berechtigung wird nichts angezeigt`() {
        shadowOf(app as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val fall = Schadensfall(id = 1, kundeName = "Anna Becker", wiedervorlageAm = heute)

        assertFalse(Benachrichtigungen.zeigeFaellige(context, listOf(fall), heute))
        assertTrue(benachrichtigungen.isEmpty())
    }

    @Test
    fun `ein faelliger Fall fuehrt direkt in den Fall`() {
        erlaubeBenachrichtigungen()
        val fall = Schadensfall(id = 42, kundeName = "Anna Becker", wiedervorlageAm = heute.minusDays(2))

        assertTrue(Benachrichtigungen.zeigeFaellige(context, listOf(fall), heute))

        val benachrichtigung = benachrichtigungen.single()
        assertEquals("1 Schadensfall zum Nachhaken", shadowOf(benachrichtigung).contentTitle)
        assertEquals("Anna Becker – seit 2 Tagen überfällig", shadowOf(benachrichtigung).contentText)
        val intent = shadowOf(benachrichtigung.contentIntent).savedIntent
        assertEquals(42L, intent.getLongExtra(Benachrichtigungen.EXTRA_FALL_ID, 0))
    }

    @Test
    fun `mehrere faellige Faelle fuehren in die Liste`() {
        erlaubeBenachrichtigungen()
        val faelle = (1L..3L).map { Schadensfall(id = it, kundeName = "Kunde $it", wiedervorlageAm = heute) }

        assertTrue(Benachrichtigungen.zeigeFaellige(context, faelle, heute))

        val benachrichtigung = benachrichtigungen.single()
        assertEquals("3 Schadensfälle zum Nachhaken", shadowOf(benachrichtigung).contentTitle)
        val intent = shadowOf(benachrichtigung.contentIntent).savedIntent
        assertTrue(intent.getBooleanExtra(Benachrichtigungen.EXTRA_ZEIGE_FAELLIGE, false))
        assertEquals(0L, intent.getLongExtra(Benachrichtigungen.EXTRA_FALL_ID, 0))
    }

    @Test
    fun `der taegliche Lauf meldet faellige Faelle und plant sich neu`() = runBlocking {
        erlaubeBenachrichtigungen()
        app.container.repository.anlegen(Schadensfall(kundeName = "Fällig", wiedervorlageAm = heute.minusDays(1)))
        app.container.repository.anlegen(Schadensfall(kundeName = "Später", wiedervorlageAm = heute.plusDays(5)))
        val erledigt = app.container.repository.anlegen(Schadensfall(kundeName = "Erledigt", wiedervorlageAm = heute.minusDays(1)))
        app.container.repository.statusAendern(erledigt, FallStatus.ERLEDIGT)

        val ergebnis = TestListenableWorkerBuilder<WiedervorlageWorker>(context).build().doWork()

        assertEquals(ListenableWorker.Result.success(), ergebnis)
        assertEquals("1 Schadensfall zum Nachhaken", shadowOf(benachrichtigungen.single()).contentTitle)
        val geplant = WorkManager.getInstance(context).getWorkInfosForUniqueWork(ErinnerungsPlaner.ARBEIT_NAME).get()
        assertEquals(WorkInfo.State.ENQUEUED, geplant.single().state)
    }

    @Test
    fun `ohne faellige Faelle bleibt es still`() = runBlocking {
        erlaubeBenachrichtigungen()
        app.container.repository.anlegen(Schadensfall(kundeName = "Später", wiedervorlageAm = heute.plusDays(5)))

        TestListenableWorkerBuilder<WiedervorlageWorker>(context).build().doWork()

        assertTrue(benachrichtigungen.isEmpty())
    }

    @Test
    fun `abgeschaltete Erinnerung entfernt den geplanten Lauf`() {
        ErinnerungsPlaner.plane(context, aktiv = true, uhrzeit = LocalTime.of(8, 0), bestehendenPlanErsetzen = true)
        ErinnerungsPlaner.plane(context, aktiv = false, uhrzeit = LocalTime.of(8, 0), bestehendenPlanErsetzen = true)

        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(ErinnerungsPlaner.ARBEIT_NAME).get()
        assertTrue(infos.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun `App-Start verschiebt einen bestehenden Plan nicht, eine geaenderte Einstellung schon`() {
        val workManager = WorkManager.getInstance(context)
        fun naechsterLauf() = workManager.getWorkInfosForUniqueWork(ErinnerungsPlaner.ARBEIT_NAME).get().single().nextScheduleTimeMillis
        val zone = ZoneId.systemDefault()
        val heuteFrueh = LocalDate.now().atTime(7, 0)
        val achtUhrHeute = LocalDate.now().atTime(8, 0).atZone(zone).toInstant().toEpochMilli()

        // Um 7:00 geplant: Der Lauf steht für heute 8:00 an.
        ErinnerungsPlaner.plane(context, true, LocalTime.of(8, 0), bestehendenPlanErsetzen = true, jetzt = heuteFrueh, zone = zone)
        assertEquals(achtUhrHeute, naechsterLauf())

        // Um 8:00:05 startet Android die App für genau diesen Lauf – er darf nicht auf morgen rutschen.
        ErinnerungsPlaner.plane(context, true, LocalTime.of(8, 0), bestehendenPlanErsetzen = false, jetzt = heuteFrueh.plusHours(1).plusSeconds(5), zone = zone)
        assertEquals(achtUhrHeute, naechsterLauf())

        // Stellt der Nutzer die Uhrzeit um, gilt sie sofort.
        ErinnerungsPlaner.plane(context, true, LocalTime.of(9, 30), bestehendenPlanErsetzen = true, jetzt = heuteFrueh, zone = zone)
        assertEquals(LocalDate.now().atTime(9, 30).atZone(zone).toInstant().toEpochMilli(), naechsterLauf())
    }

    @Test
    fun `nach einem echten Lauf steht der naechste wieder zur eingestellten Uhrzeit an`() {
        erlaubeBenachrichtigungen()
        runBlocking { app.container.repository.anlegen(Schadensfall(kundeName = "Fällig", wiedervorlageAm = heute)) }
        val workManager = WorkManager.getInstance(context)
        val zone = ZoneId.systemDefault()

        // Der Plan stammt von vorgestern – der Lauf ist also überfällig, wie nach einem ausgeschalteten Gerät.
        val vorgestern = LocalDate.now().minusDays(2).atTime(7, 0)
        ErinnerungsPlaner.plane(context, true, LocalTime.of(8, 0), bestehendenPlanErsetzen = true, jetzt = vorgestern, zone = zone)
        val id = workManager.getWorkInfosForUniqueWork(ErinnerungsPlaner.ARBEIT_NAME).get().single().id

        val vorher = java.time.LocalDateTime.now()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setPeriodDelayMet(id)
        val ende = System.currentTimeMillis() + 10_000
        // Der Lauf ist abgeschlossen, sobald der Auftrag wieder wartet und ein künftiger Termin eingetragen ist.
        fun stand() = workManager.getWorkInfoById(id).get()!!
        while (System.currentTimeMillis() < ende &&
            (stand().state != WorkInfo.State.ENQUEUED || stand().nextScheduleTimeMillis < System.currentTimeMillis())
        ) {
            Thread.sleep(20)
        }

        val danach = stand()
        assertEquals(WorkInfo.State.ENQUEUED, danach.state)
        assertEquals("1 Schadensfall zum Nachhaken", shadowOf(benachrichtigungen.single()).contentTitle)
        // Standard-Einstellung der Test-App: täglich 8:00 Uhr.
        val erwartet = ErinnerungsPlaner.naechsterTermin(LocalTime.of(8, 0), vorher).atZone(zone).toInstant().toEpochMilli()
        assertEquals(erwartet, danach.nextScheduleTimeMillis)
    }
}
