package de.aysetransport.schaden

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.Uri
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.widget.Toast
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
        if (!Regex("^(mailto:|tel:|sms:|https:)", RegexOption.IGNORE_CASE).containsMatchIn(adresse)) throw IOException("Adresse nicht erlaubt")
        val ziel = if (adresse.startsWith("sms:", true)) smsZiel(adresse) else null
        if (ziel != null && activity.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) { sms(adresse, ziel.first, ziel.second); return }
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

    // ------------------------------ SMS ------------------------------
    /** SMS direkt aus der App: wie in der SMS-App erst bestätigen, dann senden; „In SMS-App“ bleibt als Ausweg. */
    private fun sms(adresse: String, nummern: List<String>, text: String) {
        AlertDialog.Builder(activity)
            .setTitle(if (nummern.size == 1) "SMS an ${nummern[0]}" else "SMS an ${nummern.size} Empfänger")
            .setMessage(text)
            .setPositiveButton("Senden") { _, _ ->
                if (smsErlaubt()) sendeSms(nummern, text)
                else activity.frageSms { erlaubt -> if (erlaubt) sendeSms(nummern, text) else smsApp(adresse) }
            }
            .setNeutralButton("In SMS-App") { _, _ -> smsApp(adresse) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun smsApp(adresse: String) {
        try { activity.startActivity(Intent(Intent.ACTION_VIEW, adresse.toUri())) } catch (e: ActivityNotFoundException) { meldung("Keine SMS-App gefunden.") }
    }

    private fun meldung(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    private fun smsErlaubt() = ContextCompat.checkSelfPermission(activity, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    private fun sendeSms(nummern: List<String>, text: String) {
        var offen = nummern.size
        val fehler = mutableListOf<String>()
        for (nr in nummern) sendeEine(nr, text) { f ->
            if (f.isNotEmpty()) fehler += "$nr: $f"
            if (--offen == 0) meldung(if (fehler.isEmpty()) "SMS gesendet" else "SMS nicht gesendet – ${fehler.joinToString("; ")}")
        }
    }

    /** SMS über die SIM, die in Android als Standard für SMS eingestellt ist (Dual-SIM: sonst kann das Senden scheitern). */
    private fun smsManager(): SmsManager {
        val sim = SubscriptionManager.getDefaultSmsSubscriptionId()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) activity.getSystemService(SmsManager::class.java).let { if (sim != SubscriptionManager.INVALID_SUBSCRIPTION_ID) it.createForSubscriptionId(sim) else it }
        else @Suppress("DEPRECATION") (if (sim != SubscriptionManager.INVALID_SUBSCRIPTION_ID) SmsManager.getSmsManagerForSubscriptionId(sim) else SmsManager.getDefault())
    }

    /** Eine SMS (ggf. in Teilen) senden. fertig("") sobald alle Teile raus sind, sonst fertig(Fehlertext). Läuft auf dem Haupt-Thread. */
    @SuppressLint("MissingPermission")
    private fun sendeEine(nr: String, text: String, fertig: (String) -> Unit) {
        val aktion = "${activity.packageName}.SMS_GESENDET.${System.nanoTime()}" // je SMS eigene Rückmeldung
        var empfaenger: BroadcastReceiver? = null
        try {
            val sm = smsManager()
            val teile = sm.divideMessage(text)
            var offen = teile.size
            var fehler = 0
            empfaenger = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (resultCode != Activity.RESULT_OK && fehler == 0) fehler = resultCode
                    if (--offen > 0) return
                    runCatching { activity.unregisterReceiver(this) }
                    fertig(if (fehler == 0) "" else smsFehlerText(fehler))
                }
            }
            ContextCompat.registerReceiver(activity, empfaenger, IntentFilter(aktion), ContextCompat.RECEIVER_NOT_EXPORTED)
            val gesendet = PendingIntent.getBroadcast(activity, 0, Intent(aktion).setPackage(activity.packageName), PendingIntent.FLAG_IMMUTABLE)
            sm.sendMultipartTextMessage(nr, null, teile, ArrayList(teile.map { gesendet }), null)
        } catch (e: Exception) {
            empfaenger?.let { runCatching { activity.unregisterReceiver(it) } }
            fertig(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Viele SMS auf einmal (Tourenplanung: jede Person ihre Tour). Einmal bestätigen, dann alle senden.
     * Ergebnis je Empfänger in derselben Reihenfolge: ok oder Fehler – die Oberfläche bietet dann WhatsApp an.
     */
    suspend fun smsMassen(liste: List<Pair<String, String>>): JsonArray {
        if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) throw IOException("Dieses Gerät kann keine SMS senden.")
        val ja = suspendCancellableCoroutine<Boolean> { k ->
            AlertDialog.Builder(activity).setTitle("${liste.size} SMS senden?")
                .setMessage("Jede Person bekommt ihre eigene Tour per SMS (normale SMS-Kosten).")
                .setPositiveButton("Senden") { _, _ -> if (k.isActive) k.resume(true) }
                .setNegativeButton("Abbrechen") { _, _ -> if (k.isActive) k.resume(false) }
                .setOnCancelListener { if (k.isActive) k.resume(false) }
                .show()
        }
        if (!ja) throw IOException("Abgebrochen")
        if (!smsErlaubt() && !suspendCancellableCoroutine<Boolean> { k -> activity.frageSms { if (k.isActive) k.resume(it) } }) throw IOException("Ohne SMS-Berechtigung geht der Versand nicht.")
        val offen = liste.map { (nr, text) -> CompletableDeferred<String>().also { d -> sendeEine(nr, text) { d.complete(it) } } }
        return buildJsonArray {
            for (d in offen) {
                val f = withTimeoutOrNull(90_000) { d.await() } ?: "keine Rückmeldung vom Netz"
                add(buildJsonObject { put("ok", f.isEmpty()); put("fehler", f) })
            }
        }
    }

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

    companion object {
        /** Fehlercode von Android → verständlicher Grund (SmsManager.RESULT_ERROR_…). */
        fun smsFehlerText(code: Int) = when (code) {
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "allgemeiner Fehler – bei Dual-SIM in den Android-Einstellungen eine Standard-SIM für SMS festlegen (nicht „Immer fragen“), sonst Guthaben/Tarif prüfen"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "Mobilfunk aus (Flugmodus?)"
            SmsManager.RESULT_ERROR_NO_SERVICE, SmsManager.RESULT_RADIO_NOT_AVAILABLE -> "kein Netz"
            SmsManager.RESULT_ERROR_NULL_PDU -> "Nachricht konnte nicht erstellt werden"
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "Sendelimit von Android erreicht – später erneut"
            SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED, SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "Kurzwahlnummer nicht erlaubt"
            SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> "Rufnummernbeschränkung (FDN) der SIM"
            else -> "Fehlercode $code von Android"
        }
        private val NUMMER = Regex("^[+0-9 ()/-]{3,}$")

        /** Empfänger und Text aus einer sms:-Adresse der Oberfläche (sms:+49…,+49…?body=…); null, wenn etwas fehlt. */
        fun smsZiel(adresse: String): Pair<List<String>, String>? {
            val rest = adresse.substringAfter(':')
            val nummern = Uri.decode(rest.substringBefore('?')).split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
            val text = rest.substringAfter('?', "").split('&').firstOrNull { it.startsWith("body=") }?.let { Uri.decode(it.removePrefix("body=")) }.orEmpty()
            return if (nummern.isEmpty() || nummern.any { !NUMMER.matches(it) } || text.isBlank()) null else nummern to text
        }
    }
}
