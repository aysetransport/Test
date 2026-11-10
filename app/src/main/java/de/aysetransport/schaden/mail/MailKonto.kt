package de.aysetransport.schaden.mail

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class Sicherheit(val label: String) {
    SSL("SSL/TLS"),
    STARTTLS("STARTTLS"),

    /** Nur für Tests mit einem lokalen Mailserver – in der Oberfläche nicht wählbar. */
    KEINE("Unverschlüsselt"),
}

/** Server-Einstellungen eines Postfachs. */
data class MailServer(
    val imapHost: String,
    val imapPort: Int = 993,
    val imapSicherheit: Sicherheit = Sicherheit.SSL,
    val smtpHost: String,
    val smtpPort: Int = 465,
    val smtpSicherheit: Sicherheit = Sicherheit.SSL,
)

/** Voreinstellungen gängiger Anbieter; „Eigener Server“ lässt alles frei eintragen. */
enum class Anbieter(val label: String, val server: MailServer?) {
    IONOS("IONOS (1&1)", MailServer("imap.ionos.de", smtpHost = "smtp.ionos.de")),
    STRATO("STRATO", MailServer("imap.strato.de", smtpHost = "smtp.strato.de")),
    GMX("GMX", MailServer("imap.gmx.net", smtpHost = "mail.gmx.net")),
    WEBDE("WEB.DE", MailServer("imap.web.de", smtpHost = "smtp.web.de", smtpPort = 587, smtpSicherheit = Sicherheit.STARTTLS)),
    TONLINE("Telekom (T-Online)", MailServer("secureimap.t-online.de", smtpHost = "securesmtp.t-online.de")),
    EIGENER("Eigener Server", null),
}

/** Das eingerichtete Postfach. Das Passwort steht hier nicht – es liegt verschlüsselt im [PasswortTresor]. */
data class MailKonfig(
    val adresse: String,
    val benutzer: String,
    val server: MailServer,
    /** Absendername, z. B. die Firma aus den Einstellungen. */
    val name: String = "",
)

data class MailZustand(
    val konfig: MailKonfig? = null,
    val letzterAbruf: Long = 0,
    val fehler: String = "",
    /** IMAP: bis zu dieser UID ist der Posteingang abgerufen; gilt nur für [uidValidity]. */
    val letzteUid: Long = 0,
    val uidValidity: Long = 0,
)

/** Verschlüsselt das Postfach-Passwort. */
interface PasswortTresor {
    fun verschluessele(klartext: String): String
    fun entschluessele(geheim: String): String
}

/**
 * Schlüssel im Android-Schlüsselspeicher (AES-GCM). Er verlässt das Gerät nie; ohne ihn ist das
 * gespeicherte Passwort nicht lesbar – auch nicht aus einer Kopie der App-Daten.
 */
class KeystoreTresor : PasswortTresor {
    private val alias = "schadensfaelle-mailpasswort"

    private fun schluessel(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    override fun verschluessele(klartext: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, schluessel()) }
        val daten = c.iv + c.doFinal(klartext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(daten, Base64.NO_WRAP)
    }

    override fun entschluessele(geheim: String): String {
        val daten = Base64.decode(geheim, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, schluessel(), GCMParameterSpec(128, daten, 0, 12)) }
        return c.doFinal(daten, 12, daten.size - 12).toString(Charsets.UTF_8)
    }
}

private val Context.mailStore: DataStore<Preferences> by preferencesDataStore(name = "mail")

class MailSpeicher(private val store: DataStore<Preferences>, private val tresor: PasswortTresor) {

    constructor(context: Context) : this(context.applicationContext.mailStore, KeystoreTresor())

    val zustand: Flow<MailZustand> = store.data.map { p ->
        val adresse = p[ADRESSE]
        MailZustand(
            konfig = adresse?.let {
                MailKonfig(
                    adresse = it,
                    benutzer = p[BENUTZER] ?: it,
                    name = p[NAME] ?: "",
                    server = MailServer(
                        imapHost = p[IMAP_HOST] ?: "",
                        imapPort = p[IMAP_PORT] ?: 993,
                        imapSicherheit = Sicherheit.entries.firstOrNull { s -> s.name == p[IMAP_SICHERHEIT] } ?: Sicherheit.SSL,
                        smtpHost = p[SMTP_HOST] ?: "",
                        smtpPort = p[SMTP_PORT] ?: 465,
                        smtpSicherheit = Sicherheit.entries.firstOrNull { s -> s.name == p[SMTP_SICHERHEIT] } ?: Sicherheit.SSL,
                    ),
                )
            },
            letzterAbruf = p[LETZTER_ABRUF] ?: 0,
            fehler = p[FEHLER] ?: "",
            letzteUid = p[LETZTE_UID] ?: 0,
            uidValidity = p[UID_VALIDITY] ?: 0,
        )
    }

    suspend fun aktuell(): MailZustand = zustand.first()

    suspend fun passwort(): String? = store.data.first()[PASSWORT]?.let { runCatching { tresor.entschluessele(it) }.getOrNull() }

    suspend fun speichere(konfig: MailKonfig, passwort: String) = store.edit {
        if (it[ADRESSE] != konfig.adresse || it[IMAP_HOST] != konfig.server.imapHost) {
            it.remove(LETZTE_UID)
            it.remove(UID_VALIDITY)
        }
        it[ADRESSE] = konfig.adresse
        it[BENUTZER] = konfig.benutzer
        it[NAME] = konfig.name
        it[IMAP_HOST] = konfig.server.imapHost
        it[IMAP_PORT] = konfig.server.imapPort
        it[IMAP_SICHERHEIT] = konfig.server.imapSicherheit.name
        it[SMTP_HOST] = konfig.server.smtpHost
        it[SMTP_PORT] = konfig.server.smtpPort
        it[SMTP_SICHERHEIT] = konfig.server.smtpSicherheit.name
        it[PASSWORT] = tresor.verschluessele(passwort)
        it.remove(FEHLER)
    }

    suspend fun abgerufen(letzteUid: Long, uidValidity: Long, zeitpunkt: Long) = store.edit {
        it[LETZTE_UID] = letzteUid
        it[UID_VALIDITY] = uidValidity
        it[LETZTER_ABRUF] = zeitpunkt
        it.remove(FEHLER)
    }

    suspend fun fehler(text: String) = store.edit { it[FEHLER] = text }

    suspend fun trenne() = store.edit { it.clear() }

    private companion object {
        val ADRESSE = stringPreferencesKey("adresse")
        val BENUTZER = stringPreferencesKey("benutzer")
        val NAME = stringPreferencesKey("name")
        val IMAP_HOST = stringPreferencesKey("imap_host")
        val IMAP_PORT = intPreferencesKey("imap_port")
        val IMAP_SICHERHEIT = stringPreferencesKey("imap_sicherheit")
        val SMTP_HOST = stringPreferencesKey("smtp_host")
        val SMTP_PORT = intPreferencesKey("smtp_port")
        val SMTP_SICHERHEIT = stringPreferencesKey("smtp_sicherheit")
        val PASSWORT = stringPreferencesKey("passwort")
        val LETZTER_ABRUF = longPreferencesKey("letzter_abruf")
        val FEHLER = stringPreferencesKey("fehler")
        val LETZTE_UID = longPreferencesKey("letzte_uid")
        val UID_VALIDITY = longPreferencesKey("uid_validity")
    }
}
