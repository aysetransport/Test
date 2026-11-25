# Tests

| Befehl | Prüft | Dauer |
|---|---|---|
| `node --test cloud/test/cloud.test.js` | Cloud-Skript in einer nachgebauten Google-Umgebung (`cloud/test/gas-umgebung.js`): PIN, Benutzer, Rollen, Abgleich, Löschen, Anlagen, Selbst-Update, Vorschüsse | Sekunden |
| `node --test web/test/web.test.js` | Die Oberfläche im echten Browser (Playwright/Chromium) gegen das echte Cloud-Skript (`cloud/test/mock-server.js` stellt es als lokalen Server bereit): alle Bereiche, Handy- und PC-Layout, Glas, Postfach (IMAP nachgebildet), Rechnungen, E-Rechnung, Touren, Personal, Vorschuss-App … | 2–5 min |
| `cd desktop && npm ci && xvfb-run -a npm test` | Windows-App: Mail gegen GreenMail (Java), Web-Update, gestartete App (Fenster, Unterfenster, Scannen über Ordner `test/scans`) | einige Minuten |
| `./gradlew testDebugUnitTest` | Android: Robolectric, Postfach gegen GreenMail, Bildschirmfotos (Roborazzi) | einige Minuten |
| `python3 tools/erechnung-pruefen.py rechnung.xml <regeln>` | Eine XRechnung gegen Schema und Schematron der KoSIT | Sekunden |

Voraussetzungen: Node.js 22, Playwright mit Chromium (`npm install playwright && npx playwright install chromium`),
für Windows-Tests `xvfb-run` unter Linux, für Android Java 21 und das Android-SDK.

## Hinweise

- **Nicht zwei Testläufe gleichzeitig** starten – sie teilen Ports und Browser und werden dann unzuverlässig.
  Hängenden Lauf beenden: `pkill -f web/test/web.test.js`.
- Jeder Browser-Test hat 120 s Zeit (Wrapper `test` am Anfang von `web.test.js`). Auf GitHub beendet
  `--test-force-exit` den Lauf sicher nach dem letzten Test.
- Bekannt wackelig (selten, ohne Codeänderung): der Finanz-Test mit Tankbelegen in Massen und ein Cloud-Login-Schritt.
  Bei einem einzelnen roten Lauf: Workflow neu starten; bleibt er rot, ist es ein echter Fehler.
- Die Tests nutzen nie die echte Cloud: Aufrufe an `script.google.com` werden blockiert.
- Bildschirmfotos zur Kontrolle: mit Playwright `page.screenshot()` – die Tests selbst prüfen Maße (z. B. dass
  nichts seitlich übersteht und Beschriftungen ganz zu sehen sind).

## Wo Fehler stehen

- Lokal: Ausgabe von `node --test` (`not ok …` mit Zeile und Erwartung).
- GitHub: Actions → Lauf → fehlgeschlagener Schritt; bei Android zusätzlich das Artefakt „Berichte“.
