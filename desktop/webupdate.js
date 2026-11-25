'use strict';
/**
 * In-App-Update der Oberfläche (Web-App) ohne neues Setup – Gegenstück zu WebUpdate.kt in der Android-App.
 * Die neue Fassung kommt nur von der festen Adresse der Web-App, jede Datei wird per SHA-256 gegen app-update.json
 * geprüft. Startet sie zweimal nicht sauber (kein „web:ok“), gilt wieder die mitgelieferte Fassung.
 */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const NATIV_API = 2;
const BASIS = process.env.SCHADEN_UPDATE_BASIS || 'https://aysetransport.github.io/schaden/';
const MANIFEST = 'app-update.json';

/** Baut das Web-Update für den Hauptprozess: prüft app-update.json, lädt neue Dateien mit Prüfsumme, liefert beim Laden die geprüfte Fassung und fällt bei Startproblemen auf die mitgelieferte zurück. */
function webUpdate({ datenOrdner, appOrdner, appVersion, holen }) {
  const ordner = path.join(datenOrdner, 'webupdate'), zustandDatei = path.join(ordner, 'zustand.json');
  const lies = d => { try { return JSON.parse(fs.readFileSync(d, 'utf8')); } catch { return null; } };
  const gebuendelt = () => lies(path.join(appOrdner, MANIFEST));
  const stand = o => Number((o && o.stand) || 0);
  const sha = b => crypto.createHash('sha256').update(b).digest('hex');
  const hole = async pfad => {
    const r = await holen(BASIS + pfad + '?t=' + Date.now());
    if (!r.ok) throw new Error(`Update nicht erreichbar (${pfad}: ${r.status})`);
    return Buffer.from(await r.arrayBuffer());
  };
  const datei = pfad => {
    if (!fs.existsSync(zustandDatei)) return null;
    const basis = path.join(ordner, 'dateien'), f = path.normalize(path.join(basis, pfad));
    return f.startsWith(basis + path.sep) && fs.existsSync(f) && fs.statSync(f).isFile() ? f : null;
  };
  return {
    NATIV_API,
    datei,
    beimStart() {
      const z = lies(zustandDatei); if (!z) return;
      if (stand(z) <= stand(gebuendelt()) || (!z.ok && (z.versuche || 0) >= 2)) { fs.rmSync(ordner, { recursive: true, force: true }); return; }
      fs.writeFileSync(zustandDatei, JSON.stringify({ ...z, versuche: (z.versuche || 0) + 1 }));
    },
    info() { const z = lies(zustandDatei); return { nativ: NATIV_API, app: appVersion, gebuendelt: stand(gebuendelt()), aktiv: z ? stand(z) : 0, version: (z && z.version) || '' }; },
    ok() { const z = lies(zustandDatei); if (z) fs.writeFileSync(zustandDatei, JSON.stringify({ ...z, ok: true, versuche: 0 })); return true; },
    zuruecksetzen() { fs.rmSync(ordner, { recursive: true, force: true }); return true; },
    async installieren() {
      const m = JSON.parse((await hole(MANIFEST)).toString('utf8'));
      if ((m.minNativ || 1) > NATIV_API) throw new Error('Für diese Version ist ein App-Update nötig');
      if (stand(m) <= Math.max(stand(gebuendelt()), stand(lies(zustandDatei)))) return { stand: stand(lies(zustandDatei)), neu: false };
      const alt = (gebuendelt() || {}).dateien || {}, staging = ordner + '-neu';
      fs.rmSync(staging, { recursive: true, force: true });
      for (const [pfad, w] of Object.entries(m.dateien || {})) {
        if (pfad.includes('..') || path.isAbsolute(pfad)) throw new Error('Ungültiger Pfad im Update');
        if (alt[pfad] && alt[pfad].sha256 === w.sha256) continue; // unverändert: aus der App
        const vorhanden = datei(pfad) && fs.readFileSync(datei(pfad));
        const bytes = vorhanden && sha(vorhanden) === w.sha256 ? vorhanden : await hole(pfad);
        if (sha(bytes) !== w.sha256) throw new Error('Prüfsumme stimmt nicht: ' + pfad);
        const ziel = path.join(staging, 'dateien', pfad);
        fs.mkdirSync(path.dirname(ziel), { recursive: true }); fs.writeFileSync(ziel, bytes);
      }
      fs.mkdirSync(staging, { recursive: true });
      fs.writeFileSync(path.join(staging, 'zustand.json'), JSON.stringify({ stand: stand(m), ok: false, versuche: 0, version: m.version || '' }));
      fs.rmSync(ordner, { recursive: true, force: true });
      fs.renameSync(staging, ordner);
      return { stand: stand(m), neu: true };
    },
  };
}

module.exports = { webUpdate, NATIV_API };
