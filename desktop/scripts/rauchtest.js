'use strict';
// Startet das installierte Programm einmal und prüft, dass Fenster und Oberfläche laden.
// Aufruf: node scripts/rauchtest.js "C:\...\Schadensfälle.exe"
const os = require('node:os');
const fs = require('node:fs');
const path = require('node:path');
const { _electron: electron } = require('playwright');
(async () => {
  const exe = process.argv[2];
  if (!fs.existsSync(exe)) throw new Error('Programm nicht gefunden: ' + exe);
  const daten = fs.mkdtempSync(path.join(os.tmpdir(), 'schaden-rauch-'));
  const app = await electron.launch({ executablePath: exe, args: process.platform === 'linux' ? ['--no-sandbox'] : [], env: { ...process.env, SCHADEN_DATEN: daten, SCHADEN_TEST: '1' } });
  try {
    const s = await app.firstWindow();
    await s.waitForFunction(() => window.__appBereit, null, { timeout: 60000 });
    const titel = await s.title();
    const plattform = await s.evaluate(() => window.desktop && window.desktop.plattform);
    if (titel !== 'Ayse CRM' || !plattform) throw new Error(`Unerwartet: Titel „${titel}“, Brücke ${plattform}`);
    // Texterkennung im installierten Programm (Sprachdaten und Kern aus dem entpackten Teil) und Scanner-Suche über PowerShell/WIA
    const ocr = await s.evaluate(async () => { const c = document.createElement('canvas'); c.width = 700; c.height = 160; const x = c.getContext('2d');
      x.fillStyle = '#fff'; x.fillRect(0, 0, 700, 160); x.fillStyle = '#000'; x.font = '40px Arial'; x.fillText('Summe EUR 109,90', 30, 95);
      return (await window.desktop.scan.lesen(c.toDataURL('image/png').split(',')[1])).text; });
    if (!/109,90/.test(ocr)) throw new Error('Texterkennung im installierten Programm liefert: ' + JSON.stringify(ocr));
    console.log('Texterkennung ok, Scanner-Suche:', await s.evaluate(() => window.desktop.scan.geraete().then(l => `${l.length} gefunden`, e => 'Fehler: ' + e.message)));
    await s.screenshot({ path: path.join(__dirname, '..', 'dist', 'rauchtest.png') });
    console.log(`Installiertes Programm läuft (${plattform}): Fenster „${titel}“`);
  } finally {
    await app.close();
  }
})().catch(e => { console.error(e); process.exit(1); });
