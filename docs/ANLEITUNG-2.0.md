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
- **Import** (Fuhrpark → *Importieren*): übernimmt eine Fuhrpark-Datei mit Fahrzeugdaten und Dokumenten (z. B. aus eingelesenen Fahrzeugscheinen). Gleiches Kennzeichen = gleiches Fahrzeug, vorhandene Angaben bleiben, nichts wird doppelt angelegt.
- **Drucken** von Rechnungen und Angeboten öffnet kein eigenes Fenster mehr und wird darum nicht mehr blockiert.
- **Abmelden und wieder Anmelden** funktioniert zuverlässig: oben auf „Anmeldung nötig“ tippen. Im PIN-Fenster gibt es jetzt „Später“.
- **Schnellerer Cloud-Abgleich:** Die Nachfrage „gibt es Neues?“ liest die große Daten-Datei nicht mehr jedes Mal. Mail-Texte in Kontakthistorie und Fallverlauf werden gekürzt (die ganze Mail bleibt im Postfach), schon gespeicherte lange Mails einmalig auch. Die Anzeige springt bei reinen Hintergrund-Prüfungen nicht mehr auf „Gleiche ab …“.

## Neu in 4.3 (Windows-App 4.3, Android-App 5.3, Web-App) – Lohn-Import und Zusammenführen

**Wichtig:** Neue `cloud/Code.gs` bereitstellen (gleiche Cloud-Version 9, aber Lohnabrechnungs-Dokumente sind jetzt für Disposition-Logins gesperrt).

- **Personal-Import mit Zuordnung**: Personal → „⤓ Import (Lohnabrechnungen)“ zeigt vor dem Import jede Person der Datei mit der Zuordnung (vorhandener Mitarbeiter, neu anlegen oder auslassen). Ähnliche Namen sind gelb markiert und stehen oben. Abweichende Namen werden als Alias gespeichert.
- **Mitarbeiter zusammenführen** (nur Admin): beim Mitarbeiter unten „🔁 Mit anderem zusammenführen …“ oder in der Personal-Liste „🔁 Doppelte (n)“ – findet Paare wie „Fatih“ ↔ „Fatih Akyüz“.
  - Touren (Fahrer und Beifahrer), Arbeitszeiten, Abwesenheiten, Personalakte und Lohnabrechnungen, Fahrerkartentage, Dokumente, Aufgaben und Schadensfälle wandern zum bleibenden Eintrag; leere Felder werden ergänzt, der andere Name wird Alias.
  - Vorschlag: Der Eintrag mit den meisten Touren bleibt (an ihm hängen meist auch Logins), der längere Name wird übernommen – beides änderbar.
- **Lohnabrechnungen** (PDF in der Personalakte) sieht nur noch der Admin, nicht die Disposition.
- **WhatsApp an das ganze Team auf einmal** (wie in der alten Tourenplanung): Tour → 💬 → „WhatsApp an alle“ öffnet WhatsApp mit dem Tourtext, dort Fahrer und Beifahrer gleichzeitig (oder die Team-Gruppe) auswählen. Daneben „SMS an alle“ als Gruppen-SMS.

## Neu in 4.2 (Windows-App 4.2, Android-App 5.2, Web-App) – Monatsabrechnung und Briefe

- **🧾 Monatsabrechnung** (Finanzen oder ＋ Neu): zeigt für den Vormonat (Zeitraum frei wählbar) jede Firma mit gefahrenen, noch nicht abgerechneten Touren – Anzahl, Summe netto und Einsatzarten ohne Preis. Häkchen setzen, „Rechnungen anlegen“: je Firma eine Rechnung als Entwurf, Positionen je Einsatzart mit dem Preis aus der Preisliste der Firma. Die Touren gelten danach als abgerechnet; wird die Rechnung gelöscht, sind sie wieder offen.
- **✉ Briefe nach DIN 5008** mit Ihrem Briefkopf (⚙ → Eigene Firmendaten), druckbar oder als PDF:
  - Vorlagen: freier Brief, Urlaubsbestätigung (Zeitraum, Arbeitstage, Resturlaub automatisch), Beschäftigungsbescheinigung (Eintritt, Stelle, Wochenstunden), Aufforderung Fahrerkarte auslesen, Auftragsbestätigung und Preisanpassung (mit der Preisliste der Firma), Beendigung der Zusammenarbeit.
  - Aufrufen über ＋ Neu → Brief, beim Mitarbeiter „✉ Brief“, bei der Firma „📄 Brief“ oder im Urlaubseintrag „Urlaubsbestätigung drucken“. Anschrift und Anrede werden vorbelegt, alles bleibt änderbar.
  - Gedruckte Briefe werden bei der Firma als Aktivität bzw. in der Personalakte vermerkt.

## Neu in 4.1 (Windows-App 4.1, Android-App 5.1, Web-App) – eigene Logins

**Wichtig:** Diese Version braucht die neue `cloud/Code.gs` (Cloud-Version 9) – ersetzen und als neue Version bereitstellen.

- **Eigene Logins** für Fahrer und Büro: ⚙ → Anmeldung → „Benutzer und Logins verwalten“ (nur Admin) oder beim Mitarbeiter „👤 Login“.
  - Benutzername wird vorgeschlagen (z. B. ali.demir), Passwort erzeugt; die Zugangsdaten lassen sich direkt per WhatsApp, SMS oder Ausdruck weitergeben. „Logins für alle Fahrer ohne Login“ legt sie in einem Rutsch an.
  - Login ausschalten = sofort gesperrt, alle Geräte dieses Benutzers abgemeldet. Neues Passwort setzen ebenso.
- **Rollen** (von der Cloud durchgesetzt – nicht nur in der App versteckt):
  - *Admin* (Anmeldung mit der Cloud-PIN oder Admin-Login): alles, inklusive Benutzerverwaltung und Lohndaten.
  - *Disposition / Büro*: alles außer Benutzerverwaltung und Lohndaten (Lohnabrechnungen, Stundenlöhne werden weder angezeigt noch geändert).
  - *Fahrer*: nur „Meine Touren“ (eigene Touren ab 45 Tagen zurück, deren Kunden und Team), eigene Arbeitszeiten, Abwesenheiten, Aufgaben, Kalender und Schadensmeldungen zu eigenen Touren. Darf nur Status, Zeiten, km und Ablieferung der eigenen Touren ändern – keine Firmen, Preise, Rechnungen oder Daten anderer.
- **Anmelden** auf dem Fahrer-Handy: Web-App öffnen (iPhone: Safari → Teilen → „Zum Home-Bildschirm“), Benutzername und Passwort eingeben. Die Anmeldung gilt 90 Tage.
- Passwörter werden nur gesalzen und gehasht gespeichert; nach 8 Fehlversuchen ist der Login eine Stunde gesperrt.
- Behoben: Der Hinweis beim Start überdeckte manchmal andere Meldungen.

## Neu in 4.0 (Windows-App 4.0, Android-App 5.0, Web-App)

**Wichtig:** Diese Version braucht die neue `cloud/Code.gs` (Cloud-Version 8) – wie gewohnt ersetzen und als neue Version bereitstellen.

**Bedienung**
- ☰ oben links: am PC Seitenleiste breit/schmal, am Handy und Tablet ausklappbare Seitenleiste mit allen Bereichen (gruppiert nach Heute, Disposition, Kunden, Auswertung), Einstellungen, Hell/Dunkel und Tastenkürzel. Unten bleiben vier Bereiche und „Mehr“.
- Werkzeugleisten zeigen am Handy die wichtigsten Knöpfe, der Rest steckt hinter „⋯“.
- Hell, dunkel oder wie das Gerät (⚙ → Bedienung).
- Tastenkürzel: / oder Strg+K Suche, N Neu, T Tourenplan, K Kalender, G Übersicht, Alt+1…9 Bereiche, ? Hilfe.
- Am Handy (auch als iPhone-Web-App vom Home-Bildschirm): nichts scrollt seitlich, Eingaben zoomen nicht, Dialoge öffnen als Vollbild, Notch und Home-Leiste werden berücksichtigt.

**Kalender** (📆): Monat oder Woche mit Touren, Abwesenheiten, Terminen, Aufgaben, Fristen, Geburtstagen und Feiertagen; Ebenen ein-/ausblendbar; Tag antippen für Details, Aufgabe oder Abwesenheit; Drucken und .ics.

**Feiertage** für alle Bundesländer (⚙ → Arbeitszeit und Kalender, Vorgabe NRW): im Kalender, im Tourenplan, in der Zeiterfassung und bei Urlaubstagen.

**Aufgaben** (✅): für dich und das Team, mit Zuständigen, Fälligkeit, Priorität und Bezug zu Fall, Tour, Fahrzeug, Firma oder Mitarbeiter. Schnelleingabe versteht „heute“, „morgen“ und „@Name“. Ansichten Offen, Meine, Überfällig, Heute, Diese Woche, Ohne Zuständige, Erledigt. Erscheinen auch unter Termine, im Kalender und in der Übersicht.

**Zeiterfassung** (⏱)
- Erfassen von Hand, Stempeluhr im Fahrer-Modus oder aus der Fahrerkarte. Jede Zeit zeigt ihre Herkunft.
- „Entwürfe aus Tourenplan“: für Einsatztage ohne Zeit ein *Entwurf* (Beginn ab Ladezeit). Entwürfe sind gelb markiert und zählen erst, wenn die tatsächlichen Zeiten bestätigt bzw. korrigiert sind.
- Prüfung nach Arbeitszeitgesetz: mehr als 10 Stunden, zu wenig Pause (30/45 min), Ruhezeit unter 11 Stunden, Sonn- und Feiertagsarbeit, über 48 Stunden pro Woche, Mindestlohn.
- Monatsübersicht mit Ist, Soll, Saldo, Sonntags-, Feiertags- und Nachtstunden.
- Stundenzettel zum Drucken mit Unterschriftsfeldern, **Excel (.xlsx)** mit Übersicht und einem Blatt je Mitarbeiter, CSV fürs Lohnbüro.

**Fahrerkarte (Tachograph)** (💳 in Zeiten, Personal oder ＋ Neu)
- .ddd-/.c1b-/.tgd-/.esm-Dateien der Fahrerkarte (1. und 2. Generation) einlesen: Fahrer, Kartennummer, Gültigkeit, Tätigkeiten je Tag (Lenken, Arbeit, Bereitschaft, Ruhe), benutzte Fahrzeuge und Kilometer.
- Prüfung nach VO (EG) 561/2006: Tageslenkzeit (9/10 h), Fahrtunterbrechung nach 4,5 h, Wochen- und Doppelwochenlenkzeit, tägliche Ruhezeit.
- Übernahme in die Zeiterfassung als echte Aufzeichnung; Kilometerstände in den Fuhrpark; Datei wird beim Mitarbeiter abgelegt; Frist „Fahrerkarte auslesen“ alle 28 Tage.
- Excel-Export je Fahrer und Monat (Stundenzettel aus der Fahrerkarte).
- **Aliase:** Fahrzeuge können weitere Kennzeichen haben (andere Schreibweise, alte oder Leih-Kennzeichen), Mitarbeiter andere Schreibweisen des Namens und die Kartennummer – so wird alles richtig zugeordnet.

**Tourenplanung**
- Status je Tour (geplant, unterwegs, erledigt, Problem, storniert), Beginn/Ende, km-Stand (Fahrtenbuch beim Fahrzeug), Empfänger.
- Wochenplan (Fahrzeuge × Tage), Feiertagshinweis, Begleitschein/Liefernachweis zum Drucken (auch alle des Tages), Navigation zur Adresse der Firma, Aufgabe zur Tour, stornieren.

**Meine Touren (Fahrer-Modus)** (🧭) – fürs Handy der Fahrer
- Einmal „Wer nutzt dieses Gerät?“ wählen. Dann: eigene Touren der nächsten 7 Tage, Stempeluhr (Arbeitsbeginn, Pause, Feierabend).
- Losfahren (mit km-Stand), Navigation, Foto, Abliefern mit **Unterschrift des Empfängers** auf dem Bildschirm und Fotos, Schaden melden, Problem melden (legt eine wichtige Aufgabe an), Urlaub beantragen, krank melden.

**Weitere Funktionen**
- 🧮 Preiskalkulator (Finanzen oder ＋ Neu): Stunden, Personen, km, Fahrzeug, Maut, Aufschlag → Preis je Einsatz, direkt als Angebot.
- 📒 Buchhaltung: Rechnungsausgangsbuch als CSV für Steuerberater/DATEV (Erlöskonto und Debitor unter ⚙ → Buchhaltung).
- Firmenliste aus Excel/CSV importieren (Firmen → „Liste importieren“); Spalten werden am Namen erkannt.
- Datensicherung: alles in einer Datei sichern und wieder einspielen (⚙ → Datensicherung); Erinnerung nach 30 Tagen.
- Hinweis beim Start: überfällige Termine, fällige Aufgaben, Krankmeldungen, Touren mit Problem, „morgen noch nichts geplant“ – in den Apps auch als Benachrichtigung.
- Statistik: neue Daten Arbeitszeit, Fahrerkarten und Aufgaben; neue fertige Statistiken (Arbeitsstunden, ArbZG-Verstöße, Lenkzeit und km je Fahrer/Fahrzeug, Touren nach Status, Tourdauer, Erledigungsquote).
- Behoben: Ein Klick in den Einstellungen startete versehentlich jedes Mal einen Abgleich.

## Neu in 3.1 (Windows-App 3.1, Android-App 4.1, Web-App) – Statistik

Kein neues Cloud-Skript nötig (bleibt Version 7). Der Bereich heißt jetzt **Statistik** (📊).

- **Fertige Statistiken** (über 50), z. B. Touren je Auftraggeber/Monat/Wochentag/Fahrzeug, nicht abgerechnete Touren, Schäden je Mitarbeiter, je Tour, je Fahrzeug, je Auftraggeber und je Einsatzart – auch als **Schäden je 100 Touren**, Schadenkosten, Arbeits- und Krankheitstage, Lohnkosten je Arbeitstag, Gewinn/Verlust je Auftraggeber, Tour, Monat, Fahrzeug und Einsatzart, Umsatz je Tour, Zahlungsdauer, Fahrzeugkosten je Einsatztag, Werkstätten, Kontakte, Anruf-Ergebnisse, Verkaufschancen, E-Mails.
- **Baukasten:** jede erfasste Zahl aus Touren, Einsätzen, Schäden, Abwesenheiten, Rechnungen, Angeboten, Fahrzeugakte, Kontakten, Verkaufschancen, Lohnabrechnungen, Personalakte, E-Mails sowie Firmen-, Personal- und Fahrzeugbestand – gruppiert nach jedem Merkmal (Mitarbeiter, Fahrzeug, Auftraggeber, Einsatzart, Tour, Monat, Quartal, Woche, Wochentag, Status …), als Kreuztabelle, mit Filter, Top 5/10/20 und **ins Verhältnis gesetzt** zu einer anderen Zahl (je 100, je 1, in %). Zeile antippen = genauer aufschlüsseln.
- **Top & Flop:** Top- und schwächste Mitarbeiter (Gesamtwertung aus Schadenquote, Krankheitstagen, Deckungsbeitrag je Arbeitstag und Einsatztagen), wenigste/meiste Schäden je 100 Touren, beste und schlechteste Touren (Gewinn/Verlust), beste und schwächste wiederkehrende Touren, Touren mit den meisten Schäden, Gewinn und Verlust je Firma.
- **Gewinn & Verlust:** Erlös, Personal-, Fahrzeug- und Schadenkosten je Monat, Ergebnis und Marge, dazu der Abgleich mit Rechnungen, Lohnabrechnungen und Fahrzeugakte.
- **So wird gerechnet:** Erlös einer Tour = Anteil an der Rechnung (gewichtet nach Preisliste) bzw. Preis der Firma für die Einsatzart. Personal = Lohnabrechnung des Monats ÷ Arbeitstage, sonst Stundenlohn × Tagesstunden × Arbeitgeber-Zuschlag, sonst Tagessatz. Fahrzeug = (Fahrzeugakte + Leasingrate) des Monats ÷ Einsatztage, sonst Tagessatz. Schäden = Vergleich bzw. Forderung der verknüpften Fälle (abgelehnte nicht; bei Versicherung höchstens die Selbstbeteiligung). Tagessätze, Zuschlag, Selbstbeteiligung und Gemeinkosten unter ⚙ → Kosten.
- Gewinn/Verlust steht auch bei jeder Tour (⋮), bei der Firma, beim Fahrzeug und beim Mitarbeiter.
- Jede Statistik lässt sich drucken und als CSV (Excel) speichern.

## Neu in 3.0 (Windows-App 3.0, Android-App 4.0, Web-App) – alles in einer App

**Wichtig:** Diese Version braucht die neue `cloud/Code.gs` (Cloud-Version 7). In Google Apps Script den Inhalt ersetzen und unter *Bereitstellen → Bereitstellungen verwalten → Bearbeiten → Version: Neue Version* neu bereitstellen. Vorher zeigt die App „Das Cloud-Skript ist veraltet“.

**Neues Layout**
- Am PC links eine feste Leiste mit allen Bereichen, am Handy unten fünf feste Knöpfe (vier Bereiche + „Mehr“). Nichts muss mehr seitlich gewischt werden. Welche vier unten stehen: ⚙ → Bedienung.
- Oben: **Suche über alles** (Firmen, Ansprechpartner, Fälle, Touren, Personal, Fahrzeuge, Rechnungen, Mails; am PC Strg+K) und **＋ Neu** (Fall, Tour, Firma, Interessent, Mitarbeiter, Abwesenheit, Fahrzeug, Rechnung, Angebot, Rechnung aus Touren, E-Mail).
- **Übersicht** als Tagesstart: Touren und Personal heute, Probleme im Plan, Heute zu tun, Schäden, Finanzen, Vertrieb, Fristen, Postfach.

**Tourenplanung – jetzt fester Teil des CRM**
- Tagesplan mit Fahrzeug, Tour-Nr., Auftraggeber, Tour/Ziel, Ladezeit, Einsatzart, Fahrer und Beifahrern; jede Eingabe wird sofort gespeichert und auf alle Geräte abgeglichen.
- **Hinweise** direkt an der Tour: doppelt eingeplant, abwesend (Urlaub/Krank), inaktiv, „nicht bei“ diesem Auftraggeber, Führerschein reicht nicht für das Gewicht, Führerschein/Fahrerkarte/Modul 95 abgelaufen, Fahrzeug in der Werkstatt oder abgemeldet, HU/SP abgelaufen, Beifahrer oder Monteur fehlt.
- **Teams auffüllen:** schlägt freie, passende Leute vor (fester Fahrer des Fahrzeugs, wer Fahrzeug und Auftraggeber kennt, Monteur wo nötig, wer zuletzt weniger gearbeitet hat).
- Übernehmen von einem anderen Tag (mit Team, Abwesende bleiben draußen), Rückgängig (auch Strg+Z), Reihenfolge ▲▼, Tour duplizieren oder auf einen anderen Tag kopieren.
- Freies Personal und freie Fahrzeuge (nach Gewicht), Abwesende, Fahrzeuge in der Werkstatt.
- Drucken (A4 quer), „Für Fahrer“ (schmales PDF fürs Handy), WhatsApp oder SMS je Person, „An alle“ und CSV für Sammel-SMS.
- Berichte: Auftraggeber-Bericht, Anwesenheitsliste, Touren als CSV, Rechnung aus Touren.
- **Übernahme der bisherigen Tourenplanung:** ⚙ → Tourenplanung und Personal → „Aus der bisherigen Tourenplanung übernehmen“ (PIN der alten Tourenplanung, Zeitraum). Fahrzeuge, Personal, Einsatzarten, Auftraggeber (mit Adresse), Abwesenheiten und alle Touren kommen ins CRM. Wiederholen ist gefahrlos – bis zum Umstieg einfach erneut ausführen.

**Personal**
- Mitarbeiter mit Rolle, Monteur, Führerscheinklassen, „nicht einplanen bei“, Anstellung, Personalnummer, Wochenstunden, Stundenlohn, Urlaubsanspruch, Kontakt- und Notfalldaten.
- Fristen (Führerschein, Fahrerkarte, Modul 95, Führerscheinkontrolle, Unterweisung, Arbeitsmedizin) und Geburtstage erscheinen unter „Termine“ und in der Übersicht.
- Abwesenheiten mit Monatskalender (Tag antippen = eintragen), Urlaubskonto (Anspruch, genommen, geplant, Rest), Warnung bei bereits eingeplanten Touren.
- Personalakte (Gespräche, Schulungen, Unterweisungen, Ausrüstung, Abmahnungen, Lohnabrechnungen mit Brutto/Netto/Kosten) und Dokumente.
- **Auswertung je Mitarbeiter:** Arbeitstage, davon als Fahrer, Touren, Urlaub, Krank, Einsatzarten, Auftraggeber, Fahrzeuge, Lohnkosten je Arbeitstag, Schadensfälle.
- **Import der Lohnabrechnungen:** Personal → „Import (Lohnabrechnungen)“ liest eine Personal-Datei (wird aus den eingelesenen Abrechnungen erstellt). Gleiche Personalnummer bzw. gleicher Name = gleiche Person, vorhandene Angaben bleiben, je Monat eine Abrechnung.

**Auswertungen** (eigener Bereich): Personal, Auftraggeber (Touren, nicht abgerechnet, Umsatz je Tour) und Fahrzeuge (Einsatztage, Kosten je Einsatztag, Schäden, Fahrer) für Monat, Jahr oder freien Zeitraum – mit Drucken und CSV.

**Querverbindungen**
- Tour → „Schaden melden“ legt einen Fall mit Datum, Fahrzeug, Team und Auftraggeber an; der Fall zeigt Tour, Fahrzeug und Mitarbeiter als Links, die Tour zeigt den Fall.
- „Rechnung aus Touren“ rechnet nur noch nicht abgerechnete Touren ab und markiert sie mit der Rechnungsnummer – keine Doppelabrechnung.
- Firma: Touren dieses/letzten Monats, geplante Touren, nicht abgerechnete Touren, Umsatz je Tour, eingesetztes Personal.
- Fahrzeug: heutige Tour, Einsatztage, letzte Einsätze, fester Fahrer als Link, Leihfahrzeug.
- Mitarbeiter: Einsätze, Fahrzeuge, Schadensfälle, Auftraggeber.

**Firmen und Akquise**
- „＋ Interessent“ mit Akquise-Ablauf (Mail heute, Anruf nach 3 Tagen, Nachfassen nach 10 und 30 Tagen).
- „📞 Anruf festhalten“ mit Gesprächsleitfaden und Ergebnis (nicht erreicht, Rückruf, Infos, Termin, Angebot, Auftrag, kein Bedarf, kein Interesse) – legt Kontakt, Wiedervorlage, Verkaufschance an und ändert den Status.
- „📞 Anrufliste“: fällige Interessenten nacheinander anrufen.
- Standard-Mails: Erstkontakt, Nach dem Telefonat, Freie Kapazitäten, Angebot, Nachfassen, Terminbestätigung, Danke für den Auftrag, Wieder in Kontakt, Preisanpassung, Bitte um Empfehlung, Feiertagsgrüße, Rückfrage Abrechnung. Leistungstext und Leitfaden unter ⚙ → Akquise.
- **Serienmail:** dieselbe Vorlage einzeln an ausgewählte Firmen, mit persönlicher Anrede und Eintrag in der Kontakthistorie.

**Postfach (Windows- und Android-App)**
- Posteingang mit Unterhaltungen, Ungelesen, Markiert (★), Erinnerung, Gesendet, Entwürfe, Geplant, Archiv, Papierkorb.
- Antworten, Allen antworten, Weiterleiten (mit Anhängen), BCC, Adressbuch-Vorschläge aus dem ganzen CRM, Textbausteine und Signatur (⚙ → E-Mail).
- Entwürfe speichern, **später senden** (in 1 Stunde, morgen 8:00, Montag 8:00 – die App muss dann laufen).
- Aus einer Mail: Schadensfall anlegen (Mail hängt am Fall), Firma aus Absender anlegen, Absender als Ansprechpartner speichern, Wiedervorlage bei der Firma, Erinnerung (erscheint unter „Termine“), Drucken.

## Neu in 2.5 (Windows-App 2.5, Android-App 3.2, Web-App)

- **Rechnung aus Touren** (Finanzen → *Rechnung aus Touren* oder in der Firma unter *Touren*): Firma und Zeitraum wählen, die App zählt die gefahrenen Touren aus der Tourenplanung und setzt die Preise aus der Preisliste der Firma ein.
  - *je Einsatzart:* eine Position pro Einsatzart (Anzahl × Preis), *je Tour:* eine Position pro Tour mit den gefahrenen Tagen.
  - Preise werden zugeordnet: gleiche Bezeichnung wie die Einsatzart, sonst die einzige Tourpauschale der Firma. Es gilt der Preis, der am Ende des Zeitraums gültig war. In der Vorschau lässt sich jeder Preis ändern; fehlt einer, wird er in der Rechnung nachgetragen.
  - Heißt der Auftraggeber in der Tourenplanung anders als die Firma, wählt man ihn in der Vorschau aus.
  - Gibt es für Firma und Zeitraum schon eine Rechnung, weist die Vorschau darauf hin.
  - Die Rechnung wird als Entwurf angelegt und kann wie jede andere geändert, gedruckt und verschickt werden.

## Neu in 2.4 (Windows-App 2.4, Android-App 3.1, Web-App)

Kein neues Cloud-Skript nötig. Die Tourenplanung läuft weiter mit ihrem eigenen Google-Skript und ihren eigenen Daten – nichts wurde umgezogen oder kopiert, darum fehlt auch nichts.

- **Reiter „Touren“:** die komplette Tourenplanung (Tagesplan, Teams, Stammdaten, Abwesenheiten, Monatsübersicht, Auftraggeber-Bericht, PDF/Excel, WhatsApp) direkt im CRM. Anmelden mit der PIN der Tourenplanung; sie gilt, bis die App geschlossen wird.
- **Stammdaten abgleichen** (⚙ Einstellungen → Tourenplanung oder Fuhrpark → *Mit Tourenplanung abgleichen*): Fahrzeuge der Tourenplanung kommen in den Fuhrpark, Fahrzeuge aus dem Fuhrpark in die Tourenplanung, Auftraggeber werden als Firmen angelegt (auf Wunsch auch umgekehrt). Vorher zeigt die App, was passiert; nichts wird doppelt angelegt.
- **Mitarbeiter** (Fuhrpark → *Mitarbeiter*): wer heute auf welchem Fahrzeug und welcher Tour ist, wer abwesend ist, mit Telefonnummer zum Antippen. Die Namen werden im Fall-Formular und beim Fahrzeug als Fahrer vorgeschlagen.
- **Fahrzeug:** zeigt die heutige Tour mit Team.
- **Firma:** zeigt die Touren dieses und letzten Monats laut Tourenplanung.
- **Schadensfall:** im Formular unter *Transport* „Aus der Tourenplanung übernehmen“ – Fahrzeug, Tour, Fahrer, Beifahrer und Auftraggeber vom Schadenstag werden eingetragen.
- Aufgeräumt: doppelter Programmcode zusammengefasst, alte Kopien der Web-App aus dem Hauptordner des Repositorys entfernt.

## Android-App 3.0 – neu aufgebaut

Die Android-App ist ab 3.0 neu gebaut: Sie zeigt dieselbe Oberfläche wie die Web- und die Windows-App und hat damit **alle Funktionen** – Firmen, Schäden, Termine, Finanzen (Angebote, Rechnungen, Zahlungen, Mahnungen), Fuhrpark (mit Import), Übersicht und Postfach mit zwei Mail-Konten. Der Android-Teil ergänzt, was nur die App kann: E-Mail direkt per IMAP/SMTP, Fotos mit der Kamera, Dateien öffnen und im Ordner *Download/Ayse CRM* speichern, PDF für Rechnungen, Drucken über Android und Benachrichtigungen bei neuen Mails.

**Umstieg von 2.x:**
1. Die neue `Code.gs` muss bereitgestellt sein (siehe oben).
2. Die alte App deinstallieren (die neue ist anders signiert und lässt sich sonst nicht installieren).
3. Die neue APK installieren (GitHub → Actions → *Android* → Paket *Ayse-CRM-apk*), öffnen, Cloud-PIN eingeben – alle Daten kommen aus der Cloud.
4. Unter ⚙ → E-Mail die beiden Postfächer neu einrichten (Passwörter werden aus Sicherheitsgründen nicht übernommen).

Die Zurück-Taste schließt erst offene Fenster, geht dann eine Ansicht zurück und verlässt zuletzt die App.

## Bekannte Grenzen

- Mails ohne Fall liegen nur auf dem Gerät, das sie abgerufen hat. Mails zu Firmen erscheinen über
  die Kontakthistorie aber auf allen Geräten.
