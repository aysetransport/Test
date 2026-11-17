# Update auf Version 2.0 – Ayse CRM

Aus der App „Schadensfälle“ wird mit 2.0 das CRM für die Auftraggeber („Ayse CRM“). Die
Schadensfälle bleiben vollständig erhalten und stehen unten in der Leiste unter **Schäden**.
Neu sind **Firmen** (Startseite), **Termine** (alle Wiedervorlagen) und **Postfach** (alle Mails,
auch die nicht zugeordneten).

Reihenfolge: **1 → 2 → 3 → 4.** Dauer etwa 15 Minuten.

---

## 1. Vor dem Update: Backup in der alten App

Die bisherige APK 1.4.0 wurde von GitHub mit einem Testschlüssel signiert, der bei jedem Lauf neu
entsteht. Android installiert die neue Version deshalb **nicht über die alte**. Die alte App muss
einmal deinstalliert werden, und dabei löscht Android ihre Daten. Darum zuerst sichern:

1. In der alten App **Einstellungen → Backup erstellen** tippen und die ZIP-Datei z. B. unter
   „Downloads“ oder in Google Drive speichern.
2. Kurz prüfen, ob die Datei da ist (Größe deutlich über 0 KB).

> Wenn die Cloud verbunden war: Sie war zuletzt „veraltet“ und hat deshalb nicht mehr abgeglichen.
> Der Stand in Google Drive ist also älter als der auf dem Handy. **Das Backup vom Handy ist maßgeblich.**

## 2. Neue Code.gs bereitstellen (Google Apps Script)

Die neue Code.gs überträgt zusätzlich alle CRM-Daten. Ohne sie melden App und Web-App weiter
„Das Cloud-Skript ist veraltet“.

1. [script.google.com](https://script.google.com) öffnen → Projekt **„Schadensfälle Cloud“**.
2. Im Editor den gesamten Inhalt von `Code.gs` markieren und löschen, dann den Inhalt von
   [`cloud/Code.gs`](../cloud/Code.gs) aus diesem Branch einfügen und **Speichern** (Strg+S).
3. **Bereitstellen → Bereitstellungen verwalten** → die vorhandene Web-App-Bereitstellung →
   Stift (**Bearbeiten**) → bei *Version* **„Neue Version“** wählen → **Bereitstellen**.
   - **Nicht** „Neue Bereitstellung“ wählen, sonst ändert sich die Adresse (…/exec).
   - *Ausführen als: Ich* und *Zugriff: Jeder* bleiben wie bisher.
4. Prüfen: Die Adresse …/exec im Browser öffnen. Dort muss „Schadensfälle-Cloud läuft“ stehen.

Die Daten in Google Drive (`Schadensfaelle/daten.json`) bleiben erhalten. Die PIN bleibt gleich.

## 3. App 2.0 installieren

1. Die neue APK herunterladen:
   - direkt die Datei `Ayse-CRM-2.0.0.apk` (wird mitgeschickt – die optimierte, schnelle Fassung), **oder**
   - in GitHub unter **Actions → Android** den neuesten grünen Lauf auf dem Branch
     `claude/android-schadensfall-app-krncja` öffnen und unter *Artifacts* `Ayse-CRM-apk` laden
     (ZIP mit der APK).
2. Die alte App **„Schadensfälle“ deinstallieren** (lange auf das Symbol drücken → Deinstallieren).
3. Die APK öffnen und installieren (Installation aus dieser Quelle erlauben). Die App heißt jetzt
   **„Ayse CRM“**.
4. **Einstellungen → Daten → Backup wiederherstellen** und die ZIP aus Schritt 1 wählen. Alle Fälle
   mit Verlauf und Anlagen sind wieder da. Aus den Auftraggebern der Fälle entstehen automatisch die
   Firmen. Ansprechpartner, Telefon und E-Mail des Auftraggebers werden dort als Ansprechpartner mit
   der Zuständigkeit „Schäden“ eingetragen.
5. **Einstellungen → Firma / Bearbeiter** prüfen (stehen im Backup nicht drin, ggf. neu eintragen).
6. **Cloud:** Einstellungen → Cloud → Adresse ist vorbelegt → **Verbinden** → PIN eingeben. Die
   Fälle vom Handy gehen hoch, die Firmen gleichen sich mit der Web-App ab.
7. **E-Mail:** Die Passwörter liegen verschlüsselt im Handy und sind mit dem Deinstallieren weg.
   Darum unter Einstellungen neu einrichten:
   - **E-Mail für Schadensfälle:** das bisherige Postfach (z. B. schaden@…). Mails aus einem Fall
     gehen darüber.
   - **E-Mail für alles andere:** das allgemeine Postfach (z. B. info@ oder dispo@…). Mails an Firmen
     und Ansprechpartner gehen darüber. Ohne zweites Konto läuft alles über das erste.

Wer ohne Cloud arbeitet, macht 1, 3 und 4. Schritt 2 entfällt dann.

> **Künftige Updates ohne Deinstallieren:** Dafür braucht es einen festen Signaturschlüssel. Der
> Workflow ist vorbereitet: Liegen im Repository unter *Settings → Secrets and variables → Actions*
> die Secrets `SIGNATUR_BASE64` (Keystore als Base64), `SIGNATUR_PASSWORT` und `SIGNATUR_ALIAS`,
> signiert er jede APK damit. Den Schlüssel einmal anlegen, z. B.
> `keytool -genkeypair -keystore ayse.jks -alias ayse-crm -keyalg RSA -keysize 3072 -validity 11000`,
> sicher aufbewahren und als Secret hinterlegen. Danach geht jedes Update einfach über die
> installierte App drüber.

## 4. Sicherung des Browser-CRM importieren

Die Daten aus `Desktop\Claude\CRM\index.html` kommen über deren JSON-Sicherung ins neue CRM.
Am einfachsten am PC über die Web-App:

1. Im Browser-CRM die **Sicherung (JSON)** speichern, z. B. `crm-sicherung.json`.
2. Die Web-App öffnen (https://aysetransport.github.io/schaden/, nach Schritt 5) bzw. die
   Windows-App → **⚙ → CRM → „Sicherung des Browser-CRM importieren“** → Datei wählen.
3. Die Meldung zeigt, wie viele Firmen neu sind und wie viele mit vorhandenen zusammengeführt wurden.
   Über die Cloud landet alles auch auf dem Handy.

Alternativ auf dem Handy: Datei aufs Handy legen → **Einstellungen → Auftraggeber (CRM) →
„Sicherung des Browser-CRM importieren (JSON)“**.

- Firmen mit gleichem Namen (ohne Groß-/Kleinschreibung) werden **zusammengeführt**: Leere Felder
  werden ergänzt, Notizen angehängt. Ansprechpartner, Adressen, Preise usw. kommen nicht doppelt.
- Der Import lässt sich gefahrlos wiederholen. Es kommt nur hinzu, was noch fehlt.
- Danach kann das Browser-CRM stillgelegt werden. Es gibt nur noch ein CRM.

## 5. Web-App veröffentlichen

Die Web-App läuft aus dem Repository `aysetransport/schaden` (GitHub Pages). Die Dateien
`web/index.html`, `web/sw.js` und `web/manifest.webmanifest` aus diesem Branch dorthin kopieren
und committen. Die Icons sind unverändert. `sw.js` hat eine neue Speicherversion, damit installierte
Web-Apps die neue Fassung laden.

---

## Was ist neu

- **Firmen (Startseite):** Suche über Firmen, Ansprechpartner, Notizen, Adressen, Kontakthistorie
  und Fälle, Filter nach Status (Interessent, Aktiv, Pausiert, Ehemalig), nächste Wiedervorlage je Firma.
- **Firma:** Stammdaten, Ansprechpartner (anrufen, Mail schreiben), Adressen, Preise und
  Konditionen, Wiedervorlagen (abhaken), Kontakthistorie inkl. Mails, Dokumente (Links) und die
  Schadensfälle der Firma. **„Neuer Fall“** belegt den Auftraggeber und den Ansprechpartner für
  Schäden vor.
- **Termine:** Überfällig / Heute / Demnächst, mit Wiedervorlagen bei Firmen und aus Schadensfällen.
- **Postfach:** beide Postfächer. Eingehende Mails werden zugeordnet:
  1. Fallnummer im Betreff → Fall
  2. Antwort auf eine bekannte Mail → wie diese
  3. nur Schadens-Postfach: Absender ist bei genau einem offenen Fall eingetragen → Fall
  4. Absender passt zur E-Mail der Firma, eines Ansprechpartners oder zur Domain der Firma
     (aus E-Mail oder Website; gmail, web.de, gmx & Co. zählen nicht) → Firma
  5. sonst → **„Nicht zugeordnet“**

  Jede Mail lässt sich über **Zuordnen / Umhängen** einem Fall, einer Firma oder einem Kunden
  zuordnen. Anhänge werden dabei Anlagen des Falls.
- **CSV für Excel:** Firmen, Ansprechpartner, Preise, Aktivitäten, Wiedervorlagen (Einstellungen →
  Auftraggeber (CRM)) und wie bisher die Fallliste.
- **Backup** enthält jetzt auch das ganze CRM und die Mails.

## Windows-App

Die Windows-App hat dieselbe Mehrpostfach-Funktion wie Android: unter ⚙ zwei Konten (Schäden und
Allgemein), Reiter **Postfach** mit „Nicht zugeordnet“, Zuordnen/Umhängen, Antworten und Mails an
Firmen und Ansprechpartner (✉ in der Firma). Installer: GitHub → Actions → *Windows-App* → neuester
grüner Lauf → *Artifacts* `Schadensfaelle-Windows-Setup` → `Schadensfaelle-Setup-2.0.0.exe` ausführen
(installiert über die bisherige Version, Daten und Konto bleiben).

## Bekannte Grenzen

- Mails ohne Fall liegen nur auf dem Gerät, das sie abgerufen hat. Mails zu Firmen erscheinen über
  die Kontakthistorie aber auf allen Geräten.
