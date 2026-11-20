'use strict';
/**
 * Vor jeder Veröffentlichung ausführen: setzt Stand und Version der Oberfläche in web/index.html und schreibt
 * web/app-update.json (Dateien mit SHA-256). Die Windows- und Android-App lesen diese Datei für das In-App-Update.
 *   node tools/app-update.js [minNativ]
 * minNativ nur erhöhen, wenn die neue Oberfläche Gerätefunktionen braucht, die erst eine neue App mitbringt.
 */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const WEB = path.join(__dirname, '..', 'web');
const DATEIEN = ['index.html', 'sprachen.js', 'manifest.webmanifest', 'icon-192.png', 'icon-512.png', 'pdfjs/pdf.min.js', 'pdfjs/pdf.worker.min.js'];
const version = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'desktop', 'package.json'), 'utf8')).version;
const alt = (() => { try { return JSON.parse(fs.readFileSync(path.join(WEB, 'app-update.json'), 'utf8')); } catch { return {}; } })();
const minNativ = Number(process.argv[2] || alt.minNativ || 1);
const stand = Math.max(Math.floor(Date.now() / 1000), Number(alt.stand || 0) + 1);

const index = path.join(WEB, 'index.html');
const html = fs.readFileSync(index, 'utf8');
const neu = html.replace(/window\.WEB_STAND = \d+; window\.WEB_VERSION = '[^']*';/, `window.WEB_STAND = ${stand}; window.WEB_VERSION = '${version}';`);
if (neu === html && !html.includes(`window.WEB_STAND = ${stand};`)) throw new Error('Markierung WEB_STAND in index.html fehlt');
fs.writeFileSync(index, neu);

const dateien = {};
for (const d of DATEIEN) {
  const b = fs.readFileSync(path.join(WEB, d));
  dateien[d] = { sha256: crypto.createHash('sha256').update(b).digest('hex'), groesse: b.length };
}
fs.writeFileSync(path.join(WEB, 'app-update.json'), JSON.stringify({ stand, version, minNativ, dateien }, null, 1) + '\n');
console.log(`app-update.json: Version ${version}, Stand ${stand}, minNativ ${minNativ}`);
