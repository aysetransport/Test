package de.aysetransport.schaden

import android.content.Context
import de.aysetransport.schaden.cloud.CloudAbgleich
import de.aysetransport.schaden.cloud.CloudPlaner
import de.aysetransport.schaden.cloud.CloudSpeicher
import de.aysetransport.schaden.data.AnlagenSpeicher
import de.aysetransport.schaden.mail.MailDienst
import de.aysetransport.schaden.mail.MailPostfach
import de.aysetransport.schaden.mail.MailSpeicher
import de.aysetransport.schaden.data.BackupManager
import de.aysetransport.schaden.data.CrmRepository
import de.aysetransport.schaden.data.model.MailKontoArt
import de.aysetransport.schaden.mail.MailAnhangSpeicher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.io.File
import java.time.LocalDate
import de.aysetransport.schaden.domain.faelligkeit
import de.aysetransport.schaden.ui.komponenten.Hinweiszahlen
import de.aysetransport.schaden.data.EinstellungenSpeicher
import de.aysetransport.schaden.data.SchadensfallRepository
import de.aysetransport.schaden.data.db.SchadensfallDatenbank
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hält die langlebigen Objekte der App an einer Stelle zusammen.
 *
 * Datenbank und Einstellungen sind überschreibbar, damit Tests sie durch flüchtige
 * Varianten ersetzen können.
 */
open class AppContainer(context: Context) {
    protected val appContext: Context = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    open val datenbank: SchadensfallDatenbank by lazy { SchadensfallDatenbank.erstelle(appContext) }

    open val einstellungen: EinstellungenSpeicher by lazy { EinstellungenSpeicher(appContext) }

    val anlagenSpeicher: AnlagenSpeicher by lazy { AnlagenSpeicher(appContext) }

    val repository: SchadensfallRepository by lazy {
        SchadensfallRepository(datenbank, anlagenSpeicher, nachAenderung = ::nachAenderung)
    }

    open val cloudSpeicher: CloudSpeicher by lazy { CloudSpeicher(appContext) }

    val cloud: CloudAbgleich by lazy { CloudAbgleich(datenbank, anlagenSpeicher, cloudSpeicher) }

    val crm: CrmRepository by lazy { CrmRepository(datenbank, nachAenderung = ::nachAenderung) }

    /** Postfach für Schadensfälle (das bisherige E-Mail-Konto). */
    open val mailSpeicher: MailSpeicher by lazy { MailSpeicher(appContext, MailKontoArt.SCHADEN) }

    /** Postfach für alles andere (Firmen, Ansprechpartner). */
    open val mailSpeicherAllgemein: MailSpeicher by lazy { MailSpeicher(appContext, MailKontoArt.ALLGEMEIN) }

    open val mailDienst: MailDienst by lazy { MailDienst() }

    val mailAnhaenge: MailAnhangSpeicher by lazy { MailAnhangSpeicher(File(appContext.filesDir, MailAnhangSpeicher.ORDNER)) }

    val postfach: MailPostfach by lazy {
        MailPostfach(
            datenbank, repository,
            mapOf(MailKontoArt.SCHADEN to mailSpeicher, MailKontoArt.ALLGEMEIN to mailSpeicherAllgemein),
            mailDienst, appContext.cacheDir, mailAnhaenge,
        )
    }

    /** Zähler für die Hauptleiste: fällige Termine (CRM und Fälle) und nicht zugeordnete Mails. */
    val hinweiszahlen: Flow<Hinweiszahlen> by lazy {
        combine(crm.beobachteOffeneAufgaben(), repository.beobachteFaelle(), datenbank.crm().beobachteNichtZugeordnet()) { aufgaben, faelle, offen ->
            val heute = LocalDate.now()
            Hinweiszahlen(
                faellig = aufgaben.count { it.faelligAm != null && !it.faelligAm.isAfter(heute) } + faelle.count { it.faelligkeit(heute).istFaellig },
                nichtZugeordnet = offen,
            )
        }
    }

    /** Ist irgendein Postfach eingerichtet? */
    val mailEingerichtet: Flow<Boolean> by lazy {
        combine(mailSpeicher.zustand, mailSpeicherAllgemein.zustand) { a, b -> a.konfig != null || b.konfig != null }
    }

    /** Noch nicht hochgeladene Änderungen (Fälle und CRM). */
    val offeneAenderungen: Flow<Int> by lazy {
        combine(datenbank.dao().beobachteOffeneAenderungen(), datenbank.crm().beobachteOffeneCrmAenderungen()) { a, b -> a + b }
    }

    fun abgleichAnstossen() = nachAenderung()

    /** Nach jeder Änderung den Abgleich anstoßen – sofern eine Cloud verbunden ist. */
    protected open fun nachAenderung() {
        appScope.launch { if (cloudSpeicher.aktuell().angemeldet) CloudPlaner.bald(appContext) }
    }

    val backup: BackupManager by lazy { BackupManager(datenbank, anlagenSpeicher, appContext.cacheDir, mailAnhaenge = mailAnhaenge) }
}
