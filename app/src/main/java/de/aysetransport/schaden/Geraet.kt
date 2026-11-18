package de.aysetransport.schaden

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.print.PdfAusDruck
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.view.View
import android.webkit.MimeTypeMap
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Was die Oberfläche vom Gerät braucht: Dateien, PDF, Drucken, Benachrichtigungen. Läuft auf dem Haupt-Thread. */
class Geraet(private val activity: MainActivity, private val wurzel: FrameLayout) {

    private fun sicher(name: String?) = (name ?: "Datei").replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120).ifBlank { "Datei" }

    private fun mimeTyp(name: String) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    /** Öffnet eine Datei (Anlage, Dokument) mit einer passenden App des Geräts. */
    fun oeffne(name: String, bytes: ByteArray) {
        val ordner = File(activity.cacheDir, "oeffnen").apply { mkdirs() }
        ordner.listFiles()?.forEach { it.delete() } // nur die zuletzt geöffnete Datei aufheben
        val datei = File(ordner, sicher(name)).apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", datei)
        val absicht = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeTyp(datei.name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(Intent.createChooser(absicht, datei.name))
        } catch (e: ActivityNotFoundException) {
            throw IOException("Auf dem Gerät ist keine App zum Öffnen von „${datei.name}“ installiert.")
        }
    }

    /** Öffnet Telefon, E-Mail-Programm oder Browser. */
    fun oeffneExtern(adresse: String) {
        if (!Regex("^(mailto:|tel:|https:)", RegexOption.IGNORE_CASE).containsMatchIn(adresse)) throw IOException("Adresse nicht erlaubt")
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, adresse.toUri()))
        } catch (e: ActivityNotFoundException) {
            throw IOException("Keine passende App gefunden.")
        }
    }

    /** Speichert eine Datei im Ordner „Download/Ayse CRM“ und gibt den Ort zurück. */
    fun speichere(name: String, bytes: ByteArray): String {
        val dateiname = sicher(name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val werte = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, dateiname)
                put(MediaStore.Downloads.MIME_TYPE, mimeTyp(dateiname))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Ayse CRM")
            }
            val aufloeser = activity.contentResolver
            val uri = aufloeser.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, werte) ?: throw IOException("Die Datei konnte nicht gespeichert werden.")
            aufloeser.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("Die Datei konnte nicht gespeichert werden.")
            return "Download/Ayse CRM/$dateiname"
        }
        // Android 8 und 9: ohne Speicher-Berechtigung in den Download-Ordner der App
        val ordner = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.filesDir
        File(ordner, dateiname).writeBytes(bytes)
        return File(ordner, dateiname).absolutePath
    }

    /** Lädt HTML in eine unsichtbare, angehängte WebView (ohne JavaScript) und wartet, bis sie fertig ist. */
    @SuppressLint("ViewConstructor")
    private suspend fun ladeUnsichtbar(html: String, breitePx: Int, hoehePx: Int): WebView = suspendCancellableCoroutine { fortsetzung ->
        val w = WebView(activity)
        w.settings.javaScriptEnabled = false
        w.visibility = View.INVISIBLE
        wurzel.addView(w, 0, FrameLayout.LayoutParams(breitePx, hoehePx))
        w.webViewClient = object : WebViewClient() {
            private var fertig = false
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                wurzel.removeView(view)
                view.destroy()
                if (fortsetzung.isActive) fortsetzung.resumeWithException(IOException("Die Seite konnte nicht aufbereitet werden – bitte noch einmal versuchen."))
                return true
            }
            override fun onPageFinished(view: WebView, url: String?) {
                if (fertig) return
                fertig = true
                view.postDelayed({ if (fortsetzung.isActive) fortsetzung.resume(view) }, 400)
            }
        }
        fortsetzung.invokeOnCancellation { wurzel.removeView(w); w.destroy() }
        w.loadDataWithBaseURL("https://${WebInhalt.HOST}/", html, "text/html", "utf-8", null)
    }

    /** Erzeugt aus einer fertigen HTML-Seite (Angebot, Rechnung) ein PDF im Format A4 – über den Druck-Weg von Android. */
    suspend fun pdf(html: String): ByteArray {
        val dichte = activity.resources.displayMetrics.density
        val w = ladeUnsichtbar(html, (794 * dichte).toInt(), (1123 * dichte).toInt())
        val datei = File(activity.cacheDir, "beleg-${System.nanoTime()}.pdf")
        try {
            val attribute = PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(PrintAttributes.Resolution("pdf", "pdf", 600, 600))
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()
            suspendCancellableCoroutine { fortsetzung ->
                PdfAusDruck.schreibe(w.createPrintDocumentAdapter("Beleg"), attribute, datei) { fehler ->
                    if (fehler == null) fortsetzung.resume(Unit) else fortsetzung.resumeWithException(IOException("PDF konnte nicht erzeugt werden: ${fehler.message}"))
                }
            }
            return datei.readBytes()
        } finally {
            datei.delete()
            wurzel.removeView(w)
            w.destroy()
        }
    }

    /** Öffnet den Druckdialog von Android (dort auch „Als PDF speichern“). */
    suspend fun drucke(html: String) {
        val dichte = activity.resources.displayMetrics.density
        val w = ladeUnsichtbar(html, (794 * dichte).toInt(), (1123 * dichte).toInt())
        val drucker = activity.getSystemService(PrintManager::class.java)
        drucker.print("Ayse CRM", w.createPrintDocumentAdapter("Ayse CRM"), PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
        // Die WebView muss bestehen bleiben, solange der Druckdialog sie braucht; sie wird beim nächsten Druck ersetzt.
        letzterDruck?.let { wurzel.removeView(it); it.destroy() }
        letzterDruck = w
    }
    private var letzterDruck: WebView? = null

    // ------------------------------ Benachrichtigungen ------------------------------
    private val kanal = "mail"

    fun benachrichtige(titel: String, text: String, ziel: String?): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            activity.frageBenachrichtigungen()
            return false
        }
        val verwaltung = activity.getSystemService(NotificationManager::class.java)
        verwaltung.createNotificationChannel(NotificationChannel(kanal, "Neue E-Mails", NotificationManager.IMPORTANCE_DEFAULT))
        val oeffnen = Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.ZIEL, ziel)
        val pi = PendingIntent.getActivity(activity, (ziel ?: "").hashCode(), oeffnen, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(activity, kanal)
            .setSmallIcon(R.drawable.ic_benachrichtigung)
            .setContentTitle(titel)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        @SuppressLint("MissingPermission")
        fun zeige() = NotificationManagerCompat.from(activity).notify((titel + text).hashCode(), n)
        zeige()
        return true
    }
}
