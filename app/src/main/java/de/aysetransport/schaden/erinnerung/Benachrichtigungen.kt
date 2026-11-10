package de.aysetransport.schaden.erinnerung

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.aysetransport.schaden.MainActivity
import de.aysetransport.schaden.R
import de.aysetransport.schaden.data.model.Schadensfall
import de.aysetransport.schaden.domain.faelligkeit
import de.aysetransport.schaden.mail.NeueMail
import java.time.LocalDate

object Benachrichtigungen {

    const val KANAL_ID = "wiedervorlage"
    const val EXTRA_FALL_ID = "de.aysetransport.schaden.FALL_ID"
    const val EXTRA_ZEIGE_FAELLIGE = "de.aysetransport.schaden.ZEIGE_FAELLIGE"
    private const val BENACHRICHTIGUNG_ID = 1001
    private const val MAIL_ID = 1002
    private const val MAX_ZEILEN = 6

    fun kanalAnlegen(context: Context) {
        val kanal = NotificationChannel(
            KANAL_ID,
            context.getString(R.string.kanal_wiedervorlage_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.kanal_wiedervorlage_beschreibung)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(kanal)
    }

    /** Neue E-Mails zu Fällen: bei einer Mail direkt in den Fall, sonst in die Liste „Fällig“. */
    fun zeigeNeueMails(context: Context, mails: List<NeueMail>): Boolean {
        if (mails.isEmpty() || !duerfenAngezeigtWerden(context)) return false
        val faelle = mails.map { it.fall.id }.distinct()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (faelle.size == 1) putExtra(EXTRA_FALL_ID, faelle.single()) else putExtra(EXTRA_ZEIGE_FAELLIGE, true)
        }
        val antippen = PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val titel = if (mails.size == 1) "Neue E-Mail zu Fall ${mails.single().fall.fallnummer}" else "${mails.size} neue E-Mails zu Schadensfällen"
        val stil = NotificationCompat.InboxStyle().also { s -> mails.take(MAX_ZEILEN).forEach { s.addLine("${it.fall.fallnummer} · ${it.von}: ${it.betreff}") } }
        val n = NotificationCompat.Builder(context, KANAL_ID)
            .setSmallIcon(R.drawable.ic_benachrichtigung)
            .setColor(ContextCompat.getColor(context, R.color.akzent))
            .setContentTitle(titel)
            .setContentText(mails.first().let { "${it.von}: ${it.betreff}" })
            .setStyle(stil)
            .setContentIntent(antippen)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(MAIL_ID, n)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun duerfenAngezeigtWerden(context: Context): Boolean {
        val erlaubt = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return erlaubt && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Zeigt eine Sammelbenachrichtigung für alle fälligen Fälle. Bei genau einem Fall führt
     * das Antippen direkt in den Fall, sonst in die Liste „Fällig“.
     *
     * @return `false`, wenn nichts angezeigt wurde (keine Fälle oder keine Berechtigung)
     */
    fun zeigeFaellige(context: Context, faelle: List<Schadensfall>, heute: LocalDate): Boolean {
        if (faelle.isEmpty() || !duerfenAngezeigtWerden(context)) return false

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (faelle.size == 1) putExtra(EXTRA_FALL_ID, faelle.first().id) else putExtra(EXTRA_ZEIGE_FAELLIGE, true)
        }
        val antippen = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val titel = if (faelle.size == 1) "1 Schadensfall zum Nachhaken" else "${faelle.size} Schadensfälle zum Nachhaken"
        val zeilen = faelle.take(MAX_ZEILEN).map { "${it.anzeigeName} – ${it.faelligkeit(heute).text()}" }
        val stil = NotificationCompat.InboxStyle().also { stil ->
            zeilen.forEach(stil::addLine)
            if (faelle.size > MAX_ZEILEN) stil.setSummaryText("+ ${faelle.size - MAX_ZEILEN} weitere")
        }

        val benachrichtigung = NotificationCompat.Builder(context, KANAL_ID)
            .setSmallIcon(R.drawable.ic_benachrichtigung)
            .setColor(ContextCompat.getColor(context, R.color.akzent))
            .setContentTitle(titel)
            .setContentText(zeilen.first())
            .setStyle(stil)
            .setContentIntent(antippen)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(BENACHRICHTIGUNG_ID, benachrichtigung)
            true
        } catch (e: SecurityException) {
            false // Berechtigung wurde zwischen Prüfung und Anzeige entzogen
        }
    }
}
