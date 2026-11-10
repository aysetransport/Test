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
    konto: () => aufruf('mail:konto'),
    einrichten: (konfig, passwort) => aufruf('mail:einrichten', konfig, passwort),
    trennen: () => aufruf('mail:trennen'),
    sende: entwurf => aufruf('mail:sende', entwurf),
    hole: stand => aufruf('mail:hole', stand),
  },
  benachrichtige: (titel, text, ziel) => aufruf('benachrichtige', titel, text, ziel),
  oeffneDatei: (name, bytes) => aufruf('datei:oeffnen', name, bytes),
  oeffneExtern: adresse => aufruf('extern:oeffnen', adresse),
  beiZiel: rueckruf => ipcRenderer.on('ziel', (_e, ziel) => rueckruf(ziel)),
});
