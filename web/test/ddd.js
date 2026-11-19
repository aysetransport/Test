'use strict';
/** Baut eine Fahrerkarten-Datei (Generation 1) für Tests: Identifikation, Tätigkeiten (Ringpuffer) und benutzte Fahrzeuge. */
function block(fid, typ, daten) { const b = Buffer.alloc(5 + daten.length); b.writeUInt16BE(fid, 0); b[2] = typ; b.writeUInt16BE(daten.length, 3); daten.copy(b, 5); return b; }
const text = (s, len) => { const b = Buffer.alloc(len, 0x20); b[0] = 1; Buffer.from(s, 'latin1').copy(b, 1); return b; };
const zeit = iso => { const b = Buffer.alloc(4); b.writeUInt32BE(Math.floor(Date.parse(iso) / 1000)); return b; };
/** tage: [{ datum: 'JJJJ-MM-TT', km, wechsel: [[min, 'RUHE'|'BEREIT'|'ARBEIT'|'LENKEN'], …] }], fahrzeuge: [{ kz, kmVon, kmBis, ab, bis }] */
function fahrerkarte({ kartenNr = 'DF000012345670', nachname = 'Demir', vorname = 'Ali', gueltigBis = '2030-05-31', tage = [], fahrzeuge = [] }) {
  const id = Buffer.concat([Buffer.from([0x0d]), Buffer.from(kartenNr.padEnd(16, ' '), 'latin1'), text('Kraftfahrt-Bundesamt', 36), zeit('2025-05-01T00:00:00Z'), zeit('2025-05-01T00:00:00Z'), zeit(gueltigBis + 'T00:00:00Z'),
    text(nachname, 36), text(vorname, 36), Buffer.from([0x19, 0x85, 0x03, 0x14]), Buffer.from('de')]);
  const ARTEN = { RUHE: 0, BEREIT: 1, ARBEIT: 2, LENKEN: 3 };
  let vorher = 0; const recs = tage.map(t => {
    const w = Buffer.alloc(t.wechsel.length * 2); t.wechsel.forEach(([min, art], i) => w.writeUInt16BE((ARTEN[art] << 11) | min, i * 2));
    const kopf = Buffer.alloc(12); const laenge = 12 + w.length; kopf.writeUInt16BE(vorher, 0); kopf.writeUInt16BE(laenge, 2); zeit(t.datum + 'T00:00:00Z').copy(kopf, 4); kopf.writeUInt16BE(0x0001, 8); kopf.writeUInt16BE(t.km || 0, 10);
    vorher = laenge; return Buffer.concat([kopf, w]);
  });
  const ring = Buffer.concat(recs), aelteste = 0, neueste = ring.length - recs[recs.length - 1].length;
  const akt = Buffer.concat([Buffer.from([aelteste >> 8, aelteste & 255, neueste >> 8, neueste & 255]), ring, Buffer.alloc(64)]);
  const fz = Buffer.concat([Buffer.from([0, 0]), ...fahrzeuge.map(f => { const b = Buffer.alloc(31); b.writeUIntBE(f.kmVon, 0, 3); b.writeUIntBE(f.kmBis, 3, 3); zeit(f.ab).copy(b, 6); zeit(f.bis).copy(b, 10); b[14] = 0x0d; text(f.kz, 14).copy(b, 15); return b; })]);
  return Buffer.concat([block(0x0002, 0, Buffer.alloc(25)), block(0x0501, 0, Buffer.alloc(10)), block(0x0520, 0, id), block(0x0504, 0, akt), block(0x0505, 0, fz), block(0x0520, 1, Buffer.alloc(128))]);
}
module.exports = { fahrerkarte };
