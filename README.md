# Ayse CRM

CRM für Ayse Transport: Firmen und Auftraggeber, Angebote und Rechnungen (mit E-Rechnung), Postfach,
Tourenplanung, Personal, Fuhrpark, Zeiterfassung, Schadensfälle und Statistik.

Eine Oberfläche, drei Wege, sie zu nutzen:

| | Ordner | Technik |
|---|---|---|
| Web-App (Browser, installierbar) | `web/` | eine Datei `index.html`, ohne Build |
| Windows-App | `desktop/` | Electron: Fenster, E-Mail per IMAP/SMTP, PDF, Benachrichtigungen |
| Android-App | `app/` | WebView mit Brücke (`android.js`): E-Mail, PDF, Drucken, Benachrichtigungen |
| Cloud | `cloud/` | Google Apps Script, Daten in Google Drive (`Schadensfaelle/daten.json`) |

Alle Geräte gleichen sich über das Cloud-Skript ab. Ohne Cloud arbeitet jedes Gerät für sich.

**Dokumentation für Betreuung und Fehlersuche:** [`docs/`](docs/README.md) – Aufbau, Datenmodell, Cloud, Apps,
Veröffentlichen, Tests und vor allem [Fehlerbehebung](docs/fehlerbehebung.md).

## Cloud einrichten (einmalig)

1. [script.google.com](https://script.google.com) → **Neues Projekt**, `cloud/Code.gs` und `cloud/appsscript.json` einfügen.
2. **Bereitstellen → Neue Bereitstellung → Web-App**: *Ausführen als: Ich*, *Zugriff: Jeder*.
3. Die Adresse (endet auf `/exec`) in der App unter ⚙ → Cloud eintragen und eine PIN festlegen.
   Später lassen sich unter ⚙ → Benutzer persönliche Logins anlegen (Admin, Disposition, Fahrer).

Danach aktualisiert sich das Skript selbst (⚙ → Cloud-Skript, einmalig die Apps Script API einschalten).
PIN vergessen: im Skript-Editor `pinZuruecksetzen` ausführen.

## Updates

- **Oberfläche:** Windows- und Android-App laden neue Fassungen der Oberfläche selbst nach (`web/app-update.json`).
- **App selbst (nativer Teil):** ebenfalls in der App. Die Workflows legen nach jedem Lauf auf dem Standard-Branch
  den neuen Installer bzw. die neue APK in [`aysetransport/schaden`](https://github.com/aysetransport/schaden/releases)
  ab (Releases `windows-app` und `android-app`). Windows lädt das Update im Hintergrund (electron-updater) und
  installiert es beim Beenden oder auf „Jetzt installieren“. Android lädt die APK und öffnet den Installationsdialog.
  Diesen muss der Benutzer bestätigen, das schreibt Android ohne Play Store so vor (beim ersten Mal zusätzlich „Apps aus
  dieser Quelle zulassen“).
  Einmalig nötig: unter *Settings → Secrets and variables → Actions* das Secret `SCHADEN_TOKEN` anlegen
  (Fine-grained Token, nur Repository `aysetransport/schaden`, *Contents: Read and write*). Für die Android-APK zusätzlich
  `SIGNATUR_BASE64` (Schlüssel als Base64), `SIGNATUR_PASSWORT` und `SIGNATUR_ALIAS` – der Schlüssel liegt nur dort,
  nie im (öffentlichen) Repository. Ohne sie wird die APK gebaut, aber nicht veröffentlicht.
  Die Windows-Version kommt aus `desktop/package.json` und muss für ein Update steigen. Die Android-`versionCode`
  zählt im Workflow automatisch hoch.
- **Veröffentlichen:** `node tools/app-update.js` ausführen (Version kommt aus `desktop/package.json`), dann
  den Inhalt von `web/` (ohne `test/`) ins Repository [`aysetransport/schaden`](https://github.com/aysetransport/schaden)
  übernehmen → https://aysetransport.github.io/schaden/. Bei Änderungen an zwischengespeicherten Dateien
  `SPEICHER` in `web/sw.js` erhöhen.
- **Übersetzungen:** `web/sprachen.json` (Schlüssel = deutscher Text, Zahlen als `#`).

## Installieren

- **Windows:** Release [„Windows-App“ in `aysetransport/schaden`](https://github.com/aysetransport/schaden/releases/tag/windows-app)
  (öffentlich, sobald `SCHADEN_TOKEN` hinterlegt ist), sonst Release **„Windows-App“** im Repository (Tag `windows-app`, vom Workflow *Windows-App* nach jedem
  erfolgreichen Lauf auf dem Standard-Branch erneuert). Nicht signiert: SmartScreen
  → „Weitere Informationen → Trotzdem ausführen“. Installation ohne Administratorrechte.
- **Android:** Release **„Android-App“** im Repository (Tag `android-app`, `Ayse-CRM.apk`, nach jedem Lauf auf dem
  Standard-Branch erneuert). Signiert mit dem festen Schlüssel aus den Secrets, damit sich
  jede neue APK über die installierte installieren lässt. Den Schlüssel nie wechseln (sonst einmal deinstallieren und
  neu installieren); eine Sicherung gehört außerhalb von GitHub (z. B. Passwort-Manager).

## Entwickeln und testen

```bash
node --test cloud/test/cloud.test.js                       # Cloud-Skript in nachgebildeter Google-Umgebung
node --test web/test/web.test.js                           # Web-App im Browser (Playwright)
cd desktop && npm ci && xvfb-run -a npm test               # Windows-App (Mail gegen GreenMail, gestartete App)
./gradlew testDebugUnitTest                                # Android (Robolectric, GreenMail)
python3 tools/erechnung-pruefen.py rechnung.xml <regeln>   # XRechnung: Schema + Schematron (KoSIT)
```

Die Tests der Web- und Windows-App laufen gegen das echte `cloud/Code.gs`. Mit `ERECHNUNG_REGELN=<Ordner>`
prüft der Web-Test die erzeugten E-Rechnungen zusätzlich gegen die offiziellen Regeln.

## Daten und Datenschutz

- Ohne Cloud liegen die Daten nur auf dem Gerät, mit Cloud zusätzlich im Google Drive des Kontos, das das
  Skript bereitgestellt hat (mit täglicher Sicherung der letzten 14 Tage).
- PIN und Passwörter speichert das Skript nur als Prüfsumme. Mail-Passwörter bleiben verschlüsselt auf dem
  Gerät (Windows-Konto bzw. Android-Schlüsselspeicher).
- Fahrer sehen nur ihre eigenen Touren, Zeiten und Aufgaben; das setzt die Cloud durch, nicht nur die Oberfläche.
- Alles, was nach außen geht (Rechnungen, Briefe, Kunden-Nachrichten), bleibt deutsch, auch wenn die
  Oberfläche übersetzt ist.

## SMS (Android)

Die SMS-Knöpfe (Lieferavis, Touren, Team) senden auf Android-Geräten mit Mobilfunk direkt aus der App. Vorher
erscheint die Nachricht zur Bestätigung, mit „In SMS-App“ als Ausweg. Beim ersten Mal fragt Android nach der
Berechtigung. Die SMS gehen über die SIM des Geräts und kosten wie normale SMS. Ohne Berechtigung oder ohne Mobilfunk
öffnet sich wie bisher die SMS-App. (Im Play Store wäre `SEND_SMS` für eine solche App nicht erlaubt. Die APK wird
direkt verteilt.)

## Grenzen

- E-Mail: Konten mit Pflicht-Anmeldung über OAuth (z. B. Microsoft 365) werden nicht unterstützt; manche
  Anbieter verlangen ein App-Passwort.
- Abgleich: Ändern zwei Geräte denselben Datensatz fast gleichzeitig, gilt die spätere Änderung des ganzen Datensatzes.
