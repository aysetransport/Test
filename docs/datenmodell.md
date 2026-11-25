# Datenmodell

Die Daten liegen in der Cloud in **`Schadensfaelle/daten.json`** (Google Drive) und auf jedem Gerät im Stand `S`.
Beide haben dieselben Tabellen. Maßgeblich für erlaubte Felder ist das Cloud-Skript: es übernimmt nur bekannte
Felder (`cloud/Code.gs`: `FALL_TEXTE`/`FALL_DATEN`, `bereinigeVerlauf_`, `bereinigeAnlage_`,
`uebernehmeAuftraggeber_`, `CRM_TABELLEN`). Ein neues Feld muss deshalb **immer auch im Cloud-Skript** eingetragen
werden, sonst geht es beim Abgleich verloren.

## Gemeinsame Felder jedes Datensatzes

| Feld | Bedeutung |
|---|---|
| `id` | Kennung (UUID, klein). Nur Hex-Ziffern und Bindestriche erlaubt (`kennung_`) |
| `geaendertAm` | Zeitpunkt der letzten Änderung (ISO, UTC). **Spätere Änderung gewinnt** beim Abgleich |
| `geloescht` | `true` = Löschvermerk. Gelöschtes bleibt gelöscht (wird nicht wiederbelebt) |
| `rev` | (nur Cloud) Revision, bei der der Datensatz zuletzt geändert wurde – Grundlage für „alles seit Revision X“ |
| `firmaId` | (CRM-Tabellen) Zugehörige Firma; leer bei Tabellen ohne Firma (Fuhrpark, Personal, Touren …) |
| `_dirty`, `_n` | (nur Gerät) noch nicht hochgeladen bzw. Änderungszähler – werden nie übertragen |

Feldarten in `CRM_TABELLEN`: `text`, `wort:<Vorgabe>` (Aufzählung in GROSSBUCHSTABEN, unbekannt → Vorgabe),
`datum` (JJJJ-MM-TT), `cent` (ganze Zahl ≥ 0; Geld immer in **Cent**, Liter/Stunden × 100), `bool`.

## Tabellen

### Schadensfälle

| Tabelle | Inhalt |
|---|---|
| `faelle` | Schadensfall: `fallnummer` (JJJJ-NNN), Geschädigter (`kundeName`, `kundeFirma`, Kontakt), Auftraggeber (Name, Kontakt, `auftraggeberUuid` → Firma), `tour`/`tourId`, `fahrzeug`/`fahrzeugId`, `fahrer`/`fahrerId`, Beifahrer, `schadensart` (WARE …), `status` (NEU …), `beschreibung`, `schadensort`, Daten (`schadensdatum`, `meldedatum`, `wiedervorlageAm`, `zahlungsfrist`), `wartetAuf`, `forderungCent`, `vergleichCent`, `erstelltAm` |
| `verlauf` | Einträge im Verlauf eines Falls (`fallId`, `zeitpunkt`, `typ` wie EMAIL_AUSGANG, ANRUF, NOTIZ …, `text`). Werden nur angehängt oder gelöscht |
| `anlagen` | Beschreibung einer Datei (`fallId` = Fall **oder** Tour/Fahrzeug/Mitarbeiter …, `anzeigename`, `mimeType`, `groesse`, `vorhanden`). Die Datei selbst liegt in Drive (`anlagen/`), die Drive-ID (`dateiId`) bleibt in der Cloud |

### CRM

| Tabelle | Inhalt |
|---|---|
| `auftraggeber` | Firma: `name`, `rechtsform`, `status` (AKTIV, INTERESSENT …), Kontakt, `notizen`, `schlagworte`, `kundennummer`, `ustId`, `zahlungszielTage`, Bank, `leitwegId`/`eRechnungEmail` (E-Rechnung), `aliase` (frühere Namen) |
| `adressen` | `art` (HAUPTSITZ, RECHNUNG, LAGER …), Straße, PLZ, Ort |
| `ansprechpartner` | Name, Thema, Telefon, E-Mail |
| `preise` | Bezeichnung (z. B. Einsatzart), `betragCent`, Einheit, `gueltigAb` |
| `aktivitaeten` | Kontakthistorie: `datum`, `art` (ANRUF, EMAIL, BESUCH …), `text`, `kontakt` |
| `aufgaben` | Wiedervorlagen zu Firmen (`faelligAm`, `erledigt`) |
| `dokumente` | Links zu Dokumenten |
| `chancen` | Verkaufschancen (`wertCent`, `phase`, `erwartetAm`) |
| `belege` | Angebote, Rechnungen, Gutschriften: `art`, `nummer`, `datum`, `faelligAm`, `status` (ENTWURF, OFFEN, TEILBEZAHLT, BEZAHLT …), `positionen` (JSON-Text), `mwstProzent`, `bezahltCent`, `mahnstufe`, E-Rechnung (`leistungVon/Bis`, `bestellnummer`), Korrektur (`korrekturVon`, `ersetztDurch`), `gutschriftverfahren` |
| `eingangsrechnungen` | Rechnungen/Gutschriften von Lieferanten (ohne Firma): `lieferant`, `nummer`, `nettoCent`, `mwstCent`, `kategorie`, `status`, `eRechnung` |

### Fuhrpark

| Tabelle | Inhalt |
|---|---|
| `fahrzeuge` | Kennzeichen, Daten aus dem Fahrzeugschein, Fristen (`huBis`, `spBis`, `uvvBis`, `tachoBis` …), Kosten (Steuer, Versicherung, Leasing), `aliase`, `leihfahrzeug` |
| `fahrzeugeintraege` | Fahrzeugakte: `fahrzeugId`, `art` (WARTUNG, TANKEN, REPARATUR, SCHADEN …), `datum`, `kmStand`, `betragCent`, `menge` |

### Personal und Disposition

| Tabelle | Inhalt |
|---|---|
| `mitarbeiter` | Stammdaten, Rolle (FAHRER, BEIFAHRER, MONTEUR …), Fristen (Führerschein, Fahrerkarte, Modul 95 …), Anstellung, `stundenlohnCent`, Urlaub, `aliase`, `fahrerkarteNr` |
| `personaleintraege` | Personalakte: `art` (GESPRAECH, SCHULUNG, UNTERWEISUNG, ABMAHNUNG, **LOHN** …); bei LOHN `bruttoCent`, `nettoCent`, `kostenCent`, `stunden` (nur Admin sieht Lohn) |
| `abwesenheiten` | `mitarbeiterId`, `von`, `bis`, `grund` (Urlaub, Krank …) |
| `einsatzarten` | Name, Anzahl Beifahrer, Monteur nötig |
| `touren` | Tour eines Tages: `datum`, `reihenfolge`, `kennzeichen`, `tourNr`, Auftraggeber, `einsatzart`, `fahrerId`, `beifahrerIds` (Komma-Liste), `status` (GEPLANT, UNTERWEGS, ERLEDIGT, STORNIERT), Zeiten, km, Endkunde/Lieferavis (`kundeName` …, `zeitfenster`, `statusCode`), Bewertung, `belegId` (abgerechnet) |
| `stopps` | Mehrere Kunden je Tour: `tourId`, `reihenfolge`, `art`, Kunde, Adresse, `status`, `auftragNr`, `betragCent`, `eta` |
| `zeiten` | Arbeitszeit: `mitarbeiterId`, `datum`, `beginn`/`ende` (HH:MM), `pauseMin`, `art`, `quelle` (ERFASST, STEMPEL, PLAN), `bestaetigt` |
| `tachotage` | Ein Tag aus der Fahrerkarte: Minuten je Tätigkeit, Fahrzeuge, km, `aktivitaeten` (JSON) |
| `aufgabenIntern` | Interne Aufgaben: `titel`, `faelligAm`, `zustaendigId`, `prioritaet`, `erledigt`, Bezug (`bezugArt`, `bezugId`) |

## Nicht in `daten.json`

| Was | Wo |
|---|---|
| E-Mails | Nur auf dem Gerät (IndexedDB `mails:index`), Quelle ist das Postfach selbst |
| Einstellungen | `S.einstellungen` auf jedem Gerät (z. B. Firmendaten, Darstellung, Mail-Regeln) |
| Benutzer, PIN, Sitzungen | Skript-Eigenschaften der Cloud (siehe [cloud.md](cloud.md)), nur gehasht |
| Vorschüsse | `Schadensfaelle/vorschuesse.json`, nur für den Hauptadmin (siehe [vorschuss-app.md](vorschuss-app.md)) |
| Mail-Passwörter | Verschlüsselt auf dem Gerät (Windows: DPAPI/safeStorage, Android: Schlüsselspeicher) |

## Kopf von `daten.json`

`{ "kennung": "<UUID des Bestands>", "rev": <laufende Revision>, "faelle": {id: …}, … }`

Die **Kennung** ändert sich nur, wenn `daten.json` neu angelegt wird (z. B. Ordner gelöscht). Geräte mit anderer
Kennung bekommen dann den ganzen Bestand und laden ihre eigenen Daten neu hoch.
