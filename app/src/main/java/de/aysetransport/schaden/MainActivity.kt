package de.aysetransport.schaden

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import de.aysetransport.schaden.mail.AndroidTresor
import de.aysetransport.schaden.mail.MailKonten
import java.io.File

/**
 * Ayse CRM: zeigt die Web-App (dieselbe wie im Browser und unter Windows) und stellt ihr über
 * [Bruecke] Mail, Kamera, Dateien, PDF, Drucken und Benachrichtigungen zur Verfügung.
 */
class MainActivity : ComponentActivity() {

    lateinit var webView: WebView
        private set
    private lateinit var geraet: Geraet
    private var dateiRueckruf: ValueCallback<Array<Uri>>? = null
    private var kameraDatei: File? = null
    private var offenesZiel: String? = null
    private var zielBereit = false

    private fun zeigeStoerung() {
        setContentView(android.widget.TextView(this).apply {
            text = "Die Anzeige wurde vom System mehrfach beendet (zu wenig Speicher?).\n\nBitte die App schließen und neu öffnen. Deine Daten bleiben erhalten."
            setPadding(48, 160, 48, 48)
            textSize = 18f
        })
    }

    private val dateiWahl = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { ergebnis ->
        val rueckruf = dateiRueckruf ?: return@registerForActivityResult
        dateiRueckruf = null
        val uris = mutableListOf<Uri>()
        if (ergebnis.resultCode == RESULT_OK) {
            val daten = ergebnis.data
            daten?.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri }
            if (uris.isEmpty()) daten?.data?.let { uris += it }
            val foto = kameraDatei
            if (uris.isEmpty() && foto != null && foto.length() > 0) uris += FileProvider.getUriForFile(this, "$packageName.fileprovider", foto)
        }
        rueckruf.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
    }

    private val benachrichtigungsFrage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var nachSmsFrage: ((Boolean) -> Unit)? = null
    private val smsFrage = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { erg -> nachSmsFrage?.invoke(erg.values.all { it }); nachSmsFrage = null }

    /** Fragt einmalig nach der SMS-Berechtigung (Geraet.sms). */
    fun frageSms(weiter: (Boolean) -> Unit) { nachSmsFrage = weiter; smsFrage.launch(Geraet.SMS_RECHTE) }
    private var schonGefragt = false

    fun frageBenachrichtigungen() {
        if (schonGefragt || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        schonGefragt = true
        benachrichtigungsFrage.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val wurzel = FrameLayout(this)
        webView = WebView(this)
        wurzel.addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(wurzel)
        ViewCompat.setOnApplyWindowInsetsListener(wurzel) { v, insets ->
            val rand = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(rand.left, rand.top, rand.right, rand.bottom)
            WindowInsetsCompat.CONSUMED
        }
        systemleisten(resources.configuration)
        geraet = Geraet(this, wurzel)

        val webUpdate = WebUpdate(applicationContext).also { it.beimStart() }
        val lader = WebInhalt.lader(this, webUpdate)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
        }
        // Die Anzeige möglichst nicht beenden, solange die App im Vordergrund ist.
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        WebView.setWebContentsDebuggingEnabled(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, anfrage: WebResourceRequest): WebResourceResponse? =
                lader.shouldInterceptRequest(anfrage.url)

            /**
             * Android beendet die Darstellung einer WebView mitunter (z. B. bei Speichermangel im Hintergrund).
             * Ohne diese Behandlung würde die ganze App mit abstürzen – so wird die Ansicht einfach neu aufgebaut.
             * Die Daten liegen im Speicher der WebView (localStorage, IndexedDB) und bleiben erhalten.
             */
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
                val jetzt = System.currentTimeMillis()
                neustarts.removeAll { jetzt - it > 60_000 }
                neustarts += jetzt
                if (neustarts.size <= 3) recreate() else zeigeStoerung()
                return true
            }

            override fun shouldOverrideUrlLoading(view: WebView, anfrage: WebResourceRequest): Boolean {
                val url = anfrage.url
                if (url.host == WebInhalt.HOST) return false
                // Telefon, Mail und Webseiten öffnen die Apps des Geräts.
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                return true
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, rueckruf: ValueCallback<Array<Uri>>, parameter: FileChooserParams): Boolean {
                dateiRueckruf?.onReceiveValue(null)
                dateiRueckruf = rueckruf
                return try {
                    dateiWahl.launch(waehler(parameter))
                    true
                } catch (e: Exception) {
                    dateiRueckruf = null
                    false
                }
            }
        }
        val konten = MailKonten(this, AndroidTresor())
        webView.addJavascriptInterface(
            Bruecke(
                bereich = lifecycleScope,
                konten = konten,
                geraet = { geraet },
                antworte = { js -> webView.evaluateJavascript(js, null) },
                zielBereit = { zielBereit = true; offenesZiel?.let { zeigeZiel(it) } },
                webUpdate = webUpdate,
                appUpdate = AppUpdate(this),
            ),
            "AndroidBruecke",
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("window.__androidZurueck ? window.__androidZurueck() : false") { erledigt ->
                    if (erledigt != "true") { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
            }
        })

        offenesZiel = intent?.getStringExtra(ZIEL)
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) webView.loadUrl(WebInhalt.START)
    }

    /** Dateiauswahl der Seite; bei Bildern zusätzlich die Kamera. */
    private fun waehler(p: WebChromeClient.FileChooserParams): Intent {
        val auswahl = p.createIntent().apply {
            if (p.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        val typen = p.acceptTypes.filter { it.isNotBlank() }
        val mitKamera = typen.isEmpty() || typen.any { it.startsWith("image") || it == "*/*" }
        val chooser = Intent.createChooser(auswahl, "Datei oder Foto")
        kameraDatei = null
        if (mitKamera) {
            val ordner = File(cacheDir, "kamera").apply { mkdirs() }
            ordner.listFiles()?.forEach { it.delete() }
            val foto = File(ordner, "Foto_${System.currentTimeMillis()}.jpg")
            kameraDatei = foto
            val ziel = FileProvider.getUriForFile(this, "$packageName.fileprovider", foto)
            val kamera = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, ziel)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (kamera.resolveActivity(packageManager) != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(kamera))
        }
        return chooser
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(ZIEL)?.let { if (zielBereit) zeigeZiel(it) else offenesZiel = it }
    }

    private fun zeigeZiel(ziel: String) {
        offenesZiel = null
        webView.evaluateJavascript("window.__androidZiel && window.__androidZiel(${kotlinx.serialization.json.JsonPrimitive(ziel)})", null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    /** Systemleisten wie die Oberfläche: helles Design → dunkle Symbole, dunkles → helle; Grund wie die Seite (folgt dem Gerät). */
    private fun systemleisten(konfig: android.content.res.Configuration) {
        val hell = (konfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES
        window.decorView.setBackgroundColor(if (hell) 0xFFF2F2F7.toInt() else 0xFF000000.toInt())
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = hell
            isAppearanceLightNavigationBars = hell
        }
    }

    override fun onConfigurationChanged(neu: android.content.res.Configuration) {
        super.onConfigurationChanged(neu)
        systemleisten(neu)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) runCatching { (webView.parent as? android.view.ViewGroup)?.removeView(webView); webView.destroy() }
        super.onDestroy()
    }

    companion object {
        const val ZIEL = "ziel"
        /** Zeitpunkte der letzten Neustarts der Anzeige (gegen eine Endlosschleife). */
        private val neustarts = mutableListOf<Long>()
    }
}
