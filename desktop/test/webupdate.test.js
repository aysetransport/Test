'use strict';
// In-App-Update der Oberfläche: lädt nur Geändertes, prüft Prüfsummen, verweigert zu neue Fassungen, fällt nach Fehlstarts zurück.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

const sha = b => crypto.createHash('sha256').update(b).digest('hex');
const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'webupdate-'));

function aufbau({ serverDateien, manifest }) {
  process.env.SCHADEN_UPDATE_BASIS = 'https://update.test/';
  delete require.cache[require.resolve('../webupdate')];
  const { webUpdate } = require('../webupdate');
  const appOrdner = tmp(), datenOrdner = tmp();
  fs.writeFileSync(path.join(appOrdner, 'index.html'), 'ALT');
  fs.writeFileSync(path.join(appOrdner, 'sprachen.js'), 'SPRACHEN');
  fs.writeFileSync(path.join(appOrdner, 'app-update.json'), JSON.stringify({ stand: 100, minNativ: 1, dateien: { 'index.html': { sha256: sha('ALT') }, 'sprachen.js': { sha256: sha('SPRACHEN') } } }));
  const geholt = [];
  const holen = async url => {
    const pfad = url.replace('https://update.test/', '').replace(/\?.*$/, ''); geholt.push(pfad);
    const inhalt = pfad === 'app-update.json' ? JSON.stringify(manifest) : serverDateien[pfad];
    return inhalt == null ? { ok: false, status: 404 } : { ok: true, status: 200, arrayBuffer: async () => Buffer.from(inhalt) };
  };
  return { u: webUpdate({ datenOrdner, appOrdner, appVersion: '4.13.0', holen }), geholt, datenOrdner };
}

test('lädt nur geänderte Dateien, prüft Prüfsumme und liefert sie aus', async () => {
  const { u, geholt } = aufbau({ serverDateien: { 'index.html': 'NEU' },
    manifest: { stand: 200, version: '4.13.1', minNativ: 1, dateien: { 'index.html': { sha256: sha('NEU') }, 'sprachen.js': { sha256: sha('SPRACHEN') } } } });
  const r = await u.installieren();
  assert.deepEqual(r, { stand: 200, neu: true });
  assert.deepEqual(geholt, ['app-update.json', 'index.html'], 'sprachen.js unverändert – nicht geladen');
  assert.equal(fs.readFileSync(u.datei('index.html'), 'utf8'), 'NEU');
  assert.equal(u.datei('sprachen.js'), null, 'unveränderte Datei kommt aus der App');
  assert.equal(u.info().aktiv, 200);
  assert.deepEqual(await u.installieren(), { stand: 200, neu: false }, 'kein zweites Mal');
});

test('falsche Prüfsumme oder zu neue Fassung: nichts wird aktiviert', async () => {
  const a = aufbau({ serverDateien: { 'index.html': 'MANIPULIERT' }, manifest: { stand: 200, minNativ: 1, dateien: { 'index.html': { sha256: sha('NEU') } } } });
  await assert.rejects(a.u.installieren(), /Prüfsumme/);
  assert.equal(a.u.datei('index.html'), null);
  const b = aufbau({ serverDateien: { 'index.html': 'NEU' }, manifest: { stand: 200, minNativ: 99, dateien: { 'index.html': { sha256: sha('NEU') } } } });
  await assert.rejects(b.u.installieren(), /App-Update nötig/);
  assert.equal(b.u.datei('index.html'), null);
});

test('zwei Fehlstarts ohne „ok“ → zurück zur mitgelieferten Fassung; „ok“ schützt', async () => {
  const { u } = aufbau({ serverDateien: { 'index.html': 'NEU' }, manifest: { stand: 200, minNativ: 1, dateien: { 'index.html': { sha256: sha('NEU') } } } });
  await u.installieren();
  u.beimStart(); u.beimStart();
  assert.ok(u.datei('index.html'), 'nach zwei Starts noch da');
  u.beimStart();
  assert.equal(u.datei('index.html'), null, 'dritter Start ohne ok: verworfen');
  await u.installieren(); u.beimStart(); u.ok(); u.beimStart(); u.beimStart(); u.beimStart();
  assert.ok(u.datei('index.html'), 'mit ok bleibt die Fassung');
  u.zuruecksetzen();
  assert.equal(u.datei('index.html'), null);
});
