package de.aysetransport.schaden.mail

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Verschlüsselt das Mail-Passwort (auf dem Gerät: Android-Schlüsselspeicher; in Tests austauschbar). */
interface Tresor {
    fun verschluessele(klartext: String): String
    fun entschluessele(geheim: String): String
}

/** AES-GCM mit einem Schlüssel, der den Android-Schlüsselspeicher nie verlässt. */
class AndroidTresor : Tresor {
    private val alias = "ayse-crm-mail"

    private fun schluessel(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    override fun verschluessele(klartext: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, schluessel()) }
        val daten = c.doFinal(klartext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(c.iv) + ":" + Base64.getEncoder().encodeToString(daten)
    }

    override fun entschluessele(geheim: String): String {
        val (iv, daten) = geheim.split(':', limit = 2).map { Base64.getDecoder().decode(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, schluessel(), GCMParameterSpec(128, iv)) }
        return String(c.doFinal(daten), Charsets.UTF_8)
    }
}

/**
 * Die zwei Postfächer der App: SCHADEN (Schadensfälle) und ALLGEMEIN (alles andere) – wie in der Windows-App.
 * Die Oberfläche bekommt die Einstellungen ohne Passwort zu sehen.
 */
class MailKonten(context: Context, private val tresor: Tresor, private val postfach: Postfach = Postfach()) {
    private val speicher = context.getSharedPreferences("mailkonten", Context.MODE_PRIVATE)

    private fun art(a: String?) = if (a == "ALLGEMEIN") "ALLGEMEIN" else "SCHADEN"

    private fun gespeichert(a: String?): Pair<JsonObject, String>? {
        val roh = speicher.getString(art(a), null) ?: return null
        val j = runCatching { Json.parseToJsonElement(roh).jsonObject }.getOrNull() ?: return null
        val geheim = (j["passwort"] as? JsonPrimitive)?.content ?: return null
        return JsonObject(j - "passwort") to geheim
    }

    /** Einstellungen ohne Passwort – oder `null`, wenn kein Konto eingerichtet ist. */
    fun konto(a: String?): JsonElement = gespeichert(a)?.first ?: JsonNull

    private fun zugang(a: String?): Pair<MailKonfig, String> {
        val (konfig, geheim) = gespeichert(a) ?: throw MailFehler("Kein E-Mail-Konto eingerichtet")
        val passwort = runCatching { tresor.entschluessele(geheim) }
            .getOrElse { throw MailFehler("Das gespeicherte Passwort ist nicht mehr lesbar – bitte das Konto neu einrichten.", anmeldung = true) }
        return MailKonfig.aus(konfig) to passwort
    }

    /** Prüft die Anmeldung bei IMAP und SMTP und speichert das Konto erst dann. */
    fun einrichten(konfig: JsonObject, passwort: String, a: String?): JsonObject {
        postfach.pruefe(MailKonfig.aus(konfig), passwort)
        val ohne = JsonObject(konfig - "passwort")
        speicher.edit { putString(art(a), JsonObject(ohne + ("passwort" to JsonPrimitive(tresor.verschluessele(passwort)))).toString()) }
        return ohne
    }

    fun trennen(a: String?): Boolean { speicher.edit { remove(art(a)) }; return true }

    fun sende(entwurf: JsonObject, a: String?): String = zugang(a).let { (k, pw) -> postfach.sende(k, pw, entwurf) }


    /** Schnittstelle 2: ordner, stand, lade, suche, aktion, ordnerAktion, anhang, quelle, sende. */
    fun ruf(befehl: String, daten: JsonObject, a: String?): JsonElement = zugang(a).let { (k, pw) -> postfach.ruf(k, pw, befehl, daten) }
}
