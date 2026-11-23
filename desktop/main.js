'use strict';
/**
 * Ayse CRM (vormals Schadensfälle) für Windows. Die Oberfläche ist die Web-App (app/index.html); dieser Hauptprozess
 * ergänzt, was ein Browser nicht kann: E-Mail direkt per IMAP/SMTP, Windows-Benachrichtigungen,
 * Öffnen von Anlagen mit den Programmen des Rechners und ein über Windows verschlüsseltes Passwort.
 */
const { app, BrowserWindow, dialog, ipcMain, Notification, protocol, net, safeStorage, session, shell, Menu, nativeImage, nativeTheme, systemPreferences, screen } = require('electron');
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
// Postfach wie Outlook: ein Einstieg für Ordner, Abgleich, Aktionen, Suche, Anhänge (siehe mail.js „ruf“)
behandle('mail:ruf', (befehl, daten, a) => { const k = ladeKonto(a); return mail.ruf(k, passwort(k), String(befehl), daten || {}); });

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
// Unter Windows sieht die App aus wie eine Windows-11-Anwendung: eigene Titelleiste mit den echten Fenster-Knöpfen
// (Andocken/Snap-Layouts bleiben erhalten), Mica-Hintergrund ab Windows 11 22H2, Akzentfarbe aus den Windows-Einstellungen.
const IST_WINDOWS = process.platform === 'win32';
const windowsBuild = () => (IST_WINDOWS ? Number(String(os.release()).split('.')[2]) || 0 : 0);
const MICA = IST_WINDOWS && windowsBuild() >= 22621;
const TITEL_HOEHE = 48;
const fensterDatei = () => path.join(app.getPath('userData'), 'fenster.json');
function fensterInfo() {
  let akzent = '';
  try { if (IST_WINDOWS) akzent = '#' + systemPreferences.getAccentColor().slice(0, 6); } catch {}
  return { windows: IST_WINDOWS, windows11: windowsBuild() >= 22000, mica: MICA, akzent, dunkel: nativeTheme.shouldUseDarkColors, titelHoehe: TITEL_HOEHE, version: app.getVersion() };
}
/** Größe und Lage vom letzten Mal – nur, wenn sie noch auf einen vorhandenen Bildschirm passt. */
function letzteLage() {
  try {
    const l = JSON.parse(fs.readFileSync(fensterDatei(), 'utf8'));
    const sichtbar = screen.getAllDisplays().some(d => l.x < d.workArea.x + d.workArea.width - 80 && l.x + l.width > d.workArea.x + 80 && l.y >= d.workArea.y - 10 && l.y < d.workArea.y + d.workArea.height - 60);
    return sichtbar ? l : { width: l.width, height: l.height, maximiert: l.maximiert };
  } catch { return {}; }
}
function lageMerken() {
  if (!fenster || fenster.isDestroyed()) return;
  try { fs.writeFileSync(fensterDatei(), JSON.stringify({ ...fenster.getNormalBounds(), maximiert: fenster.isMaximized() })); } catch {}
}
function erstelleFenster() {
  const lage = letzteLage();
  fenster = new BrowserWindow({
    width: lage.width || 1280, height: lage.height || 860, ...(lage.x != null ? { x: lage.x, y: lage.y } : {}), minWidth: 380, minHeight: 500,
    title: 'Ayse CRM', icon: path.join(ORDNER, 'icon-512.png'), show: false,
    backgroundColor: MICA ? '#00000000' : nativeTheme.shouldUseDarkColors ? '#202020' : '#F3F3F3',
    ...(IST_WINDOWS ? { titleBarStyle: 'hidden', titleBarOverlay: { color: '#00000000', symbolColor: nativeTheme.shouldUseDarkColors ? '#FFFFFF' : '#1A1A1A', height: TITEL_HOEHE } } : {}),
    ...(MICA ? { backgroundMaterial: 'mica' } : {}),
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: true, spellcheck: true },
  });
  if (lage.maximiert) fenster.maximize();
  for (const e of ['resize', 'move']) fenster.on(e, () => { clearTimeout(lageMerken.t); lageMerken.t = setTimeout(lageMerken, 600); });
  fenster.on('close', lageMerken);
  fenster.webContents.session.setSpellCheckerLanguages(['de-DE']);
  // Links nach außen (Mail, Telefon, Webseiten) öffnen die Programme des Rechners, nicht ein zweites Fenster.
  fenster.webContents.setWindowOpenHandler(({ url }) => {
    if (/^(https?:|mailto:|tel:)/i.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  fenster.webContents.on('will-navigate', (e, url) => {
    if (!url.startsWith('app://')) { e.preventDefault(); if (/^(https?:|mailto:|tel:)/i.test(url)) shell.openExternal(url); }
  });
  // Rechtsklick in Textfeldern und auf markiertem Text: das gewohnte Windows-Menü (mit Rechtschreibvorschlägen).
  fenster.webContents.on('context-menu', (_e, p) => {
    const eintraege = [];
    for (const v of (p.dictionarySuggestions || []).slice(0, 5)) eintraege.push({ label: v, click: () => fenster.webContents.replaceMisspelling(v) });
    if (p.misspelledWord) eintraege.push({ label: 'Zum Wörterbuch hinzufügen', click: () => fenster.webContents.session.addWordToSpellCheckerDictionary(p.misspelledWord) }, { type: 'separator' });
    if (p.isEditable) eintraege.push({ label: 'Rückgängig', role: 'undo', enabled: p.editFlags.canUndo }, { type: 'separator' }, { label: 'Ausschneiden', role: 'cut', enabled: p.editFlags.canCut });
    if (p.isEditable || p.selectionText) eintraege.push({ label: 'Kopieren', role: 'copy', enabled: p.editFlags.canCopy });
    if (p.isEditable) eintraege.push({ label: 'Einfügen', role: 'paste', enabled: p.editFlags.canPaste }, { type: 'separator' }, { label: 'Alles auswählen', role: 'selectAll' });
    if (p.linkURL && /^https?:/i.test(p.linkURL)) eintraege.push({ type: 'separator' }, { label: 'Link kopieren', click: () => require('electron').clipboard.writeText(p.linkURL) });
    if (eintraege.length) Menu.buildFromTemplate(eintraege).popup({ window: fenster });
  });
  // Windows-Tastenkürzel, die sonst das Anwendungsmenü liefern würde: Zoom (Strg +/−/0), F11 Vollbild, Strg+Umschalt+I nur in Tests nicht nötig.
  fenster.webContents.on('before-input-event', (e, i) => {
    if (i.type !== 'keyDown') return;
    const wc = fenster.webContents, z = wc.getZoomFactor();
    if (i.control && (i.key === '+' || i.key === '=' || i.code === 'NumpadAdd')) { wc.setZoomFactor(Math.min(2, Math.round((z + 0.1) * 10) / 10)); e.preventDefault(); }
    else if (i.control && (i.key === '-' || i.code === 'NumpadSubtract')) { wc.setZoomFactor(Math.max(0.6, Math.round((z - 0.1) * 10) / 10)); e.preventDefault(); }
    else if (i.control && (i.key === '0' || i.code === 'Numpad0')) { wc.setZoomFactor(1); e.preventDefault(); }
    else if (i.key === 'F11') { fenster.setFullScreen(!fenster.isFullScreen()); e.preventDefault(); }
    else return;
    wc.send('fenster:zoom', wc.getZoomFactor());
  });
  fenster.once('ready-to-show', () => fenster.show());
  fenster.loadURL('app://schaden/index.html');
}
if (IST_WINDOWS) {
  systemPreferences.on('accent-color-changed', () => { if (fenster) fenster.webContents.send('fenster:info', fensterInfo()); });
  nativeTheme.on('updated', () => { if (fenster) fenster.webContents.send('fenster:info', fensterInfo()); });
}
ipcMain.on('fenster:info', e => { e.returnValue = fensterInfo(); });
/** Farben der Titelleiste (Fenster-Knöpfe) passend zum hellen/dunklen Design der Oberfläche. */
behandle('fenster:titelleiste', (farbe, symbol, thema) => {
  if (['light', 'dark', 'system'].includes(thema)) nativeTheme.themeSource = thema; // Mica und Fenster-Knöpfe folgen dem Design der App
  if (!IST_WINDOWS || !fenster) return false;
  fenster.setTitleBarOverlay({ color: MICA ? '#00000000' : String(farbe || '#F3F3F3'), symbolColor: String(symbol || '#1A1A1A'), height: TITEL_HOEHE });
  return true;
});
/** Zähler auf dem Taskleistensymbol (z. B. ungelesene Mails); png = kleines Abzeichen als Data-URL, leer = entfernen. */
behandle('fenster:zaehler', (anzahl, png) => {
  if (!fenster) return false;
  if (IST_WINDOWS) fenster.setOverlayIcon(anzahl && png ? nativeImage.createFromDataURL(String(png)) : null, anzahl ? `${anzahl} ungelesen` : '');
  else if (app.setBadgeCount) app.setBadgeCount(Number(anzahl) || 0);
  return true;
});
behandle('fenster:blinken', () => { if (fenster && !fenster.isFocused()) fenster.flashFrame(true); return true; });
behandle('fenster:zoom', f => { if (fenster) fenster.webContents.setZoomFactor(Math.max(0.6, Math.min(2, Number(f) || 1))); return fenster ? fenster.webContents.getZoomFactor() : 1; });

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
