package de.aysetransport.schaden.erinnerung

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Plant die tägliche Erinnerung an fällige Wiedervorlagen. */
object ErinnerungsPlaner {

    const val ARBEIT_NAME = "wiedervorlage-erinnerung"

    /**
     * Richtet die tägliche Prüfung zur gewünschten Uhrzeit ein. Bei `aktiv = false` wird sie
     * abgeschaltet.
     *
     * @param bestehendenPlanErsetzen `true` nach einer geänderten Einstellung und am Ende eines
     *   Laufs (dann gilt die neue Uhrzeit ab dem nächsten Lauf). Beim App-Start muss es
     *   `false` sein: Android startet die App auch eigens für den fälligen Lauf – würde der
     *   Start ihn auf morgen verschieben, käme die Erinnerung nie an.
     */
    fun plane(
        context: Context,
        aktiv: Boolean,
        uhrzeit: LocalTime,
        bestehendenPlanErsetzen: Boolean,
        jetzt: LocalDateTime = LocalDateTime.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        val workManager = WorkManager.getInstance(context)
        if (!aktiv) {
            workManager.cancelUniqueWork(ARBEIT_NAME)
            return
        }
        val naechsterLauf = naechsterTermin(uhrzeit, jetzt).atZone(zone).toInstant().toEpochMilli()
        val auftrag = PeriodicWorkRequestBuilder<WiedervorlageWorker>(24, TimeUnit.HOURS)
            // Ohne diese Vorgabe würde die Uhrzeit mit jedem Lauf ein Stück wandern.
            .setNextScheduleTimeOverride(naechsterLauf)
            .build()
        val regel = if (bestehendenPlanErsetzen) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP
        workManager.enqueueUniquePeriodicWork(ARBEIT_NAME, regel, auftrag)
    }

    /** Nächster Zeitpunkt, zu dem es [uhrzeit] ist – heute, falls noch nicht vorbei, sonst morgen. */
    fun naechsterTermin(uhrzeit: LocalTime, jetzt: LocalDateTime): LocalDateTime {
        val heute = jetzt.toLocalDate().atTime(uhrzeit)
        return if (heute.isAfter(jetzt)) heute else heute.plusDays(1)
    }
}
