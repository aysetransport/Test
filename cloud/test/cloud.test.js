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
  assert.equal(u.aufruf('status').api, 10);
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

test('Benutzer-Logins: Admin legt Fahrer an, Fahrer sieht nur Eigenes und ändert nur Erlaubtes', () => {
  const u = eingerichtet();
  const ALI = 'a1a1a1a1-0000-4000-8000-000000000001', BEN = 'b2b2b2b2-0000-4000-8000-000000000002', CEM = 'c3c3c3c3-0000-4000-8000-000000000003';
  const F1 = 'f1f1f1f1-0000-4000-8000-000000000001', F2 = 'f2f2f2f2-0000-4000-8000-000000000002';
  const T1 = 'd1d1d1d1-0000-4000-8000-000000000001', T2 = 'd2d2d2d2-0000-4000-8000-000000000002';
  const heute = new Date().toISOString().slice(0, 10), jetzt = new Date().toISOString();
  const ma = (id, name) => ({ id, firmaId: '', name, rolle: 'FAHRER', aktiv: true, stundenlohnCent: 1500, geaendertAm: jetzt });
  u.sync({ seit: 0, auftraggeber: [{ id: F1, name: 'Möbel Krause', status: 'AKTIV', geaendertAm: jetzt }, { id: F2, name: 'Geheim GmbH', status: 'AKTIV', geaendertAm: jetzt }],
    mitarbeiter: [ma(ALI, 'Ali'), ma(BEN, 'Ben'), ma(CEM, 'Cem')],
    touren: [{ id: T1, firmaId: '', datum: heute, kennzeichen: 'RS-AT 100', auftraggeberId: F1, fahrerId: ALI, beifahrerIds: BEN, name: 'Küche', geaendertAm: jetzt },
      { id: T2, firmaId: '', datum: heute, kennzeichen: 'RS-AT 200', auftraggeberId: F2, fahrerId: CEM, name: 'Fremd', geaendertAm: jetzt }],
    preise: [{ id: 'e1e1e1e1-0000-4000-8000-000000000001', firmaId: F1, bezeichnung: 'Montage', betragCent: 25000, geaendertAm: jetzt }],
    personaleintraege: [{ id: 'e2e2e2e2-0000-4000-8000-000000000001', firmaId: '', mitarbeiterId: ALI, art: 'LOHN', datum: '2026-10-01', bruttoCent: 300000, geaendertAm: jetzt }] });

  // Nur der Admin verwaltet Benutzer; Passwörter nie im Klartext
  assert.equal(u.aufruf('status').benutzer, false);
  assert.throws(() => u.aufruf('benutzerSpeichern', PIN, { login: 'Ali', rolle: 'FAHRER', passwort: '123' }), /mindestens 6/);
  u.aufruf('benutzerSpeichern', PIN, { login: 'Ali.Demir', name: 'Ali Demir', rolle: 'FAHRER', mitarbeiterId: ALI, passwort: 'fahrer-123' });
  u.aufruf('benutzerSpeichern', PIN, { login: 'buero', name: 'Büro', rolle: 'DISPO', passwort: 'buero-123' });
  assert.throws(() => u.aufruf('benutzerSpeichern', PIN, { login: 'ali.demir', rolle: 'FAHRER', passwort: 'xxxxxxx' }), /gibt es schon/);
  assert.ok(!JSON.stringify([...u.eigenschaften]).includes('fahrer-123'));
  assert.equal(u.aufruf('status').benutzer, true);

  assert.throws(() => u.aufruf('einloggen', 'ali.demir', 'falsch'), /Benutzername oder Passwort falsch/);
  const ali = u.aufruf('einloggen', 'ALI.DEMIR', 'fahrer-123');
  assert.equal(ali.rolle, 'FAHRER'); assert.ok(ali.token.startsWith('T:'));
  assert.throws(() => u.aufruf('benutzerListe', ali.token), /Nur für Admins/);

  // Sicht des Fahrers: eigene Tour, deren Firma, Team ohne Lohn; keine fremde Tour, keine Preise, keine Personalakte
  const r = u.aufruf('sync', ali.token, { seit: 0 });
  assert.equal(r.ansicht, 'FAHRER');
  assert.deepEqual(r.touren.map(t => t.id), [T1]);
  assert.deepEqual(r.auftraggeber.map(f => f.name), ['Möbel Krause']);
  assert.deepEqual(r.mitarbeiter.map(m => m.name).sort(), ['Ali', 'Ben']);
  assert.ok(r.mitarbeiter.every(m => m.stundenlohnCent === undefined));
  assert.equal(r.preise.length, 0); assert.equal(r.personaleintraege.length, 0); assert.equal(r.belege.length, 0);

  // Erlaubt: Status der eigenen Tour, eigene Zeit, Krankmeldung, neuer Schadensfall; verboten: fremde Tour, fremde Zeit, Tourdaten, Firmen
  const zurueck = u.aufruf('sync', ali.token, { seit: 0,
    touren: [{ id: T1, status: 'ERLEDIGT', kmEnde: 1234, name: 'umbenannt', fahrerId: CEM, geaendertAm: new Date(Date.now() + 1000).toISOString() },
      { id: T2, status: 'STORNIERT', geaendertAm: new Date(Date.now() + 1000).toISOString() }],
    zeiten: [{ id: 'e3e3e3e3-0000-4000-8000-000000000001', mitarbeiterId: ALI, datum: heute, beginn: '07:00', ende: '15:00', pauseMin: 30, quelle: 'STEMPEL', geaendertAm: jetzt },
      { id: 'e3e3e3e3-0000-4000-8000-000000000002', mitarbeiterId: CEM, datum: heute, beginn: '07:00', ende: '15:00', geaendertAm: jetzt }],
    abwesenheiten: [{ id: 'e4e4e4e4-0000-4000-8000-000000000001', mitarbeiterId: ALI, von: heute, bis: heute, grund: 'Krank', geaendertAm: jetzt }],
    faelle: [fall('e5e5e5e5-0000-4000-8000-000000000001', { tourId: T1, fahrerId: ALI })],
    auftraggeber: [{ id: F2, name: 'gehackt', geaendertAm: new Date(Date.now() + 1000).toISOString() }],
    personaleintraege: [{ id: 'e2e2e2e2-0000-4000-8000-000000000001', mitarbeiterId: ALI, art: 'LOHN', datum: '2026-10-01', bruttoCent: 999999, geaendertAm: new Date(Date.now() + 1000).toISOString() }] });
  assert.equal(zurueck.faelle.length, 1, 'eigener Schadensfall ist sichtbar');
  const admin = u.aufruf('sync', PIN, { seit: 0 }), t1 = admin.touren.find(t => t.id === T1), t2 = admin.touren.find(t => t.id === T2);
  assert.deepEqual([t1.status, t1.kmEnde, t1.name, t1.fahrerId], ['ERLEDIGT', 1234, 'Küche', ALI]);
  assert.equal(t2.status, 'GEPLANT');
  assert.deepEqual(admin.zeiten.map(z => z.mitarbeiterId), [ALI]);
  assert.equal(admin.abwesenheiten.length, 1);
  assert.equal(admin.auftraggeber.find(f => f.id === F2).name, 'Geheim GmbH');
  assert.equal(admin.personaleintraege[0].bruttoCent, 300000);

  // Disposition: alles außer Lohndaten
  const buero = u.aufruf('einloggen', 'buero', 'buero-123');
  const rd = u.aufruf('sync', buero.token, { seit: 0 });
  assert.equal(rd.touren.length, 2); assert.equal(rd.preise.length, 1); assert.equal(rd.personaleintraege.length, 0);
  assert.ok(rd.mitarbeiter.every(m => m.stundenlohnCent === undefined));
  // Lohnabrechnungen in der Personalakte sieht und lädt nur der Admin
  const LA = 'e6e6e6e6-0000-4000-8000-000000000001', FS = 'e6e6e6e6-0000-4000-8000-000000000002';
  u.aufruf('sync', PIN, { seit: 0, anlagen: [{ id: LA, fallId: ALI, anzeigename: 'Lohnabrechnung – Oktober 2026.pdf', mimeType: 'application/pdf', groesseBytes: 3, hinzugefuegtAm: jetzt },
    { id: FS, fallId: ALI, anzeigename: 'Führerschein – vorne.jpg', mimeType: 'image/jpeg', groesseBytes: 3, hinzugefuegtAm: jetzt }] });
  assert.deepEqual(u.aufruf('sync', buero.token, { seit: 0 }).anlagen.map(x => x.id), [FS]);
  assert.equal(u.aufruf('sync', PIN, { seit: 0 }).anlagen.length, 2);
  assert.throws(() => u.aufruf('anlageLaden', buero.token, LA), /Kein Zugriff/);
  u.aufruf('sync', buero.token, { seit: 0, mitarbeiter: [{ ...ma(ALI, 'Ali D.'), stundenlohnCent: 1, geaendertAm: new Date(Date.now() + 2000).toISOString() }] });
  const nachDispo = u.aufruf('sync', PIN, { seit: 0 }).mitarbeiter.find(m => m.id === ALI);
  assert.deepEqual([nachDispo.name, nachDispo.stundenlohnCent], ['Ali D.', 1500], 'Disposition ändert Namen, aber nicht den Lohn');

  // Sperren beendet die Sitzung sofort; Abmelden ebenso
  const liste = u.aufruf('benutzerListe', PIN), aliB = liste.find(b => b.login === 'ali.demir');
  u.aufruf('benutzerSpeichern', PIN, { ...aliB, aktiv: false });
  assert.throws(() => u.aufruf('sync', ali.token, { seit: 0 }), /erneut anmelden/);
  assert.throws(() => u.aufruf('einloggen', 'ali.demir', 'fahrer-123'), /falsch/);
  u.aufruf('abmelden', buero.token);
  assert.throws(() => u.aufruf('stand', buero.token), /erneut anmelden/);
  // Nach acht Fehlversuchen ist der Login gesperrt
  for (let i = 0; i < 8; i++) assert.throws(() => u.aufruf('einloggen', 'buero', 'falsch'), /falsch/);
  assert.throws(() => u.aufruf('einloggen', 'buero', 'buero-123'), /GESPERRT/);
});

test('Statuslink: Kunde sieht den Lieferstatus ohne Anmeldung und bewertet einmal', () => {
  const u = eingerichtet();
  const T = 'f1f1f1f1-0000-4000-8000-000000000001', F = 'f2f2f2f2-0000-4000-8000-000000000002', CODE = 'Ab12Cd34Ef56Gh78Ij90';
  const jetzt = new Date().toISOString();
  u.sync({ seit: 0, auftraggeber: [{ id: F, name: 'Möbel Krause', status: 'AKTIV', geaendertAm: jetzt }],
    touren: [{ id: T, firmaId: '', datum: '2026-11-20', kennzeichen: 'RS-AT 100', auftraggeberId: F, fahrerId: 'x', zeitfenster: '8–12 Uhr', statusCode: CODE, kundeName: 'Frau Meier', kundeTelefon: '0171 9', geaendertAm: jetzt }] });
  assert.throws(() => u.aufruf('sendungsstatus', 'kurz'), /ungültig/);
  assert.throws(() => u.aufruf('sendungsstatus', 'Xb12Cd34Ef56Gh78Ij90'), /ungültig/);
  const s = u.aufruf('sendungsstatus', CODE);
  assert.deepEqual([s.statusText, s.zeitfenster, s.im_auftrag, s.bewertbar], ['Geplant', '8–12 Uhr', 'Möbel Krause', false]);
  assert.ok(!JSON.stringify(s).includes('0171'), 'keine Telefonnummer im Status');
  assert.throws(() => u.aufruf('bewerten', CODE, 5, 'super'), /erst nach der Lieferung/);
  // Fahrer liefert ab
  u.sync({ seit: 0, touren: [{ id: T, firmaId: '', datum: '2026-11-20', kennzeichen: 'RS-AT 100', auftraggeberId: F, fahrerId: 'x', zeitfenster: '8–12 Uhr', statusCode: CODE, status: 'ERLEDIGT', abgeliefertAm: jetzt, empfaenger: 'Meier', geaendertAm: new Date(Date.now() + 1000).toISOString() }] });
  assert.equal(u.aufruf('sendungsstatus', CODE).bewertbar, true);
  assert.throws(() => u.aufruf('bewerten', CODE, 9), /1 bis 5/);
  u.aufruf('bewerten', CODE, 4, 'Pünktlich, freundlich');
  assert.throws(() => u.aufruf('bewerten', CODE, 1), /schon bewertet/);
  // Ein Gerät mit altem Stand speichert die Tour später – die Bewertung bleibt
  u.sync({ seit: 0, touren: [{ id: T, firmaId: '', datum: '2026-11-20', kennzeichen: 'RS-AT 100', auftraggeberId: F, fahrerId: 'x', statusCode: CODE, status: 'ERLEDIGT', notiz: 'neu', geaendertAm: new Date(Date.now() + 60000).toISOString() }] });
  const t = u.sync({ seit: 0 }).touren.find(x => x.id === T);
  assert.deepEqual([t.bewertung, t.bewertungText, t.notiz], [4, 'Pünktlich, freundlich', 'neu']);
});
