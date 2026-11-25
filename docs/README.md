# Ayse CRM – Dokumentation

Diese Dokumentation erklärt, wie Ayse CRM aufgebaut ist, wie die Teile zusammenspielen und was bei Störungen
zu tun ist. Sie ist für jemanden geschrieben, der die App betreuen muss, ohne sie selbst gebaut zu haben.

| Datei | Inhalt |
|---|---|
| [aufbau.md](aufbau.md) | Überblick: Bestandteile, Ordner, wie eine Ansicht entsteht, Speicher auf dem Gerät |
| [datenmodell.md](datenmodell.md) | Alle Tabellen und Felder, Kennungen, Zeitstempel, Löschen |
| [cloud.md](cloud.md) | Cloud-Skript (Google Apps Script): Einrichtung, Anmeldung und Rollen, Abgleich, Ablage in Google Drive, Selbst-Update |
| [apps.md](apps.md) | Windows-App (Electron) und Android-App (WebView): was sie zusätzlich können, Schnittstellen, Speicherorte |
| [updates-und-veroeffentlichen.md](updates-und-veroeffentlichen.md) | Wie Änderungen zu den Nutzern kommen: Branches, Prüfsummen, GitHub Pages, Workflows, Secrets |
| [tests.md](tests.md) | Welche Tests es gibt, wie man sie startet, was sie abdecken |
| [vorschuss-app.md](vorschuss-app.md) | Die eigene App des Chefs für Vorschüsse |
| [fehlerbehebung.md](fehlerbehebung.md) | **Störungen und Lösungen** – zuerst hier nachsehen |

## In einem Satz

Eine Oberfläche (`web/index.html`) läuft im Browser, in einer Windows-App und in einer Android-App; alle Geräte
gleichen ihre Daten über ein Google-Apps-Script ab, das die Daten als `daten.json` im Google Drive des Firmenkontos
ablegt.

## Wo was liegt

| Repository / Ort | Zweck |
|---|---|
| `aysetransport/Test`, Branch `claude/android-schadensfall-app-krncja` | **Quellcode** (Standard-Branch, von hier wird veröffentlicht) |
| `aysetransport/schaden`, Branch `main` | **Veröffentlichte Web-App** (GitHub Pages: https://aysetransport.github.io/schaden/), Releases `windows-app` und `android-app` für die App-Updates |
| Google Drive des Firmenkontos, Ordner `Schadensfaelle/` | **Daten** (`daten.json`, `vorschuesse.json`, `anlagen/`, `sicherungen/`) |
| script.google.com (Projekt des Cloud-Skripts) | Das laufende Cloud-Skript; seine Adresse endet auf `/exec` |

## Wichtigste Handgriffe

- **Etwas geht nicht:** [fehlerbehebung.md](fehlerbehebung.md).
- **Daten zurückholen:** Google Drive → `Schadensfaelle/sicherungen/` (tägliche Kopie, 14 Tage) – siehe Fehlerbehebung, „Daten verloren“.
- **Neue Version veröffentlichen:** [updates-und-veroeffentlichen.md](updates-und-veroeffentlichen.md).
- **Tests laufen lassen:** [tests.md](tests.md).

## Kommentare im Code

Jede Datei beginnt mit einem Kopfkommentar, jede Funktion hat einen kurzen Kommentar darüber (`/** … */`), der
sagt, *was* sie tut und *warum* – das *wie* steht im Code. Die Oberfläche (`web/index.html`) ist in Abschnitte
gegliedert, die mit `// ====== Name ======` bzw. `// ---------- Name ----------` beginnen; in [aufbau.md](aufbau.md)
steht die Liste. Suchen nach dem Abschnittsnamen führt direkt hin.
