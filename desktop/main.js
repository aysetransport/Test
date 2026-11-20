'use strict';
/**
 * Ayse CRM (vormals Schadensfälle) für Windows. Die Oberfläche ist die Web-App (app/index.html); dieser Hauptprozess
 * ergänzt, was ein Browser nicht kann: E-Mail direkt per IMAP/SMTP, Windows-Benachrichtigungen,
 * Öffnen von Anlagen mit den Programmen des Rechners und ein über Windows verschlüsseltes Passwort.
 */
const { app, BrowserWindow, dialog, ipcMain, Notification, protocol, net, safeStorage, session, shell, Menu } = require('electron');
const path = require('node:path');
const fs = require('node:fs');
const os = require('node:os');
const { pathToFileURL } = require('node:url');
const mail = require('./mail');
const { webUpdate } = require('./webupdate');

const ORDNER = path.join(__dirname, 'app');
protocol.registerSchemesAsPrivileged([{ scheme: 'app', privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true } }]);

if (process.env.SCHADEN_DATEN) app.setPath('userData', process.env.SCHADEN_DATEN); // Tests: eigener Datenordner
if (!app.requestSingleInstanceLock()) app.quit();

let fenster = null;
let update = null; // In-App-Update der Oberfläche (webupdate.js), nach app.whenReady angelegt

// ------------------------------ E-Mail-Konten ------------------------------
// Zwei Postfächer wie in der Android-App: SCHADEN (Schadensfälle, bisherige Datei mailkonto.json)
// und ALLGEMEIN (alles andere, mailkonto-allgemein.json).
const ARTEN = ['SCHADEN', 'ALLGEMEIN'];
const art = a => (ARTEN.includes(a) ? a : 'SCHADEN');
const kontoDatei = a => path.join(app.getPath('userData'), art(a) === 'SCHADEN' ? 'mailkonto.json' : 'mailkonto-allgemein.json');
function ladeKonto(a) {
  try { return JSON.parse(fs.readFileSync(kontoDatei(a), 'utf8')); } catch { return null; }
}
function passwort(konto) {
  if (!konto || !konto.passwort) throw new mail.MailFehler('Kein E-Mail-Konto eingerichtet');
  const roh = Buffer.from(konto.passwort, 'base64');
  return konto.verschluesselt ? safeStorage.decryptString(roh) : roh.toString('utf8');
}
const ohnePasswort = k => (k ? Object.fromEntries(Object.entries(k).filter(([n]) => n !== 'passwort' && n !== 'verschluesselt')) : null);

function behandle(kanal, arbeit) {
  ipcMain.handle(kanal, async (_e, ...args) => {
    try { return { ok: await arbeit(...args) }; } catch (e) { return { fehler: e.message || String(e), anmeldung: !!e.anmeldung }; }
  });
}

behandle('mail:anbieter', () => mail.ANBIETER);
behandle('web:info', () => update.info());
behandle('web:installieren', () => update.installieren());
behandle('web:ok', () => update.ok());
behandle('web:zuruecksetzen', () => update.zuruecksetzen());
behandle('mail:konto', a => ohnePasswort(ladeKonto(a)));
behandle('mail:einrichten', async (konfig, pw, a) => {
  await mail.pruefe(konfig, pw);
  // Unter Windows verschlüsselt DPAPI das Passwort mit dem Windows-Konto des Benutzers.
  const verschluesselt = safeStorage.isEncryptionAvailable();
  const gespeichert = verschluesselt ? safeStorage.encryptString(pw) : Buffer.from(pw, 'utf8');
  fs.mkdirSync(app.getPath('userData'), { recursive: true });
  fs.writeFileSync(kontoDatei(a), JSON.stringify({ ...konfig, passwort: gespeichert.toString('base64'), verschluesselt }), { mode: 0o600 });
  return ohnePasswort(konfig);
});
behandle('mail:trennen', a => { fs.rmSync(kontoDatei(a), { force: true }); return true; });
behandle('mail:sende', (entwurf, a) => { const k = ladeKonto(a); return mail.sende(k, passwort(k), entwurf); });
behandle('mail:hole', (stand, a) => { const k = ladeKonto(a); return mail.hole(k, passwort(k), stand || {}); });

// ------------------------------ Rechner-Funktionen ------------------------------
behandle('benachrichtige', (titel, text, ziel) => {
  if (!Notification.isSupported()) return false;
  const n = new Notification({ title: titel, body: text, icon: path.join(ORDNER, 'icon-192.png') });
  n.on('click', () => { if (fenster) { if (fenster.isMinimized()) fenster.restore(); fenster.focus(); if (ziel) fenster.webContents.send('ziel', ziel); } });
  n.show();
  return true;
});
behandle('datei:oeffnen', async (name, bytes) => {
  const sicher = String(name || 'Anlage').replace(/[\\/:*?"<>|]/g, '_').slice(0, 120);
  const ordner = fs.mkdtempSync(path.join(os.tmpdir(), 'schadensfaelle-'));
  const datei = path.join(ordner, sicher);
  fs.writeFileSync(datei, Buffer.from(bytes));
  const fehler = await shell.openPath(datei);
  if (fehler) throw new Error(fehler);
  return true;
});
behandle('extern:oeffnen', adresse => {
  if (!/^(mailto:|tel:|sms:|https:)/i.test(String(adresse))) throw new Error('Adresse nicht erlaubt');
  return shell.openExternal(adresse);
});

// ------------------------------ PDF (Angebote, Rechnungen) ------------------------------
/** Erzeugt aus einer fertigen HTML-Seite ein PDF (A4) und liefert es als Base64. */
behandle('pdf:erzeugen', async html => {
  const unsichtbar = new BrowserWindow({ show: false, webPreferences: { javascript: false, sandbox: true } });
  try {
    await unsichtbar.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(String(html)));
    const pdf = await unsichtbar.webContents.printToPDF({ pageSize: 'A4', printBackground: true, margins: { marginType: 'none' } });
    return Buffer.from(pdf).toString('base64');
  } finally {
    unsichtbar.destroy();
  }
});
/** Fragt nach dem Speicherort und legt die Datei dort ab (z. B. ein PDF oder eine Kalenderdatei). */
behandle('datei:speichern', async (vorschlag, base64) => {
  const name = String(vorschlag || 'Datei').replace(/[\\/:*?"<>|]/g, '_').slice(0, 120);
  if (process.env.SCHADEN_TEST) {
    // Tests: ohne Dialog in den Datenordner
    const ziel = path.join(app.getPath('userData'), name);
    fs.writeFileSync(ziel, Buffer.from(String(base64), 'base64'));
    return ziel;
  }
  const { canceled, filePath } = await dialog.showSaveDialog(fenster, { defaultPath: path.join(app.getPath('documents'), name) });
  if (canceled || !filePath) return '';
  fs.writeFileSync(filePath, Buffer.from(String(base64), 'base64'));
  return filePath;
});

// ------------------------------ Fenster ------------------------------
function erstelleFenster() {
  fenster = new BrowserWindow({
    width: 1280, height: 860, minWidth: 380, minHeight: 500,
    title: 'Ayse CRM', icon: path.join(ORDNER, 'icon-512.png'), backgroundColor: '#F8F9FF', show: false,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: true },
  });
  // Links nach außen (Mail, Telefon, Webseiten) öffnen die Programme des Rechners, nicht ein zweites Fenster.
  fenster.webContents.setWindowOpenHandler(({ url }) => {
    if (/^(https?:|mailto:|tel:)/i.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  fenster.webContents.on('will-navigate', (e, url) => {
    if (!url.startsWith('app://')) { e.preventDefault(); if (/^(https?:|mailto:|tel:)/i.test(url)) shell.openExternal(url); }
  });
  fenster.once('ready-to-show', () => fenster.show());
  fenster.loadURL('app://schaden/index.html');
}

app.on('second-instance', () => { if (fenster) { if (fenster.isMinimized()) fenster.restore(); fenster.focus(); } });
app.on('window-all-closed', () => app.quit());
app.whenReady().then(() => {
  update = webUpdate({ datenOrdner: app.getPath('userData'), appOrdner: ORDNER, appVersion: app.getVersion(), holen: u => net.fetch(u, { cache: 'no-store' }) });
  update.beimStart();
  protocol.handle('app', anfrage => {
    const pfad = decodeURIComponent(new URL(anfrage.url).pathname);
    const neu = update.datei(pfad.replace(/^\/+/, '') || 'index.html');
    if (neu) return net.fetch(pathToFileURL(neu).toString());
    const datei = path.normalize(path.join(ORDNER, pfad));
    if (!datei.startsWith(ORDNER + path.sep)) return new Response('Nicht gefunden', { status: 404 });
    return net.fetch(pathToFileURL(datei).toString());
  });
  if (process.env.SCHADEN_TEST) {
    // Tests sprechen nie die echte Cloud an.
    session.defaultSession.webRequest.onBeforeRequest({ urls: ['https://script.google.com/*', 'https://*.googleusercontent.com/*'] }, (_d, cb) => cb({ cancel: true }));
  }
  Menu.setApplicationMenu(null);
  erstelleFenster();
});
