# Fehlerbehebung

Erst das Symptom suchen, dann die Schritte der Reihe nach. Zu jeder Störung steht, woran man sie erkennt, woran
sie liegt und was hilft. Hintergründe: [cloud.md](cloud.md), [apps.md](apps.md),
[updates-und-veroeffentlichen.md](updates-und-veroeffentlichen.md).

## Erste Hilfe (immer zuerst)

1. **Punkt oben rechts ansehen** (Abgleich): grün = Cloud aktuell, orange = gleicht ab / speichert, grau = nur auf
   diesem Gerät, „Anmeldung nötig“ = anmelden, rot mit Text = Fehler (der Text sagt, welcher). Antippen gleicht sofort ab.
2. **App neu laden** (Browser: neu laden; Windows: Strg+R bzw. schließen/öffnen; Android: App ganz schließen).
3. **Version prüfen:** ⚙ → Updates (Version der Oberfläche, Stand, letzter Update-Fehler).
4. **Funktioniert es im Browser** (https://aysetransport.github.io/schaden/)? Ja → Problem liegt an der App
   (Update, Speicher), nein → an Oberfläche oder Cloud.

## Abgleich und Cloud

| Symptom | Ursache | Lösung |
|---|---|---|
| „Anmeldung nötig“ / „Anmeldung abgelaufen“ | Sitzung abgelaufen, Passwort/PIN geändert, Benutzer gesperrt | Punkt antippen und neu anmelden. Benutzer prüfen unter ⚙ → Benutzer (aktiv?) |
| „PIN falsch“ bzw. „GESPERRT … Minuten“ | Falsche PIN, nach 8 Versuchen gesperrt | Warten (Sperre verdoppelt sich je Fehlversuch, max. 7 Tage). PIN vergessen: im Skript-Editor `pinZuruecksetzen` ausführen – die neue PIN steht im Ausführungsprotokoll |
| „PIN_AUS: Die Anmeldung mit der Cloud-PIN ist abgeschaltet“ | Hauptadmin hat PIN-Anmeldung abgeschaltet | Mit Benutzer-Login anmelden. Notfall: `notfallPinAnmeldungEinschalten` im Skript-Editor |
| „Offline · n Änderungen warten“ | Kein Netz oder Cloud nicht erreichbar | Netz prüfen. Änderungen bleiben auf dem Gerät und gehen automatisch raus |
| „Das Cloud-Skript ist veraltet …“ | Oberfläche braucht neuere Cloud (`CLOUD_API` > `API_VERSION`) | ⚙ → Cloud-Skript → Aktualisieren (Hauptadmin). Geht das nicht: von Hand einspielen ([cloud.md](cloud.md), „Neue Version von Hand einspielen“) |
| „Unbekannte Funktion: …“ | Cloud ist älter als die App erwartet | Wie oben: Cloud-Skript aktualisieren |
| „… nicht die erforderliche Berechtigung … UrlFetchApp.fetch“ (Cloud-Update) | Selbst-Update wurde nie freigegeben | Einmalig: Apps Script API einschalten, `Code.gs`/`appsscript.json` einspielen, `selbstUpdateEinrichten` ausführen, neue Version bereitstellen ([cloud.md](cloud.md), Einrichten) |
| „Bitte einmalig die Google Apps Script API einschalten“ | API aus | https://script.google.com/home/usersettings → einschalten |
| „Die neue Version antwortet nicht – die vorherige Version ist wieder aktiv“ | Neuer Code startete nicht (selten) | Erneut versuchen; Ausführungsprotokoll im Skript-Editor (Ausführungen) ansehen |
| „Die Cloud legt ihren Bestand ständig neu an …“ | `daten.json` wird nicht gefunden/gespeichert (Ordner gelöscht, Drive voll, Rechte) | Google Drive prüfen: Ordner `Schadensfaelle`, `daten.json` vorhanden? Speicherplatz frei? |
| Ein Gerät sieht Änderungen eines anderen nicht | Abgleich hängt, falsche Cloud-Adresse | ⚙ → Cloud: auf beiden Geräten dieselbe Adresse? Punkt antippen. Im Browser `…/exec` aufrufen: „Schadensfälle-Cloud läuft“? |
| Fahrer sieht keine Touren | Benutzer nicht mit Mitarbeiter verknüpft, oder Touren älter als 45 Tage | ⚙ → Benutzer → Fahrer → Mitarbeiter zuordnen; Touren müssen ihn als Fahrer/Beifahrer haben |
| Büro sieht keine Lohndaten | So gewollt (Rolle DISPO) | Lohn nur mit Rolle ADMIN |
| Gelöschtes taucht wieder auf / Änderung „verschwindet“ | Zwei Geräte haben denselben Datensatz fast gleichzeitig geändert – die spätere Änderung gewinnt; Geräte-Uhr falsch | Uhrzeit der Geräte prüfen (automatisch stellen). Datensatz erneut bearbeiten |

## Daten verloren oder beschädigt

- **Tägliche Sicherungen** liegen in Google Drive: `Schadensfaelle/sicherungen/daten-JJJJ-MM-TT.json` (14 Tage),
  Vorschüsse in `sicherungen-vorschuesse/`. Zusätzlich hat jedes Gerät seinen eigenen Stand.
- **Einzelne Angaben nachsehen:** Sicherung herunterladen, in einem Editor öffnen und nach Name/Fallnummer suchen.
- **Cloud-Datei kaputt oder gelöscht:**
  1. In Drive `Schadensfaelle/daten.json` (falls vorhanden) umbenennen in `daten-defekt.json` – nicht löschen.
  2. Die passende Sicherung kopieren und als `Schadensfaelle/daten.json` ablegen.
  3. In der Kopie oben den Wert von `"kennung"` ändern (z. B. eine Ziffer austauschen). Dadurch erkennen alle Geräte
     einen neuen Bestand, holen ihn vollständig und laden ihre eigenen Daten zusätzlich hoch (die spätere Änderung
     gewinnt) – so geht nichts verloren, was ein Gerät noch hat.
  4. Im Skript-Editor unter Projekteinstellungen → Skripteigenschaften `DATEN_ID` und `STAND` löschen (das Skript
     sucht die Datei dann neu).
- **Gerät:** ⚙ → Datensicherung → „Sicherung erstellen“ legt eine Datei mit allen Daten des Geräts an (ohne
  Mail-Passwörter, empfohlen monatlich); „Sicherung einspielen“ führt sie wieder zusammen.
- **„Speichern auf dem Gerät fehlgeschlagen – bitte Speicherplatz prüfen“:** Gerätespeicher voll bzw. der Browser
  erlaubt nicht mehr. Speicher freimachen; mit Cloud ist nichts verloren. Ohne Cloud: sofort ⚙ → „Sicherung erstellen“.

## Oberfläche und Updates

| Symptom | Ursache | Lösung |
|---|---|---|
| Neue Funktion fehlt nach Veröffentlichung | GitHub Pages liefert noch die alte Fassung (1–10 min) bzw. Zwischenspeicher | Warten, neu laden. Installierte Web-App ganz schließen und öffnen |
| App zeigt nach Update eine leere/kaputte Seite | Neue Fassung startet nicht | Die App fällt nach zwei Fehlstarts selbst auf die mitgelieferte Fassung zurück. ⚙ → Updates → „Zurücksetzen“ erzwingt das sofort |
| „Für diese Version ist ein App-Update nötig“ | Neue Oberfläche braucht neuere App (`minNativ`) | App-Update installieren (⚙ → Updates bzw. Release herunterladen) |
| Update lädt nicht („Prüfsumme …“) | Veröffentlichung unvollständig: `web/` nach `tools/app-update.js` noch geändert | Beim Veröffentlichen `node tools/app-update.js` als letzten Schritt ausführen und erneut veröffentlichen |
| Farben/Statusleiste der installierten Web-App alt | Android übernimmt das Manifest nur beim Installieren | Vom Startbildschirm entfernen und neu hinzufügen |
| Glas-Effekte ruckeln (ältere Android-Geräte) | Lichtbrechung kostet Grafikleistung | Am Gerät „Transparenz reduzieren“/„Bewegung reduzieren“ einschalten – die App schaltet die Effekte dann ab |
| Texte oder Knöpfe stehen über / sind abgeschnitten | Darstellungsfehler | Bildschirmfoto mit Gerät/Fensterbreite festhalten; die Browser-Tests prüfen Überstände (`web/test/web.test.js`, Tests „Bedienung nach Apple“) |

## E-Mail (Windows- und Android-App)

| Symptom | Ursache | Lösung |
|---|---|---|
| „Das Postfach hat die Anmeldung abgelehnt“ | Passwort falsch, Anbieter verlangt App-Passwort | Im Webmail des Anbieters ein App-Passwort erzeugen und unter ⚙ → E-Mail → Konto eintragen |
| Microsoft 365 / Outlook.com geht nicht | Nur OAuth-Anmeldung erlaubt – wird nicht unterstützt | Anderes Postfach oder Weiterleitung nutzen |
| „Keine Verbindung zum Postfach: …“ | Server/Port/Verschlüsselung falsch, Netz | Einstellungen des Anbieters prüfen (IMAP 993 SSL, SMTP 465 SSL oder 587 STARTTLS) |
| „Das gespeicherte Passwort ist nicht mehr lesbar“ | Gerät/Windows-Konto gewechselt, Schlüssel weg | Konto neu einrichten (Passwort erneut eingeben) |
| Mails fehlen | Zeitraum der Einstellung „nur die letzten n Tage“, Regel verschiebt sie, Unterhaltung ignoriert | ⚙ → E-Mail-Einstellungen prüfen (Zeitraum, Regeln); Suche „Auf dem Server weitersuchen“ |
| Kein Postfach im Browser | Gewollt: E-Mail nur in den Apps (Passwort bleibt auf dem Gerät) | Windows- oder Android-App nutzen |

## Windows-App

| Symptom | Lösung |
|---|---|
| SmartScreen warnt beim Installieren | „Weitere Informationen → Trotzdem ausführen“ (Installer ist nicht signiert) |
| Scanner wird nicht gefunden / Scan bricht ab | Scannertreiber (WIA) installiert? Im Dialog „🩺 Scanner prüfen“ ausführen und das Ergebnis weitergeben. Andere Programme, die den Scanner belegen, schließen |
| Texterkennung liest falsch | Beleg gerade und hell scannen (300 dpi); unsichere Felder sind markiert und müssen geprüft werden |
| Fenster ist außerhalb des Bildschirms | `%APPDATA%\Schadensfälle\fenster.json` löschen (bei geschlossener App) |
| App startet nicht mehr nach Update | `%APPDATA%\Schadensfälle\webupdate\` löschen → mitgelieferte Oberfläche; sonst neu installieren (Daten bleiben) |

## Android-App

| Symptom | Lösung |
|---|---|
| Installation „App nicht installiert“ / „Paket ist ungültig“ | Die APK wurde mit einem anderen Schlüssel signiert. Signatur-Secrets dürfen sich nie ändern; sonst einmal deinstallieren (Daten: vorher Cloud abgleichen) und neu installieren |
| „Apps aus dieser Quelle zulassen“ | Beim ersten Update einmal erlauben (Android-Vorgabe ohne Play Store) |
| SMS gehen nicht direkt raus | Erlaubnis „SMS senden“ fehlt oder kein Mobilfunk → die App öffnet stattdessen die SMS-App |
| Keine Benachrichtigungen | Android 13+: Benachrichtigungen für die App erlauben (Einstellungen → Apps) |
| „Die Anzeige wurde vom System mehrfach beendet“ | Zu wenig Speicher – App schließen und neu öffnen, andere Apps beenden |

## Veröffentlichen und GitHub

| Symptom | Ursache | Lösung |
|---|---|---|
| Workflow „Web-App“ rot | Ein Browser-Test schlägt fehl | Actions → Lauf → Schritt „Web-App im Browser testen“ → `not ok …` lesen. Einzelner bekannter Wackel-Test ([tests.md](tests.md)): neu starten |
| Workflow „abgebrochen“ (cancelled) | Zeitlimit (15 min) erreicht, Testprozess hing | `--test-force-exit` ist gesetzt; erneut starten. Wiederholt: lokal `node --test web/test/web.test.js` ausführen |
| Windows-Workflow läuft nicht | Nur Änderungen unter `desktop/**` starten ihn | Version in `desktop/package.json` erhöhen (für ein App-Update ohnehin nötig) |
| Release/APK wird nicht veröffentlicht | Secret `SCHADEN_TOKEN` bzw. `SIGNATUR_*` fehlt/abgelaufen | Secrets prüfen ([updates-und-veroeffentlichen.md](updates-und-veroeffentlichen.md)); Token läuft ab → neu erzeugen |
| Push abgelehnt | Rechte bzw. Branch-Schutz | Mit einem Konto mit Schreibrechten; auf den richtigen Branch pushen |

## Weitere Hilfen

- **Ausführungsprotokoll der Cloud:** script.google.com → Projekt → „Ausführungen“ (Fehler mit Zeit und Meldung).
- **Konsole der Oberfläche:** im Browser F12 → Konsole; Android: `chrome://inspect` am PC mit USB-Debugging.
  Die Windows-App hat keine Entwicklerwerkzeuge eingebaut – Fehler dort im Browser nachstellen.
- **Code finden:** In `web/index.html` nach dem Abschnittsnamen oder nach dem Text der Meldung suchen – jede Meldung
  steht wörtlich im Code; die Funktion darüber hat einen Kommentar, der erklärt, wofür sie da ist.
