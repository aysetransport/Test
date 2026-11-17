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

## Neu in 2.1

- **Verkaufschancen** je Firma (Anfrage → Angebot → Verhandlung → Gewonnen/Verloren, mit Wert und Termin)
- **Schlagworte** für Firmen (z. B. Möbel, Elektro, Küchen) mit Filter in der Firmenliste
- **Kennzahlen** im Firmen-Detail: Schadensfälle offen/gesamt, offene Forderungen, gezahlte Vergleiche,
  offene und gewonnene Chancen, letzter Kontakt
- **Letzter Kontakt** in der Firmenliste und Filter **„Lange kein Kontakt“** (aktive Firmen, 60 Tage)
- **Kontakt festhalten mit Folgetermin**: legt gleich eine Wiedervorlage an
- **Wiedervorlagen verschieben** in „Termine“: Morgen / +1 Woche
- **Mail-Vorlagen für Firmen**: Angebot (mit der Preisliste der Firma), Terminbestätigung, Nachfassen,
  Rückfrage zur Abrechnung
- **Preisliste teilen** (Android) bzw. kopieren (Web/Windows)
- **Ansprechpartner ins Telefonbuch** und **Wiedervorlage in den Kalender** (Android)
- **Firmen zusammenführen** (doppelt angelegte Auftraggeber) im Bearbeiten-Dialog der Firma
- **Suche im Postfach**
- CSV-Export zusätzlich mit Verkaufschancen

Für 2.1 braucht es wieder die **aktuelle `Code.gs`** (Version 5) – wie in Schritt 2 bereitstellen
(„Neue Version“). Ohne sie meldet die App „Cloud-Skript veraltet“.

## Neu in 2.2 (Windows-App und Web-App)

- **Übersicht** als erster Reiter: Termine, nicht zugeordnete Mails, offene und überfällige Rechnungen,
  Umsatz Monat/Jahr, offene Chancen und Angebote, offene Schadensfälle, Firmen lange ohne Kontakt
- **Angebote und Rechnungen**: fortlaufende Nummern (R2026-0001, A2026-0001; Startnummer unter ⚙ einstellbar), Positionen frei oder aus der
  Preisliste, Netto/MwSt./Brutto, Einleitung und Schlusstext, **PDF speichern**, Drucken,
  **per E-Mail mit PDF-Anhang** (an den Ansprechpartner für Abrechnung), Angebot → Rechnung, Stornieren
- **Zahlstatus**: Entwurf, offen, teilbezahlt, bezahlt, überfällig; Zahlungen (auch Teilzahlungen)
  erfassen – sie stehen auch in der Kontakthistorie; **Zahlungserinnerung und Mahnungen** per Mail
  mit Mahnstufe
- **Finanzen**: offene Posten, Überfälliges, Umsatz je Firma, Suche, CSV-Export
- **Eigene Firmendaten** für Rechnungen unter ⚙ (Anschrift, Steuernummer, USt-IdNr., Bank, Zahlungsziel,
  MwSt.); je Firma **Kundennummer, USt-IdNr. und Zahlungsziel**
- **Termine als Kalenderdatei (.ics)** für Outlook/Google Kalender

Angebote und Rechnungen gleichen sich über die Cloud zwischen Windows-App und Web-App ab; die
Android-App kennt sie noch nicht (sie lässt sie unverändert).

## Neu in 2.3 (Windows-App und Web-App)

**Wichtig:** Diese Version braucht die neue `cloud/Code.gs` (Cloud-Version 6). In Google Apps Script den Inhalt ersetzen und unter *Bereitstellen → Bereitstellungen verwalten → Bearbeiten → Version: Neue Version* neu bereitstellen. Solange das alte Skript läuft, zeigt die App „Das Cloud-Skript ist veraltet“ und gleicht nicht ab.

- **Fuhrpark** (neuer Reiter): Fahrzeuge mit allen Angaben aus dem Fahrzeugschein (Halter, Marke, FIN, Erstzulassung, HSN/TSN, Gewichte, Leistung, Schadstoffklasse …), Versicherung und Leasing.
- **Fristen:** HU (TÜV), SP, UVV, Tachograph, Ladebordwand, Wartung (nach Datum oder km), Versicherung und Leasingende. Was in den nächsten 30 Tagen fällig ist, steht farbig in der Liste, unter *Termine* und in der *Übersicht*.
- **Dokumente:** Fahrzeugschein, Fahrzeugbrief, Versicherung, Prüfberichte, Verträge und Fotos am Fahrzeug ablegen. Sie liegen wie die Anlagen der Schadensfälle in Google Drive.
- **Fahrzeugakte:** Wartung, Reparatur, Reifen, Tanken, Prüfungen, Schäden, Fahrerwechsel und sonstige Kosten. Daraus ergeben sich Kosten pro Jahr und pro km sowie der Durchschnittsverbrauch.
- Schadensfälle, bei denen das Kennzeichen im Feld *Fahrzeug* steht, erscheinen beim Fahrzeug. Im Fall-Formular werden Kennzeichen und Fahrer vorgeschlagen.
- CSV-Export für Fahrzeuge und Fahrzeugakte.
- **Drucken** von Rechnungen und Angeboten öffnet kein eigenes Fenster mehr und wird darum nicht mehr blockiert.
- **Abmelden und wieder Anmelden** funktioniert zuverlässig: oben auf „Anmeldung nötig“ tippen. Im PIN-Fenster gibt es jetzt „Später“.
- **Schnellerer Cloud-Abgleich:** Die Nachfrage „gibt es Neues?“ liest die große Daten-Datei nicht mehr jedes Mal. Mail-Texte in Kontakthistorie und Fallverlauf werden gekürzt (die ganze Mail bleibt im Postfach), schon gespeicherte lange Mails einmalig auch. Die Anzeige springt bei reinen Hintergrund-Prüfungen nicht mehr auf „Gleiche ab …“.

## Bekannte Grenzen

- Mails ohne Fall liegen nur auf dem Gerät, das sie abgerufen hat. Mails zu Firmen erscheinen über
  die Kontakthistorie aber auf allen Geräten.
