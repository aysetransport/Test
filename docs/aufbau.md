# Aufbau

## Bestandteile

```
                ┌──────────────────────────────────────────────┐
                │  Oberfläche: web/index.html (eine Datei)     │
                │  HTML + CSS + JavaScript, ohne Build-Schritt │
                └──────────────────────────────────────────────┘
                   │ läuft in …
     ┌─────────────┼───────────────────┬────────────────────────┐
     ▼             ▼                   ▼                        ▼
  Browser /     Windows-App         Android-App              Vorschuss-App
  installierte  (desktop/,          (app/, WebView           (web/vorschuss/,
  Web-App       Electron)           + Brücke android.js)     nur Chef)
     │             │                   │                        │
     └─────────────┴─────── HTTPS (POST, JSON) ─────────────────┘
                                   ▼
                  Cloud-Skript (cloud/Code.gs, Google Apps Script)
                                   ▼
                  Google Drive: Schadensfaelle/daten.json, anlagen/, sicherungen/
```

- **Oberfläche** – `web/index.html` enthält alles: Darstellung (CSS, Apple-Design mit „Liquid Glass“), Logik und
  Bedienung. Es gibt keinen Build und keine Bibliotheken außer `web/pdfjs/` (PDF-Anzeige).
- **Windows-App** – `desktop/`: Electron. Lädt die Oberfläche aus `desktop/app/` (Kopie von `web/`, erzeugt mit
  `npm run vorbereiten`) bzw. ein geprüftes Update davon. Ergänzt E-Mail (IMAP/SMTP), PDF, Scanner und
  Texterkennung, Benachrichtigungen, mehrere Fenster.
- **Android-App** – `app/`: Kotlin, eine Activity mit WebView. Die Oberfläche kommt aus den Assets bzw. einem
  geprüften Update. Ergänzt E-Mail, PDF/Drucken, SMS, Benachrichtigungen, Dateiauswahl/Kamera.
- **Cloud** – `cloud/Code.gs`: Google Apps Script als Web-App. Prüft Anmeldung und Rechte, gleicht ab, speichert in
  Google Drive. Siehe [cloud.md](cloud.md).
- **Vorschuss-App** – `web/vorschuss/`: eigene kleine Web-App für den Chef. Siehe [vorschuss-app.md](vorschuss-app.md).

## Ordner

| Pfad | Inhalt |
|---|---|
| `web/index.html` | Die gesamte Oberfläche |
| `web/sw.js` | Service Worker: Web-App startet auch ohne Netz (Netz zuerst, sonst Zwischenspeicher) |
| `web/manifest.webmanifest`, `web/icon-*.png`, `web/logo.svg` | App-Installation und Symbole (`node tools/icons.js` erzeugt die PNGs aus `logo.svg`) |
| `web/app-update.json`, `web/cloud-update.json` | Beschreibung der veröffentlichten Fassung mit SHA-256-Prüfsummen (erzeugt von `tools/app-update.js`) |
| `web/cloud/Code.gs`, `web/cloud/appsscript.json` | Kopie des Cloud-Skripts für dessen Selbst-Update |
| `web/sprachen.json` | Übersetzungen der Bedienoberfläche (für Fahrer/Helfer) |
| `web/status.html` | Öffentliche Seite „Sendungsstatus“ für Endkunden (Statuslink im Lieferavis) |
| `web/vorschuss/` | Vorschuss-App |
| `web/test/web.test.js` | Browser-Tests (Playwright) |
| `cloud/Code.gs`, `cloud/appsscript.json` | Cloud-Skript (Quelle) |
| `cloud/test/` | Tests des Cloud-Skripts in einer nachgebauten Google-Umgebung (`gas-umgebung.js`, `mock-server.js`) |
| `desktop/` | Windows-App: `main.js` (Hauptprozess), `preload.js` (Schnittstelle zur Oberfläche), `mail.js`, `scan.js`, `webupdate.js`, `test/` |
| `app/` | Android-App (Gradle): `src/main/java/de/aysetransport/schaden/…`, `src/main/assets/android.js`, `src/test/` |
| `tools/` | `app-update.js` (Prüfsummen/Versionen), `icons.js` (App-Symbole aus `logo.svg`), `symbole.js` (Liniensymbole in `index.html` aus Lucide), `erechnung-pruefen.py` (XRechnung prüfen), `schema`/`lade_sch`/`pruefe_sch` (Regeln) |
| `.github/workflows/` | `web.yml`, `windows.yml`, `android.yml` – Tests und Builds bei jedem Push auf den Standard-Branch |
| `docs/` | Diese Dokumentation |

## Wie die Oberfläche arbeitet

### Zustand

Alles, was die App weiß, steht im JavaScript-Objekt **`S`** (Stand): eine Tabelle je Datenart (`S.faelle`,
`S.auftraggeber`, `S.touren`, …; siehe [datenmodell.md](datenmodell.md)), dazu `S.einstellungen`, `S.seit`
(letzte Cloud-Revision), `S.kennung` (Kennung des Cloud-Bestands), `S.apiUrl`, `S.nurLokal`, `S.mails`.

Weitere globale Objekte:

| Objekt | Zweck |
|---|---|
| `ANSICHT` | Was gerade angezeigt wird (Bereich, Filter, Suche, gewählter Tag …) – wird nicht gespeichert |
| `SYNC` | Zustand des Cloud-Abgleichs (läuft, Fehler, zuletzt) |
| `MAIL`, `MAILSPEICHER` | Postfach: Konten, laufender Abruf, Speicherort der Mails |
| `PIN` | Zugang zur Cloud: Cloud-PIN oder Sitzungs-Token (`T:…`) |
| `Aktionen` | Speichern von Datensätzen (Fall, Firma, CRM-Einträge …) mit allem, was dazugehört |

### Änderungen

1. Ein Datensatz wird geändert → der Aufrufer setzt `geaendertAm: jetztIso()`, `merke(tabelle, datensatz)` legt ihn in `S` ab und markiert ihn mit `_dirty` (Löschen: `entferne()` legt einen Löschvermerk `geloescht: true` an).
2. `nachAenderung()` → `speichere()` (Gerät), `zeichne()` (Ansicht), `planeAbgleich()` (Cloud in ~1 s).
3. `abgleich()` schickt alle `_dirty`-Datensätze an die Cloud und übernimmt die Antwort (siehe [cloud.md](cloud.md)).

### Ansicht

- **Route** = Teil der Adresse hinter `#`: `#/` (Liste), `#/fall/<id>`, `#/firma/<id>`, `#/mail/<id>`,
  `#/beleg/<id>`, `#/mitarbeiter/<id>`, `#/fahrzeug/<id>`, `#/einstellungen`, … (`route()`, `gehe()`).
- **`zeichne()`** zeichnet alles neu: Navigation (`zeichneNavi`), linke Spalte (`zeichneListe`), rechte Spalte
  (`zeichneDetail`), Titel (`kopfTitel`). Jeder Bereich hat seine Funktion (`zeichneFirmenliste`, `zeichneTouren`,
  `zeichnePostfach`, `zeichneFinanzen`, …).
- **Dialoge** laufen über ein gemeinsames `<dialog id="dialog">` (`dialog(html)`), Menüs über `menueAn()`,
  Rückfragen über `aktionsblatt()`/`bestaetige()`, Meldungen über `toast()`.
- **Bedienung** läuft fast vollständig über Ereignis-Delegation: ein `click`-Handler je Bereich reagiert auf
  `data-…`-Attribute der Knöpfe (z. B. `data-mail-akt="loeschen"`).

### Bereiche (Navigation)

`uebersicht` (Übersicht), `fahrer` (Meine Touren), `kalender`, `termine`, `aufgaben`, `touren`, `personal`,
`zeiten`, `fuhrpark`, `firmen`, `faelle` (Schäden), `postfach`, `finanzen`, `auswertung` (Statistik) – siehe
`BEREICHE`. Welche jemand sieht, hängt von der Rolle ab (`bereichSichtbar`): Fahrer sehen nur ihre Bereiche.

### Abschnitte in `web/index.html`

Die Datei ist in Abschnitte gegliedert (Suche nach dem Namen):

- **Grundlagen** (Datum, Fachlogik der Schadensfälle) · **Daten im Browser** (Speicher, IndexedDB) · **Cloud**
  (Abgleich, Anmeldung) · **Sprachen** · **Oberfläche** (Rahmen, Navigation, Suche, Übersicht, Dialoge, Formular,
  Einstellungen)
- **CRM: Firmen** · **Angebote, Rechnungen, Zahlstatus** · **Eingangsrechnungen** (auch scannen) · **Tankbelege**
- **Personal** (Liste, Mitarbeiter, Fristen-Matrix, Zusammenführen) · **Tourenplanung** (Prüfung, Teams auffüllen,
  Endkunde/Lieferavis, Stoppliste, Rollkarte, Drucken, Berichte, Rechnung aus Touren)
- **Auswertungen** · **Wirtschaftlichkeit** · **Statistik** · **Feiertage** · **Interne Aufgaben** · **Kalender**
- **Excel (.xlsx) ohne Bibliothek** · **Zeiterfassung** · **Fahrerkarte (Tachograph)** · **Tour: Status,
  Fahrtenbuch, Begleitschein** · **Fahrer-Modus** · **Preiskalkulator** · **Datensicherung** · **Buchhaltung** ·
  **Hinweise beim Start** · **Monatsabrechnung** · **Briefe** · **Fuhrpark**
- **E-Mail** (Postfach wie Outlook, Speicher, Abgleich, Aktionen, Ordner, Suche, Lesebereich, Schreiben, Regeln,
  Einstellungen) · **E-Rechnung (XRechnung/ZUGFeRD)** · **Updates** · **Windows: Rahmen, Kontextmenüs**

## Speicher auf dem Gerät

| Ort | Inhalt |
|---|---|
| `localStorage['schadensfaelle-v1']` | Der Stand `S` (ohne Mails). Ist er zu groß (~5 MB), liegt er in IndexedDB und hier nur ein Vermerk `{__idb: true}` |
| `localStorage['schadensfaelle-pin']` | Cloud-PIN bzw. Sitzungs-Token |
| `localStorage['crm-bereich']`, `['nav-schmal']`, `['crm-sprachen']`, `['stempel']` | Zuletzt geöffneter Bereich, schmale Seitenleiste, Wörterbuch, laufende Stempeluhr (Fahrer) |
| IndexedDB `schadensfaelle` | Dateien: Anlagen (Fotos, PDFs), Mail-Anhänge, `mails:index` (alle Mails), ggf. `stand` (großer Stand) |

Mehrere Fenster (Windows-App) teilen sich diesen Speicher; nur das **Hauptfenster** gleicht mit der Cloud ab und ruft
Mails ab (`NEBENFENSTER`). Andere Fenster erfahren Änderungen über das `storage`-Ereignis.

## Design

Apple Human Interface Guidelines: Systemfarben hell/dunkel (folgen dem Gerät, einstellbar), Systemschrift,
gruppierte Flächen, Pillen-Knöpfe. „Liquid Glass“ nur auf der Bedienebene (Leisten, Menüs, Sheets, Knöpfe):
klare Linse mit Lichtbrechung am Rand (`glasLinsen`, nur Chromium), Glanzlicht folgt Neigung bzw. Maus
(`glasLicht`). Farben und Maße sind CSS-Variablen am Anfang von `index.html` (`--marke`, `--glas`, `--t-*`, `--r-*`).
Bei „Bewegung reduzieren“ / „Transparenz reduzieren“ des Geräts wird abgeschaltet.
