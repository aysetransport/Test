# Vorschuss-App

Eigene kleine App für den Chef, um Vorschüsse an Mitarbeiter nachzuhalten.

- **Adresse:** https://aysetransport.github.io/schaden/vorschuss/ (Quelle `web/vorschuss/index.html`, `manifest.webmanifest`, `sw.js`)
- **Aufs iPhone:** in Safari öffnen → Teilen → „Zum Home-Bildschirm“.
- **Anmeldung:** nur der **Hauptadmin** (Benutzer + Passwort aus Ayse CRM) oder die Cloud-PIN. Büro und andere
  Admins werden abgewiesen (im Cloud-Skript durchgesetzt).

## Daten

- Mitarbeiterstamm kommt **lesend** aus dem CRM (nur Name, Personalnummer, aktiv, Rolle – keine Lohndaten).
- Die Vorschüsse liegen getrennt in `Schadensfaelle/vorschuesse.json` (Google Drive), mit eigener Tagessicherung
  in `sicherungen-vorschuesse/`. Sie tauchen weder in `daten.json` noch im Abgleich der CRM-Apps auf.
- Eintrag: `mitarbeiterId`, `art` (VORSCHUSS oder RUECKZAHLUNG), `weg` (BAR, UEBERWEISUNG, LOHN), `datum`,
  `betragCent`, `notiz`, `geaendertAm`, `geloescht`. Offener Betrag = Vorschüsse − Rückzahlungen.
- Auf dem Gerät: `localStorage['vorschuss-v1']` (Anmeldung, zuletzt geladener Stand, noch nicht übertragene
  Buchungen). Ohne Netz gebuchte Einträge werden als „wartet“ markiert und später übertragen.

## Schnittstelle

`vorschussLaden()` und `vorschussSpeichern(liste)` im Cloud-Skript (ab API-Version 18). Fehlen sie, versucht die App
einmal das Cloud-Selbst-Update und zeigt sonst den Grund in einem roten Kasten mit „Erneut versuchen“.

## Störungen

| Meldung | Lösung |
|---|---|
| „Die Cloud kennt die Vorschüsse noch nicht …“ | Cloud-Skript aktualisieren (⚙ → Cloud-Skript in Ayse CRM) bzw. von Hand einspielen ([cloud.md](cloud.md)) |
| „Die Cloud darf sich noch nicht selbst aktualisieren …“ | Einmalig `selbstUpdateEinrichten` im Skript-Editor ausführen und Berechtigungen erlauben ([cloud.md](cloud.md), Einrichten) |
| „Nur der Chef (Hauptadmin) hat Zugriff“ | Mit dem Hauptadmin-Login anmelden; Hauptadmin festlegen unter ⚙ → Benutzer (Admin, „Hauptadmin“) |
| „Keine Verbindung zur Cloud“ | Netz prüfen; gebuchte Einträge bleiben erhalten und gehen später raus |
