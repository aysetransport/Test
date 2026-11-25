# Windows-App und Android-App

Beide Apps zeigen dieselbe Oberfläche (`web/index.html`) und ergänzen, was ein Browser nicht kann. Die Oberfläche
erkennt die Apps an `window.desktop` (Windows und Android stellen dieselbe Schnittstelle bereit; Android über
`app/src/main/assets/android.js`, das die Aufrufe an die Kotlin-Brücke weiterreicht).

## Gemeinsame Schnittstelle `window.desktop`

| Teil | Funktion |
|---|---|
| `mail.*` | Postfach direkt per IMAP/SMTP: `anbieter`, `konto(art)`, `einrichten(konfig, passwort, art)`, `trennen`, `sende`, `ruf(befehl, daten, art)` mit den Befehlen `ordner`, `stand`, `lade`, `suche`, `aktion`, `ordnerAktion`, `anhang`, `quelle`, `sende` (Schnittstelle 2) |
| `pdf(html)` | HTML → PDF (Rechnungen, Briefe, Tagesplan) |
| `speichere(name, base64)` | Datei speichern (Speichern-Dialog bzw. Android „Dateien“) |
| `oeffneDatei(name, bytes)`, `oeffneExtern(url)` | Datei bzw. Link mit dem Programm des Geräts öffnen |
| `benachrichtige(titel, text, ziel)` | Benachrichtigung; Antippen öffnet `ziel` in der App |
| `web.*` | Update der Oberfläche: `info`, `installieren`, `ok` (Start war sauber), `zuruecksetzen` |
| `app.*` | Update der App selbst: `pruefen`, `installieren` |
| nur Windows: `fenster.*` | Titelleiste, Taskleisten-Zähler, Zoom, **neue Fenster/Loskoppeln** (`neu(bereich, ziel, allein)`) |
| nur Windows: `scan.*` | Scanner (WIA) und Texterkennung |
| nur Android: SMS | `sms:massen` – SMS direkt senden (mit Erlaubnis), sonst SMS-App |

Mail-Passwörter erreichen die Oberfläche nie; sie liegen verschlüsselt in der App.

## Windows-App (`desktop/`)

| Datei | Aufgabe |
|---|---|
| `main.js` | Hauptprozess: Fenster (Haupt- und Unterfenster, Lage merken), Protokoll `app://schaden/…` (liefert die Oberfläche bzw. ihr geprüftes Update), IPC-Kanäle (`behandle('mail:ruf', …)` usw.), Rechtsklick-Menü mit Rechtschreibung, Tastenkürzel (Zoom, F11), Benachrichtigungen, App-Update (electron-updater) |
| `preload.js` | Einzige Brücke zur Oberfläche (`window.desktop`) – nur diese Funktionen sind erreichbar |
| `mail.js` | IMAP (imapflow) und SMTP (nodemailer), Fehler in verständliche Meldungen übersetzt |
| `scan.js` | Scannen über PowerShell + WIA (auch Einzug/Stapel), Texterkennung offline (tesseract.js, deutsch) |
| `webupdate.js` | Update der Oberfläche: lädt neue Dateien von GitHub Pages, prüft SHA-256, fällt bei Startproblemen zurück |
| `scripts/vorbereiten.js` | Kopiert die Oberfläche aus `web/` nach `desktop/app/` (vor jedem Build/Test) |
| `test/` | `mail.test.js` (gegen GreenMail), `webupdate.test.js`, `app.test.js` (gestartete App mit Playwright) |

**Speicherorte** (Electron-`userData`-Ordner, unter Windows `%APPDATA%\Schadensfälle\` – der App-Name stammt noch aus der Anfangszeit, siehe `productName` in `desktop/package.json`; in Tests `SCHADEN_DATEN`):
`mailkonto*.json` (Konten, Passwort über Windows verschlüsselt), `fenster.json` (Fensterlage), `webupdate/`
(geladene Oberfläche + `zustand.json`), sowie der Browser-Speicher der Oberfläche (localStorage/IndexedDB).

**Fenster:** Das erste Fenster ist das Hauptfenster (gleicht ab, ruft Mails ab). Weitere Fenster: Strg+Umschalt+N,
Mittelklick/Strg-Klick auf Bereiche, „Neues Fenster“ in der Seitenleiste, **Loskoppeln** (⧉-Symbol) für einzelne
Datensätze und Mails (`?neben=1&allein=1#/mail/…`). Schließen des Hauptfensters schließt alle.

**Installation/Updates:** Setup ohne Administratorrechte, nicht signiert (SmartScreen: „Weitere Informationen →
Trotzdem ausführen“). Updates der App kommen vom Release `windows-app` in `aysetransport/schaden`; die Version steht in
`desktop/package.json` und muss für ein Update steigen.

## Android-App (`app/`)

| Datei | Aufgabe |
|---|---|
| `MainActivity.kt` | Eine Activity mit WebView; liefert `https://appassets.androidplatform.net/…` aus (`WebInhalt`), Zurück-Taste, Dateiauswahl/Kamera, Benachrichtigungen, System-/Navigationsleiste hell/dunkel |
| `WebInhalt.kt` | Liefert die Dateien der Oberfläche: geprüftes Update (`WebUpdate`) oder die mitgelieferten Assets |
| `Bruecke.kt` | Nimmt Aufrufe der Oberfläche (`window.android…`, über `android.js`) entgegen und verteilt sie (`mail:*`, `pdf:erzeugen`, `datei:*`, `web:*`, `app:*`, `sms:massen` …) |
| `Geraet.kt` | Geräte-Funktionen: Dateien öffnen/teilen, PDF/Druck (über eine unsichtbare WebView), SMS, Benachrichtigungen |
| `WebUpdate.kt` | Update der Oberfläche (wie `desktop/webupdate.js`; `NATIV_API` = Schnittstellenstand der App) |
| `AppUpdate.kt` | Update der App: lädt `android-update.json` + APK vom Release `android-app` in `aysetransport/schaden`, prüft SHA-256, öffnet den Installationsdialog |
| `mail/Postfach.kt`, `mail/MailKonten.kt` | IMAP/SMTP mit JavaMail; Konten in SharedPreferences, Passwort AES-GCM mit Schlüssel im Android-Schlüsselspeicher |
| `assets/android.js` | Stellt `window.desktop` in der WebView bereit (gleiche Schnittstelle wie Windows) |
| `src/test/` | Robolectric-Tests (u. a. Postfach gegen GreenMail, Bildschirmfotos mit Roborazzi) |

**Installation/Updates:** APK `Ayse-CRM.apk` aus dem Release; Android verlangt beim ersten Mal „Apps aus dieser
Quelle zulassen“ und bestätigt jedes Update per Dialog. Die APK muss **immer mit demselben Schlüssel** signiert sein
(Secrets `SIGNATUR_*`), sonst lässt sie sich nicht über die installierte installieren.

## Update der Oberfläche in den Apps (beide gleich)

1. Die App liest `https://aysetransport.github.io/schaden/app-update.json` (Version, Stand, `minNativ`, Liste der
   Dateien mit SHA-256).
2. Ist der Stand neuer und `minNativ` ≤ Schnittstellenstand der App, lädt sie alle Dateien und prüft jede Prüfsumme.
3. Beim nächsten Start (oder „Jetzt neu laden“) gilt die neue Fassung. Meldet die Oberfläche nicht innerhalb von zwei
   Starts „ok“ (`web.ok()`), fällt die App auf die mitgelieferte Fassung zurück.
4. Verlangt eine neue Oberfläche eine neuere App (`minNativ`), wird stattdessen ein App-Update angeboten.
