'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { erstelleUmgebung } = require('./gas-umgebung');

const PIN = 'geheim-2026';
const A = '11111111-1111-4111-8111-111111111111';
const B = '22222222-2222-4222-8222-222222222222';
const V1 = 'aaaaaaaa-0000-4000-8000-000000000001';
const N1 = 'bbbbbbbb-0000-4000-8000-000000000001';

function eingerichtet() {
  const u = erstelleUmgebung();
  u.aufruf('pinFestlegen', PIN);
  u.sync = anfrage => u.aufruf('sync', PIN, anfrage);
  return u;
}
const fall = (id, extra = {}) => ({
  id, fallnummer: '2026-001', kundeName: 'Anna Becker', status: 'NEU', schadensart: 'WARE',
  erstelltAm: '2026-11-10T08:00:00Z', geaendertAm: '2026-11-10T08:00:00Z', ...extra,
});

test('PIN wird beim ersten Verbinden festgelegt und danach verlangt', () => {
  const u = erstelleUmgebung();
  assert.equal(u.aufruf('status').eingerichtet, false);
  assert.throws(() => u.aufruf('anmelden', PIN), /PIN noch nicht festgelegt/);
  assert.throws(() => u.aufruf('pinFestlegen', '123'), /mindestens 4 Zeichen/);
  assert.equal(erstelleUmgebung().aufruf('pinFestlegen', '1978').eingerichtet, true);

  u.aufruf('pinFestlegen', PIN);
  assert.equal(u.aufruf('status').eingerichtet, true);
  assert.throws(() => u.aufruf('pinFestlegen', 'andere-pin'), /bereits eine PIN/);
  assert.throws(() => u.aufruf('anmelden', 'falsch'), /PIN falsch/);
  assert.equal(u.aufruf('anmelden', PIN).rev, 0);
  assert.ok(!JSON.stringify([...u.eigenschaften]).includes(PIN), 'PIN darf nicht im Klartext gespeichert sein');
});

test('nach acht falschen Eingaben ist eine Stunde gesperrt – auch für die richtige PIN', () => {
  const u = eingerichtet();
  for (let i = 0; i < 7; i++) assert.throws(() => u.aufruf('anmelden', 'falsch'), /PIN falsch/);
  u.aufruf('anmelden', PIN); // richtige Eingabe setzt den Zähler zurück
  for (let i = 0; i < 8; i++) assert.throws(() => u.aufruf('anmelden', 'falsch'), /PIN falsch/);
  assert.throws(() => u.aufruf('anmelden', PIN), /GESPERRT/);
  u.zeitVor(59 * 60000);
  assert.throws(() => u.aufruf('anmelden', PIN), /GESPERRT/);
  u.zeitVor(2 * 60000);
  assert.equal(u.aufruf('anmelden', PIN).rev, 0);
});

test('unbekannte Funktionen und interne Helfer sind nicht aufrufbar', () => {
  const u = eingerichtet();
  assert.throws(() => u.aufruf('ladeDaten_', PIN), /Unbekannte Funktion/);
  assert.throws(() => u.aufruf('pinZuruecksetzen', PIN), /Unbekannte Funktion/);
  assert.throws(() => u.aufruf('constructor', PIN), /Unbekannte Funktion/);
  assert.equal(u.post('kein json').error !== undefined, true);
});

test('pinZuruecksetzen erlaubt eine neue PIN und behält die Daten', () => {
  const u = eingerichtet();
  u.sync({ seit: 0, faelle: [fall(A)] });
  u.kontext.pinZuruecksetzen();
  assert.equal(u.aufruf('status').eingerichtet, false);
  u.aufruf('pinFestlegen', 'neue-pin-9');
  assert.equal(u.aufruf('sync', 'neue-pin-9', { seit: 0 }).faelle.length, 1);
});

test('ein Gerät lädt hoch, das andere bekommt nur Neues', () => {
  const u = eingerichtet();
  const erste = u.sync({ seit: 0, faelle: [fall(A)] });
  assert.equal(erste.rev, 1);
  assert.equal(erste.faelle[0].kundeName, 'Anna Becker');

  const geraetB = u.sync({ seit: 0, kennung: erste.kennung });
  assert.deepEqual(geraetB.faelle.map(f => f.id), [A]);
  const nichtsNeues = u.sync({ seit: geraetB.rev, kennung: erste.kennung });
  assert.deepEqual([nichtsNeues.faelle, nichtsNeues.verlauf, nichtsNeues.anlagen], [[], [], []]);
  assert.equal(u.aufruf('stand', PIN).rev, 1);
});

test('bei gleichzeitigen Änderungen gewinnt der spätere Stand, der Verlierer bekommt ihn zurück', () => {
  const u = eingerichtet();
  const k = u.sync({ seit: 0, faelle: [fall(A)] }).kennung;
  u.sync({ seit: 1, kennung: k, faelle: [fall(A, { status: 'IN_PRUEFUNG', geaendertAm: '2026-11-10T10:00:00Z' })] });

  const veraltet = u.sync({ seit: 1, kennung: k, faelle: [fall(A, { kundeName: 'Alt', geaendertAm: '2026-11-10T09:00:00Z' })] });
  assert.equal(veraltet.faelle[0].status, 'IN_PRUEFUNG');
  assert.equal(veraltet.faelle[0].kundeName, 'Anna Becker');

  // Auch wenn das Gerät den neueren Stand laut „seit“ schon kennen müsste, bekommt es ihn erneut.
  const nochmal = u.sync({ seit: veraltet.rev, kennung: k, faelle: [fall(A, { kundeName: 'Alt', geaendertAm: '2026-11-10T09:00:00Z' })] });
  assert.equal(nochmal.faelle.length, 1);
  // Unverändert erneut gesendet: kein neuer Stand, nichts zurück.
  const gleich = u.sync({ seit: nochmal.rev, kennung: k, faelle: [nochmal.faelle[0]] });
  assert.equal(gleich.rev, nochmal.rev);
  assert.equal(gleich.faelle.length, 0);
});

test('Zeitpunkte werden unabhängig von der Schreibweise verglichen', () => {
  const u = eingerichtet();
  u.sync({ seit: 0, faelle: [fall(A, { geaendertAm: '2026-11-10T09:00:00.500Z' })] });
  const frueher = u.sync({ seit: 0, faelle: [fall(A, { kundeName: 'Früher', geaendertAm: '2026-11-10T09:00:00Z' })] });
  assert.equal(frueher.faelle[0].kundeName, 'Anna Becker');
  const spaeter = u.sync({ seit: 0, faelle: [fall(A, { kundeName: 'Später', geaendertAm: '2026-11-10T10:00:00+01:00' })] });
  assert.equal(spaeter.faelle[0].kundeName, 'Anna Becker', '10:00 +01:00 ist 09:00 UTC und damit nicht später');
});

test('zwei Geräte vergeben dieselbe Fallnummer: der zweite Fall wird umnummeriert', () => {
  const u = eingerichtet();
  u.sync({ seit: 0, faelle: [fall(A)] });
  const zweiter = u.sync({ seit: 0, faelle: [fall(B, { kundeName: 'Carl Demir' })] });
  const nummern = Object.fromEntries(zweiter.faelle.map(f => [f.id, f.fallnummer]));
  assert.deepEqual(nummern, { [A]: '2026-001', [B]: '2026-002' });
  // Eine spätere Bearbeitung mit der alten Nummer bringt die Dublette nicht zurück.
  const bearbeitet = u.sync({ seit: zweiter.rev, faelle: [fall(B, { fallnummer: '2026-001', geaendertAm: '2026-11-11T08:00:00Z' })] });
  assert.equal(bearbeitet.faelle.find(f => f.id === B).fallnummer, '2026-002');
});

test('Löschen entfernt Inhalt, Verlauf, Anlagen und Dateien und lässt sich nicht rückgängig bearbeiten', () => {
  const u = eingerichtet();
  u.sync({
    seit: 0, faelle: [fall(A)],
    verlauf: [{ id: V1, fallId: A, zeitpunkt: '2026-11-10T08:05:00Z', typ: 'NOTIZ', text: 'Kundin angerufen' }],
    anlagen: [{ id: N1, fallId: A, anzeigename: 'foto.jpg', mimeType: 'image/jpeg', groesseBytes: 3, hinzugefuegtAm: '2026-11-10T08:06:00Z' }],
  });
  u.aufruf('anlageHochladen', PIN, N1, Buffer.from([1, 2, 3]).toString('base64'));
  assert.equal(u.dateien('Schadensfaelle/anlagen').length, 1);

  const geloescht = u.sync({ seit: 0, faelle: [{ id: A, geloescht: true, geaendertAm: '2026-11-10T09:00:00Z' }] });
  assert.deepEqual(geloescht.faelle.map(f => [f.id, f.geloescht, f.kundeName]), [[A, true, undefined]]);
  assert.deepEqual(geloescht.verlauf.map(v => [v.geloescht, v.text]), [[true, undefined]]);
  assert.deepEqual(geloescht.anlagen.map(a => [a.geloescht, a.anzeigename]), [[true, undefined]]);
  assert.equal(u.dateien('Schadensfaelle/anlagen').length, 0);
  assert.ok(!u.datei('Schadensfaelle/daten.json').bytes.toString().includes('Anna Becker'), 'personenbezogene Daten sind entfernt');

  // Ein anderes Gerät bearbeitet den Fall noch und hängt etwas an: Es erfährt von der Löschung.
  const spaet = u.sync({
    seit: 3, faelle: [fall(A, { kundeName: 'Bearbeitet', geaendertAm: '2026-11-12T09:00:00Z' })],
    verlauf: [{ id: 'aaaaaaaa-0000-4000-8000-000000000002', fallId: A, zeitpunkt: '2026-11-12T09:00:00Z', typ: 'NOTIZ', text: 'zu spät' }],
  });
  assert.equal(spaet.faelle.find(f => f.id === A).geloescht, true);
  assert.equal(spaet.verlauf.find(v => v.id.endsWith('2')).geloescht, true);
  assert.throws(() => u.aufruf('anlageLaden', PIN, N1), /Anlage unbekannt/);
});

test('Verlaufseinträge werden hinzugefügt und einzeln gelöscht, aber nicht verändert', () => {
  const u = eingerichtet();
  u.sync({ seit: 0, faelle: [fall(A)], verlauf: [{ id: V1, fallId: A, zeitpunkt: '2026-11-10T08:05:00Z', typ: 'ANRUF', text: 'Original' }] });
  const geaendert = u.sync({ seit: 0, verlauf: [{ id: V1, fallId: A, zeitpunkt: '2026-11-10T08:05:00Z', typ: 'ANRUF', text: 'Manipuliert' }] });
  assert.equal(geaendert.verlauf[0].text, 'Original');
  const weg = u.sync({ seit: geaendert.rev, kennung: geaendert.kennung, verlauf: [{ id: V1, fallId: A, geloescht: true }] });
  assert.deepEqual(weg.verlauf.map(v => v.geloescht), [true]);
});

test('Anlagen: Inhalt kommt unverändert zurück, doppeltes Hochladen schadet nicht', () => {
  const u = eingerichtet();
  const bytes = Buffer.from(Array.from({ length: 5000 }, (_, i) => i % 256));
  const meta = { id: N1, fallId: A, anzeigename: 'Lieferschein.pdf', mimeType: 'application/pdf', groesseBytes: 1, hinzugefuegtAm: '2026-11-10T08:06:00Z' };
  assert.throws(() => u.aufruf('anlageHochladen', PIN, N1, bytes.toString('base64')), /Anlage unbekannt/);

  const vorher = u.sync({ seit: 0, faelle: [fall(A)], anlagen: [meta] });
  assert.equal(vorher.anlagen[0].vorhanden, false);
  assert.throws(() => u.aufruf('anlageLaden', PIN, N1), /noch nicht hochgeladen/);
  assert.throws(() => u.aufruf('anlageHochladen', PIN, N1, ''), /leer/);

  u.aufruf('anlageHochladen', PIN, N1, bytes.toString('base64'));
  u.aufruf('anlageHochladen', PIN, N1, bytes.toString('base64'));
  assert.equal(u.dateien('Schadensfaelle/anlagen').length, 1);

  const nachher = u.sync({ seit: vorher.rev, kennung: vorher.kennung });
  assert.deepEqual(nachher.anlagen.map(a => [a.vorhanden, a.groesseBytes, 'dateiId' in a]), [[true, 5000, false]]);
  const geladen = u.aufruf('anlageLaden', PIN, N1);
  assert.equal(geladen.mimeType, 'application/pdf');
  assert.ok(Buffer.from(geladen.base64, 'base64').equals(bytes));
});

test('zu große Anlagen werden abgelehnt', () => {
  const u = eingerichtet();
  u.sync({ seit: 0, faelle: [fall(A)], anlagen: [{ id: N1, fallId: A, anzeigename: 'video.mp4', mimeType: 'video/mp4' }] });
  const zuGross = Buffer.alloc(25 * 1024 * 1024 + 1).toString('base64');
  assert.throws(() => u.aufruf('anlageHochladen', PIN, N1, zuGross), /größer als 25 MB/);
});

test('ein Gerät mit fremder Bestandskennung bekommt alles', () => {
  const u = eingerichtet();
  const erste = u.sync({ seit: 0, faelle: [fall(A)] });
  assert.equal(u.sync({ seit: 99, kennung: erste.kennung }).faelle.length, 0);
  assert.equal(u.sync({ seit: 99, kennung: 'anderer-bestand' }).faelle.length, 1);
  assert.equal(u.sync({ seit: 99 }).faelle.length, 1);
});

test('Eingaben werden bereinigt: fremde Felder fallen weg, kaputte Werte werden ersetzt', () => {
  const u = eingerichtet();
  const r = u.sync({ seit: 0, faelle: [{
    id: A.toUpperCase(), kundeName: 'x'.repeat(30000), beifahrer1: 'Ali Kaya', beifahrer2: 42, status: 'drop table', schadensdatum: '10.11.2026',
    forderungCent: '45000', vergleichCent: -5, wartetAuf: 'VERSICHERUNG', rev: 999, admin: true, geaendertAm: 'unsinn',
  }] });
  const f = r.faelle[0];
  assert.equal(f.id, A);
  assert.equal(f.kundeName.length, 20000);
  assert.deepEqual([f.beifahrer1, f.beifahrer2], ['Ali Kaya', '42']);
  assert.deepEqual([f.status, f.schadensdatum, f.forderungCent, f.vergleichCent, f.wartetAuf, f.rev, f.admin],
    ['NEU', null, 45000, null, 'VERSICHERUNG', 1, undefined]);
  assert.ok(!Number.isNaN(Date.parse(f.geaendertAm)));
  assert.throws(() => u.sync({ seit: 0, faelle: [{ id: '../../etc/passwd' }] }), /Ungültige Kennung/);
  assert.equal(u.sync({ seit: 0 }).faelle.length, 1, 'eine kaputte Anfrage ändert nichts');
});

test('Daten liegen als lesbare Datei in Drive; täglich entsteht eine Sicherung, höchstens 14 bleiben', () => {
  const u = eingerichtet();
  u.sync({ seit: 0, faelle: [fall(A)] });
  const gespeichert = JSON.parse(u.datei('Schadensfaelle/daten.json').bytes.toString('utf8'));
  assert.equal(gespeichert.faelle[A].kundeName, 'Anna Becker');
  assert.equal(u.dateien('Schadensfaelle/sicherungen').length, 0);

  for (let tag = 1; tag <= 20; tag++) {
    u.zeitVor(24 * 3600 * 1000);
    const zeit = new Date(Date.parse('2026-11-10T08:00:00Z') + tag * 3600000).toISOString();
    u.sync({ seit: 0, faelle: [fall(A, { kundeName: 'Stand ' + tag, geaendertAm: zeit })] });
    u.sync({ seit: 0, faelle: [fall(A, { kundeName: 'Stand ' + tag + 'b', geaendertAm: zeit.replace('.000Z', '.500Z') })] });
  }
  const sicherungen = u.dateien('Schadensfaelle/sicherungen');
  assert.equal(sicherungen.length, 14);
  const juengste = sicherungen.map(d => d.name).sort().at(-1);
  assert.match(JSON.parse(u.datei('Schadensfaelle/sicherungen/' + juengste).bytes.toString()).faelle[A].kundeName, /^Stand 19b$/);
});

test('wird der Drive-Ordner gelöscht, beginnt ein neuer Bestand mit neuer Kennung', () => {
  const u = eingerichtet();
  const alt = u.sync({ seit: 0, faelle: [fall(A)] });
  u.wurzel.ordner.find(o => o.name === 'Schadensfaelle').setTrashed(true);
  const neu = u.sync({ seit: alt.rev, kennung: alt.kennung });
  assert.notEqual(neu.kennung, alt.kennung);
  assert.equal(neu.rev, 0);
});

test('eine leere Cloud behält ihre Kennung – sonst würden Geräte endlos neu hochladen', () => {
  const u = eingerichtet();
  const kennung = u.aufruf('anmelden', PIN).kennung;
  assert.equal(u.aufruf('stand', PIN).kennung, kennung);
  assert.equal(u.sync({ seit: 0 }).kennung, kennung);
  assert.equal(u.dateien('Schadensfaelle').filter(d => d.name === 'daten.json').length, 1);
});

test('Auftraggeber: Kontaktdaten am Fall und eigene Liste mit denselben Abgleichregeln', () => {
  const u = eingerichtet();
  const AG = 'cccccccc-0000-4000-8000-000000000001';
  const r = u.sync({ seit: 0, faelle: [fall(A, { auftraggeber: 'Möbelhaus', auftraggeberKontakt: 'Frau Kaya', auftraggeberTelefon: '02191 1', auftraggeberEmail: 'dispo@x.example' })],
    auftraggeber: [{ id: AG, name: 'Möbelhaus', rechtsform: 'GmbH', status: 'INTERESSENT', email: 'dispo@x.example', website: 'moebel.example', geaendertAm: '2026-11-10T08:00:00Z', fremd: 1 }] });
  assert.deepEqual([r.faelle[0].auftraggeberKontakt, r.faelle[0].auftraggeberTelefon, r.faelle[0].auftraggeberEmail], ['Frau Kaya', '02191 1', 'dispo@x.example']);
  assert.deepEqual(r.auftraggeber.map(e => [e.name, e.rechtsform, e.status, e.telefon, e.website, e.fremd]), [['Möbelhaus', 'GmbH', 'INTERESSENT', '', 'moebel.example', undefined]]);

  const aelter = u.sync({ seit: r.rev, kennung: r.kennung, auftraggeber: [{ id: AG, name: 'Alt', geaendertAm: '2026-11-09T08:00:00Z' }] });
  assert.equal(aelter.auftraggeber[0].name, 'Möbelhaus', 'der neuere Stand gewinnt und geht an das Gerät zurück');
  const weg = u.sync({ seit: aelter.rev, kennung: r.kennung, auftraggeber: [{ id: AG, geloescht: true, geaendertAm: '2026-11-11T08:00:00Z' }] });
  assert.deepEqual(weg.auftraggeber.map(e => [e.geloescht, e.name]), [[true, undefined]]);
  const spaeter = u.sync({ seit: weg.rev, kennung: r.kennung, auftraggeber: [{ id: AG, name: 'Wieder da?', geaendertAm: '2026-11-12T08:00:00Z' }] });
  assert.equal(spaeter.auftraggeber[0].geloescht, true, 'gelöscht bleibt gelöscht');
  assert.equal(u.aufruf('status').api, 8);
});

test('CRM: Ansprechpartner, Preise usw. gleichen sich ab und verschwinden mit ihrer Firma', () => {
  const u = eingerichtet();
  const F = 'dddddddd-0000-4000-8000-000000000001';
  const K = 'dddddddd-0000-4000-8000-000000000002';
  const P = 'dddddddd-0000-4000-8000-000000000003';
  const W = 'dddddddd-0000-4000-8000-000000000004';
  const AK = 'dddddddd-0000-4000-8000-000000000005';
  const t = '2026-11-10T08:00:00Z';
  const r = u.sync({ seit: 0,
    faelle: [fall(A, { auftraggeber: 'Seats and Sofas', auftraggeberUuid: F })],
    auftraggeber: [{ id: F, name: 'Seats and Sofas', status: 'AKTIV', geaendertAm: t }],
    ansprechpartner: [{ id: K, firmaId: F, name: 'Jana Krüger', thema: 'Disposition', email: 'jana@seats.example', geaendertAm: t, boese: '<script>' }],
    preise: [{ id: P, firmaId: F, bezeichnung: 'Tourpauschale', betragCent: 18950, einheit: 'pro Tour', gueltigAb: '2026-01-01', geaendertAm: t }],
    aufgaben: [{ id: W, firmaId: F, faelligAm: '2026-11-20', text: 'Preise nachverhandeln', erledigt: false, geaendertAm: t }],
    aktivitaeten: [{ id: AK, firmaId: F, datum: 'kaputt', art: 'ANRUF', text: 'Telefonat', geaendertAm: t }] });
  assert.equal(r.faelle[0].auftraggeberUuid, F);
  assert.deepEqual(r.ansprechpartner.map(k => [k.name, k.thema, k.firmaId, k.boese]), [['Jana Krüger', 'Disposition', F, undefined]]);
  assert.deepEqual(r.preise.map(p => [p.betragCent, p.gueltigAb, p.einheit]), [[18950, '2026-01-01', 'pro Tour']]);
  assert.equal(r.aufgaben[0].erledigt, false);
  assert.equal(r.aktivitaeten[0].datum, '2026-11-10', 'ein Kontakt bekommt immer ein Datum');
  assert.deepEqual([r.adressen, r.dokumente], [[], []]);

  // Ein zweites Gerät hakt die Wiedervorlage ab – die spätere Änderung gewinnt.
  const erledigt = u.sync({ seit: r.rev, kennung: r.kennung, aufgaben: [{ id: W, firmaId: F, faelligAm: '2026-11-20', text: 'Preise nachverhandeln', erledigt: true, geaendertAm: '2026-11-11T08:00:00Z' }] });
  assert.equal(erledigt.aufgaben[0].erledigt, true);
  const aelter = u.sync({ seit: erledigt.rev, kennung: r.kennung, aufgaben: [{ id: W, firmaId: F, text: 'alt', erledigt: false, geaendertAm: '2026-11-09T08:00:00Z' }] });
  assert.equal(aelter.aufgaben[0].erledigt, true, 'der neuere Stand geht an das Gerät zurück');

  // Firma löschen: alles, was an ihr hängt, ist mit weg – auch was ein Gerät später noch schickt.
  const weg = u.sync({ seit: aelter.rev, kennung: r.kennung, auftraggeber: [{ id: F, geloescht: true, geaendertAm: '2026-11-12T08:00:00Z' }] });
  assert.deepEqual(weg.ansprechpartner.map(k => [k.id, k.geloescht]), [[K, true]]);
  assert.deepEqual(weg.preise.map(p => p.geloescht), [true]);
  const nachzuegler = u.sync({ seit: weg.rev, kennung: r.kennung, dokumente: [{ id: N1, firmaId: F, titel: 'Vertrag', geaendertAm: '2026-11-13T08:00:00Z' }] });
  assert.equal(nachzuegler.dokumente[0].geloescht, true);
  assert.equal(u.sync({ seit: 0 }).faelle[0].auftraggeber, 'Seats and Sofas', 'der Fall bleibt erhalten');
});

test('CRM: Zusammenführen zweier Firmen – umgehängte Einträge überleben die Löschung der zweiten', () => {
  const u = eingerichtet();
  const F1 = 'eeeeeeee-0000-4000-8000-000000000001';
  const F2 = 'eeeeeeee-0000-4000-8000-000000000002';
  const K = 'eeeeeeee-0000-4000-8000-000000000003';
  const t = '2026-11-10T08:00:00Z', spaeter = '2026-11-10T09:00:00Z';
  const r = u.sync({ seit: 0, auftraggeber: [{ id: F1, name: 'Media Markt', geaendertAm: t }, { id: F2, name: 'media markt', geaendertAm: t }],
    ansprechpartner: [{ id: K, firmaId: F2, name: 'Herr Ott', geaendertAm: t }] });
  const z = u.sync({ seit: r.rev, kennung: r.kennung, auftraggeber: [{ id: F2, geloescht: true, geaendertAm: spaeter }],
    ansprechpartner: [{ id: K, firmaId: F1, name: 'Herr Ott', geaendertAm: spaeter }] });
  assert.deepEqual(z.ansprechpartner.map(k => [k.firmaId, k.geloescht]), [[F1, undefined]]);
  assert.equal(z.auftraggeber.find(f => f.id === F2).geloescht, true);
});

test('Belege (Angebote/Rechnungen) gleichen sich ab; Firmenfelder, die eine App nicht kennt, bleiben erhalten', () => {
  const u = eingerichtet();
  const F = 'ffffffff-0000-4000-8000-000000000001', R = 'ffffffff-0000-4000-8000-000000000002';
  const r = u.sync({ seit: 0, auftraggeber: [{ id: F, name: 'Seats', kundennummer: 'K-1001', ustId: 'DE123', zahlungszielTage: '30', schlagworte: 'Möbel', geaendertAm: '2026-11-10T08:00:00Z' }],
    belege: [{ id: R, firmaId: F, art: 'RECHNUNG', nummer: 'RE-2026-0001', datum: '2026-11-10', faelligAm: '2026-11-24', status: 'OFFEN',
      positionen: JSON.stringify([{ bez: 'Tourpauschale', menge: 2, einzelCent: 18950 }]), mwstProzent: 19, bezahltCent: 0, geaendertAm: '2026-11-10T08:00:00Z' }] });
  assert.equal(r.belege[0].nummer, 'RE-2026-0001');
  assert.equal(JSON.parse(r.belege[0].positionen)[0].einzelCent, 18950);
  // Die Android-App kennt Kundennummer & Co. nicht und schickt die Firma ohne diese Felder.
  const z = u.sync({ seit: r.rev, kennung: r.kennung, auftraggeber: [{ id: F, name: 'Seats and Sofas', geaendertAm: '2026-11-11T08:00:00Z' }] });
  assert.deepEqual([z.auftraggeber[0].name, z.auftraggeber[0].kundennummer, z.auftraggeber[0].ustId, z.auftraggeber[0].zahlungszielTage, z.auftraggeber[0].schlagworte],
    ['Seats and Sofas', 'K-1001', 'DE123', '30', 'Möbel']);
});
