# Cloud (Google Apps Script)

Quelle: `cloud/Code.gs` und `cloud/appsscript.json`. Läuft als Web-App im Google-Konto der Firma
(„Ausführen als: Ich“, „Zugriff: Jeder“). Alle Apps sprechen mit derselben Adresse (endet auf `/exec`); die Standard-
Adresse steht in `web/index.html` als `window.API_URL`, abweichend eingetragen unter ⚙ → Cloud.

## Aufruf

`POST` mit Text-Inhalt `{"fn": "<Name>", "args": [<PIN oder Token>, …]}` → Antwort `{"ok": …}` oder
`{"error": "<Meldung>"}` (nie eine HTML-Fehlerseite, siehe `doPost`). `GET` auf die Adresse zeigt nur
„Schadensfälle-Cloud läuft“ – damit kann man prüfen, ob die Adresse stimmt.

| Ohne Anmeldung (`OFFEN`) | Zweck |
|---|---|
| `status` | API-Version, ob PIN/Benutzer eingerichtet sind, ob PIN-Anmeldung aus ist |
| `pinFestlegen(pin)` | Nur beim allerersten Verbinden (mindestens 8 Zeichen) |
| `einloggen(login, passwort)` | Benutzer-Anmeldung → Sitzungs-Token `T:…` (90 Tage, verlängert sich bei Nutzung) |
| `sendungsstatus(code)`, `bewerten(code, sterne, text)` | Öffentliche Statusseite für Endkunden (`web/status.html`) |

| Mit PIN/Token (`GESCHUETZT`) | Zweck |
|---|---|
| `anmelden` | Gerät anmelden (bei PIN: liefert Token), Kennung und Stand |
| `stand` | Schnell: Kennung und Revision (ohne `daten.json` zu lesen) |
| `sync(anfrage)` | **Abgleich** (siehe unten) |
| `anlageHochladen(id, base64)`, `anlageLaden(id)` | Dateien zu Anlagen (max. 25 MB) |
| `benutzerListe`, `benutzerSpeichern`, `benutzerLoeschen`, `abmelden` | Benutzerverwaltung (Admin) |
| `pinAnmeldung(an)` | Anmeldung mit Cloud-PIN ab-/einschalten (Hauptadmin) |
| `cloudUpdateInfo`, `cloudAktualisieren`, `cloudAutoUpdate` | Selbst-Update (Hauptadmin) |
| `vorschussLaden`, `vorschussSpeichern` | Vorschuss-App (Hauptadmin) |

## Anmeldung, Rollen, Rechte

- **Cloud-PIN** = Admin mit allen Rechten (gilt als Hauptadmin). Gespeichert nur als Hash (`PIN_HASH`, `PIN_SALZ`).
  Nach 8 falschen Eingaben gesperrt, jede weitere Sperre doppelt so lang (bis 7 Tage).
- **Benutzer** (⚙ → Benutzer): Login + Passwort, Rolle **ADMIN** (alles), **DISPO** (alles außer Benutzerverwaltung
  und Lohndaten), **FAHRER** (nur eigene Touren der letzten 45 Tage, Team, zugehörige Fälle, eigene Zeiten,
  Abwesenheiten, Aufgaben). Ein Admin kann **Hauptadmin** sein: verwaltet andere Admins, Cloud-Update, Vorschüsse.
- Die Rechte setzt **das Skript** durch, nicht die Oberfläche (`pruefeZugang_`, `fahrerSicht_`, `fahrerEingang_`,
  Filter in `sync`). Ein Fahrer kann also auch mit manipulierter App nichts anderes sehen.
- Ist die PIN-Anmeldung abgeschaltet (`PIN_AUS`), geht nur noch Benutzer-Login. Notfall: im Skript-Editor
  `notfallPinAnmeldungEinschalten` ausführen.

## Abgleich (`sync`)

1. Das Gerät schickt `{seit, kennung, <tabelle>: [geänderte Datensätze]}`.
2. Das Skript sperrt (LockService, bis 25 s), lädt `daten.json` und übernimmt jeden Datensatz:
   - nur bekannte Felder, Typen geprüft (`bereinige…`, `CRM_TABELLEN`);
   - **spätere Änderung gewinnt** (`geaendertAm`); Zukunftszeiten werden auf „jetzt + 5 min“ gekappt;
   - **gelöscht bleibt gelöscht**; schickt ein Gerät Altes, bekommt es den Löschvermerk zurück;
   - jede echte Änderung erhöht `rev` und bekommt diese Revision.
3. Gab es Änderungen, wird `daten.json` geschrieben (vorher einmal täglich gesichert).
4. Antwort: alles mit `rev > seit` (bei anderer Kennung: alles) – Fahrer immer ihren vollständigen, gefilterten
   Ausschnitt, Disposition ohne Lohndaten.

Das Gerät (`abgleich()` in `web/index.html`) übernimmt die Antwort, löscht `_dirty` der bestätigten Datensätze und
merkt `S.seit = rev`. Ändern zwei Geräte denselben Datensatz fast gleichzeitig, gilt der ganze Datensatz mit der
späteren Änderung.

## Ablage in Google Drive

```
Schadensfaelle/
  daten.json                   alle Daten (siehe datenmodell.md)
  vorschuesse.json             Vorschüsse (nur Hauptadmin)
  anlagen/                     Dateien der Anlagen (Dateiname = Kennung)
  sicherungen/                 daten-JJJJ-MM-TT.json, die letzten 14 Tage
  sicherungen-vorschuesse/     vorschuesse-JJJJ-MM-TT.json, die letzten 14 Tage
```

## Skript-Eigenschaften (Projekteinstellungen → Skripteigenschaften)

| Schlüssel | Inhalt |
|---|---|
| `PIN_HASH`, `PIN_SALZ`, `FEHLVERSUCHE` | Cloud-PIN und Sperre |
| `PIN_AUS` | `1` = PIN-Anmeldung abgeschaltet |
| `BENUTZER_<id>` | Ein Benutzer (Passwort nur gehasht) |
| `SITZUNG_<hash>` | Eine Sitzung (Token nur gehasht) |
| `FEHL_<login>`, `FEHL_?` | Fehlversuche bei der Benutzer-Anmeldung |
| `ORDNER_ID`, `DATEN_ID`, `VORSCHUSS_ID`, `STAND` | Gemerkte Drive-Ordner/Dateien und Stand (beschleunigt) |
| `LETZTE_SICHERUNG`, `LETZTE_SICHERUNG_VORSCHUSS` | Datum der letzten Tagessicherung |
| `CODE_STAND`, `AUTO_UPDATE`, `UPDATE_FEHLER`, `BEREITSTELLUNG` | Selbst-Update |

Eigenschaften **nicht von Hand ändern** – außer zur Fehlerbehebung (siehe [fehlerbehebung.md](fehlerbehebung.md)).

## Einrichten (neues Google-Konto)

1. https://script.google.com/home/usersettings → **Google Apps Script API** einschalten (für das Selbst-Update).
2. script.google.com → **Neues Projekt**. Projekteinstellungen → „Manifestdatei appsscript.json im Editor anzeigen“.
3. `Code.gs` und `appsscript.json` mit dem Inhalt aus `cloud/` ersetzen, speichern.
4. Funktion **`selbstUpdateEinrichten`** auswählen → **Ausführen** → Berechtigungen erlauben (Konto wählen →
   „Erweitert“ → „Zu … wechseln“ → „Zulassen“).
5. **Bereitstellen → Neue Bereitstellung → Web-App**: Ausführen als *Ich*, Zugriff *Jeder* → Adresse kopieren.
6. In der App ⚙ → Cloud die Adresse eintragen (falls anders als `window.API_URL`) und eine PIN festlegen.

**Neue Version von Hand einspielen** (falls das Selbst-Update nicht geht): Schritt 3, dann **Bereitstellen →
Bereitstellungen verwalten → ✎ → Version: Neue Version → Bereitstellen**. Die Adresse bleibt gleich.

## Selbst-Update

Die Cloud holt sich neue Fassungen selbst von https://aysetransport.github.io/schaden/ (`cloud-update.json`
mit Prüfsumme, `cloud/Code.gs`, `cloud/appsscript.json`): von Hand über ⚙ → Cloud-Skript (Hauptadmin),
automatisch nachts um 3 Uhr (wenn eingeschaltet), und die Vorschuss-App stößt es an, wenn ihr Funktionen fehlen.

Ablauf (`selbstUpdate_`): Prüfsumme prüfen → Code über die Apps-Script-API ersetzen → Version anlegen → dieselbe
Bereitstellung auf die neue Version umstellen → testen, ob sie antwortet; sonst zurück auf die vorherige.

Voraussetzungen: Apps Script API eingeschaltet **und** die Berechtigungen aus `appsscript.json`
(`script.external_request`, `script.projects`, `script.deployments` …) einmal erteilt – sonst Fehler „nicht die
erforderliche Berechtigung … UrlFetchApp.fetch“ (Lösung: Einrichten, Schritte 1–4).

## API-Version

`API_VERSION` im Skript zählt Änderungen an der Schnittstelle. Die Oberfläche verlangt eine Mindestversion
(`CLOUD_API` in `web/index.html`); ist das Skript älter, meldet sie „Das Cloud-Skript ist veraltet“. Bei jeder
Erweiterung des Datenmodells: Feld im Skript eintragen, `API_VERSION` erhöhen, ggf. `CLOUD_API` anpassen, Tests
in `cloud/test/cloud.test.js` ergänzen.

## Werkzeuge im Skript-Editor (Funktion auswählen → Ausführen)

| Funktion | Wann |
|---|---|
| `selbstUpdateEinrichten` | Einmalig nach dem Einrichten bzw. wenn das Selbst-Update an Berechtigungen scheitert |
| `pinZuruecksetzen` | PIN vergessen – danach legt das nächste Gerät eine neue PIN fest |
| `notfallPinAnmeldungEinschalten` | PIN-Anmeldung wieder einschalten (z. B. Hauptadmin-Passwort verloren) |
