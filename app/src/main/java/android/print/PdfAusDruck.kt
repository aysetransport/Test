package android.print

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Schreibt das Ergebnis eines [PrintDocumentAdapter] (z. B. einer WebView) ohne Druckdialog in eine PDF-Datei.
 * Liegt im Paket android.print, weil die Rückruf-Klassen dort nur paketweit erzeugt werden können
 * (zur Laufzeit sind sie öffentlich; so machen es viele Apps).
 */
object PdfAusDruck {
    fun schreibe(adapter: PrintDocumentAdapter, attribute: PrintAttributes, datei: File, fertig: (Throwable?) -> Unit) {
        adapter.onStart()
        adapter.onLayout(null, attribute, CancellationSignal(), object : PrintDocumentAdapter.LayoutResultCallback() {
            override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) {
                val ziel = ParcelFileDescriptor.open(datei, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
                adapter.onWrite(arrayOf(PageRange.ALL_PAGES), ziel, CancellationSignal(), object : PrintDocumentAdapter.WriteResultCallback() {
                    override fun onWriteFinished(pages: Array<out PageRange>?) { ziel.close(); adapter.onFinish(); fertig(null) }
                    override fun onWriteFailed(error: CharSequence?) { ziel.close(); adapter.onFinish(); fertig(IllegalStateException(error?.toString() ?: "PDF fehlgeschlagen")) }
                    override fun onWriteCancelled() { ziel.close(); adapter.onFinish(); fertig(IllegalStateException("PDF abgebrochen")) }
                })
            }
            override fun onLayoutFailed(error: CharSequence?) { adapter.onFinish(); fertig(IllegalStateException(error?.toString() ?: "PDF fehlgeschlagen")) }
            override fun onLayoutCancelled() { adapter.onFinish(); fertig(IllegalStateException("PDF abgebrochen")) }
        }, null)
    }
}
