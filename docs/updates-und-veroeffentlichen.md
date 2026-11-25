# Updates und Veröffentlichen

## Überblick

| Was | Quelle | Wie es zu den Nutzern kommt |
|---|---|---|
| Oberfläche (Web) | `web/` | Inhalt nach `aysetransport/schaden` (Branch `main`) kopieren → GitHub Pages. Browser laden beim nächsten Öffnen neu; die Apps holen sie per In-App-Update |
| Cloud-Skript | `cloud/Code.gs` | Liegt als `web/cloud/Code.gs` mit auf GitHub Pages; die Cloud aktualisiert sich selbst (siehe [cloud.md](cloud.md)) |
| Windows-App | `desktop/` | Workflow *Windows-App* baut den Installer und legt ihn in den Release `windows-app` (in `Test` und `schaden`); die App aktualisiert sich selbst |
| Android-App | `app/` | Workflow *Android* baut die APK und legt sie in den Release `android-app` (in `Test` und `schaden`); die App bietet das Update an |
| Vorschuss-App | `web/vorschuss/` | Mit der Oberfläche auf GitHub Pages (`…/schaden/vorschuss/`) |

## Branches

- **Standard-Branch** `claude/android-schadensfall-app-krncja` in `aysetransport/Test`: von hier wird veröffentlicht;
  Pushes starten die Workflows.
- Arbeits-Branches (z. B. `claude/gifted-pasteur-4gyr4x`): Entwicklung; fertige Commits werden per
  `git cherry-pick` auf den Standard-Branch übernommen.

## Schritte einer Veröffentlichung

```bash
# 1. Auf dem Standard-Branch (mit den neuen Commits)
node tools/app-update.js          # setzt WEB_STAND/WEB_VERSION in index.html, schreibt app-update.json und cloud-update.json
git add -A && git commit -m "… ; Prüfsummen"
git push origin HEAD:claude/android-schadensfall-app-krncja
```

```bash
# 2. GitHub Pages: Inhalt von web/ (ohne test/) in den Klon von aysetransport/schaden
cp -r web/. ../schaden/ && rm -rf ../schaden/test
cd ../schaden && git add -A && git commit -m "…" && git push origin HEAD:main
```

3. Workflows auf GitHub prüfen (Actions): *Web-App* muss grün sein, bei App-Änderungen auch *Windows-App* bzw.
   *Android*.

Wichtig:

- `tools/app-update.js` **nach** allen Änderungen an `web/` ausführen – die Apps verwerfen Dateien, deren Prüfsumme
  nicht zu `app-update.json` passt. Danach nichts mehr in `web/` ändern, ohne es erneut auszuführen.
- Die Version der Oberfläche kommt aus `desktop/package.json` (`version`). Für ein **Windows-App-Update** muss sie
  steigen (und `desktop/**` muss sich ändern, damit der Workflow läuft).
- `node tools/app-update.js 2` setzt `minNativ` – nur wenn die neue Oberfläche Funktionen braucht, die erst eine neue
  App mitbringt (dann bekommen alte Apps ein App-Update statt der neuen Oberfläche).
- Ändert sich, was der Service Worker vorhält (`web/sw.js`, Liste `DATEIEN`), `SPEICHER` dort hochzählen.
- GitHub Pages braucht nach dem Push oft 1–10 Minuten, bis die neue Fassung überall ausgeliefert wird.

## Workflows (`.github/workflows/`)

| Workflow | Startet bei Änderungen in | Tut |
|---|---|---|
| `web.yml` (*Web-App*) | `web/**`, `cloud/**` | Cloud-Tests, Browser-Tests (Playwright/Chromium), 15 min Grenze |
| `windows.yml` (*Windows-App*) | `desktop/**` | Tests (Mail, gestartete App), Installer bauen, installiert prüfen, Release `windows-app` |
| `android.yml` (*Android*) | `app/**`, Gradle-Dateien | Lint, Unit-Tests, Debug- und Release-APK, Release `android-app` (nur mit Signaturschlüssel) |

Alle laufen nur auf dem Standard-Branch (bzw. von Hand über „Run workflow“).

## Secrets (GitHub → Settings → Secrets and variables → Actions in `aysetransport/Test`)

| Secret | Zweck |
|---|---|
| `SCHADEN_TOKEN` | Fine-grained Token, nur `aysetransport/schaden`, *Contents: Read and write* – damit die Workflows die Releases für die App-Updates dort ablegen dürfen |
| `SIGNATUR_BASE64`, `SIGNATUR_PASSWORT`, `SIGNATUR_ALIAS` | Signaturschlüssel der Android-App (PKCS12 als Base64). **Nie wechseln** und außerhalb von GitHub sichern (Passwort-Manager) |

Das Repository ist öffentlich: Schlüssel und Passwörter gehören nie in den Code (`.gitignore` sperrt `*.p12`,
`*.jks`, `*.keystore`).

## Versionen

- Oberfläche/Windows: `desktop/package.json` → `version` (z. B. 4.22.0); in der Oberfläche `window.WEB_VERSION`.
- Android: `app/build.gradle.kts` → `versionName`; `versionCode` zählt der Workflow automatisch hoch.
- Cloud: `API_VERSION` in `cloud/Code.gs` (Schnittstelle), Mindestversion der Oberfläche `CLOUD_API`.
