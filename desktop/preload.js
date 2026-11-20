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
    hole: (stand, art) => aufruf('mail:hole', stand, art),
  },
  benachrichtige: (titel, text, ziel) => aufruf('benachrichtige', titel, text, ziel),
  oeffneDatei: (name, bytes) => aufruf('datei:oeffnen', name, bytes),
  oeffneExtern: adresse => aufruf('extern:oeffnen', adresse),
  pdf: html => aufruf('pdf:erzeugen', html),
  speichere: (name, base64) => aufruf('datei:speichern', name, base64),
  beiZiel: rueckruf => ipcRenderer.on('ziel', (_e, ziel) => rueckruf(ziel)),
  // In-App-Update der Oberfläche
  web: { info: () => aufruf('web:info'), installieren: () => aufruf('web:installieren'), ok: () => aufruf('web:ok'), zuruecksetzen: () => aufruf('web:zuruecksetzen') },
});
