'use strict';
// Brücke zwischen Oberfläche und Hauptprozess: nur diese Funktionen sind von der Seite aus erreichbar.
const { contextBridge, ipcRenderer } = require('electron');

const aufruf = (kanal, ...args) => ipcRenderer.invoke(kanal, ...args).then(r => {
  if (r && r.fehler) { const e = new Error(r.fehler); e.anmeldung = r.anmeldung; throw e; }
  return r && r.ok;
});

contextBridge.exposeInMainWorld('desktop', {
  plattform: process.platform,
  mail: {
    anbieter: () => aufruf('mail:anbieter'),
    // art: 'SCHADEN' (Vorgabe) oder 'ALLGEMEIN'
    konto: art => aufruf('mail:konto', art),
    einrichten: (konfig, passwort, art) => aufruf('mail:einrichten', konfig, passwort, art),
    trennen: art => aufruf('mail:trennen', art),
    sende: (entwurf, art) => aufruf('mail:sende', entwurf, art),
    // Schnittstelle 2: ordner, stand, lade, suche, aktion, ordnerAktion, anhang, quelle, sende
    schnittstelle: 2,
    ruf: (befehl, daten, art) => aufruf('mail:ruf', befehl, daten, art),
  },
  benachrichtige: (titel, text, ziel) => aufruf('benachrichtige', titel, text, ziel),
  oeffneDatei: (name, bytes) => aufruf('datei:oeffnen', name, bytes),
  oeffneExtern: adresse => aufruf('extern:oeffnen', adresse),
  pdf: html => aufruf('pdf:erzeugen', html),
  speichere: (name, base64) => aufruf('datei:speichern', name, base64),
  beiZiel: rueckruf => ipcRenderer.on('ziel', (_e, ziel) => rueckruf(ziel)),
  // Fenster im Windows-Stil: Infos (Windows 11, Mica, Akzentfarbe, dunkel), Titelleiste, Taskleisten-Zähler, Zoom
  fenster: {
    info: () => ipcRenderer.sendSync('fenster:info'),
    beiAenderung: rueckruf => ipcRenderer.on('fenster:info', (_e, info) => rueckruf(info)),
    beiZoom: rueckruf => ipcRenderer.on('fenster:zoom', (_e, f) => rueckruf(f)),
    titelleiste: (farbe, symbol, thema) => aufruf('fenster:titelleiste', farbe, symbol, thema),
    zaehler: (anzahl, png) => aufruf('fenster:zaehler', anzahl, png),
    blinken: () => aufruf('fenster:blinken'),
    zoom: f => aufruf('fenster:zoom', f),
    neu: (bereich, ziel) => aufruf('fenster:neu', bereich, ziel), // Unterfenster: Bereich und ggf. Datensatz (#/beleg/…)
  },
  // Update der App selbst (neues Setup bzw. neue APK)
  app: { pruefen: () => aufruf('app:pruefen'), installieren: () => aufruf('app:installieren') },
  // In-App-Update der Oberfläche
  web: { info: () => aufruf('web:info'), installieren: () => aufruf('web:installieren'), ok: () => aufruf('web:ok'), zuruecksetzen: () => aufruf('web:zuruecksetzen') },
});
