'use strict';
/**
 * Bedient die Web-App in einem echten Browser gegen das lokale Cloud-Skript (cloud/test/mock-server.js).
 * Start:  node --test web/test/
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { chromium } = require('playwright');
const { starteCloud, starteWebseite } = require('../../cloud/test/mock-server');

const PIN = 'geheim-2026';
let browser, seite;
test.before(async () => { browser = await chromium.launch(); seite = await starteWebseite(path.join(__dirname, '..')); });
test.after(async () => { await browser.close(); await seite.schliessen(); });

/** Ein „Gerät“ = eigener Browser-Speicher. Mit cloudUrl ist die Cloud-Adresse schon eingetragen. */
async function geraet(cloudUrl, optionen = {}) {
  const { bereich, ...kontextOptionen } = optionen;
  const kontext = await browser.newContext({ serviceWorkers: 'block', viewport: { width: 1200, height: 900 }, acceptDownloads: true, ...kontextOptionen });
  // Tests sprechen nie die echte Cloud an: entweder die Test-Cloud oder „nur auf diesem Gerät“.
  await kontext.route('https://script.google.com/**', route => route.abort());
  await kontext.addInitScript(([url, bereich]) => {
    if (!localStorage.getItem('schadensfaelle-v1')) {
      localStorage.setItem('schadensfaelle-v1', JSON.stringify(url ? { apiUrl: url } : { nurLokal: true }));
      // Die App startet bei den Firmen; die meisten Tests arbeiten in der Fallliste („Schäden“).
      localStorage.setItem('crm-bereich', bereich);
    }
  }, [cloudUrl || '', optionen.bereich || 'faelle']);
  const s = await kontext.newPage();
  s.on('pageerror', fehler => { throw fehler; });
  await s.goto(seite.url);
  await s.waitForFunction(() => window.__appBereit);
  return s;
}
async function melde(s, pin = PIN, neu = false) {
  await s.locator('#pin1').fill(pin);
  if (neu) await s.locator('#pin2').fill(pin);
  await s.locator('#pin-form button.voll').click();
}
// Läuft das Warten ab, zeigt die Meldung, woran der Abgleich hängt (statt nur „Timeout“).
const aktuell = s => s.waitForFunction(() => document.querySelector('#sync').dataset.zustand === 'aktuell' && document.querySelector('#sync').textContent === 'Cloud aktuell')
  .catch(async e => { const z = await s.evaluate(() => ({ anzeige: document.querySelector('#sync').textContent, zustand: document.querySelector('#sync').dataset.zustand, fehler: SYNC.fehler, laeuft: SYNC.laeuft, nochmal: SYNC.nochmal, offen: TABELLEN.reduce((n, t) => n + offene(t).length, 0) })).catch(() => ({})); throw new Error(e.message.split('\n')[0] + ' – Sync: ' + JSON.stringify(z)); });
const abgleich = async s => { await s.evaluate(() => abgleich()); await aktuell(s); };
async function legeFallAn(s, felder) {
  await s.locator('[data-neu]').first().click();
  for (const [name, wert] of Object.entries(felder)) await s.locator(`#fall-form [name="${name}"]`).fill(wert);
  await s.locator('#fall-form button.voll').click();
  await s.locator('#wiedervorlage').waitFor();
  // Nach dem Speichern wird die Ansicht des Falls einmal neu gezeichnet – erst danach weiterarbeiten.
  await s.waitForFunction(() => location.hash.startsWith('#/fall/'));
}
const cloudDaten = cloud => JSON.parse(cloud.umgebung.datei('Schadensfaelle/daten.json').bytes.toString('utf8'));
const jahr = new Date().getFullYear();

test('Fachlogik im Browser stimmt mit der Android-App überein', async () => {
  const s = await geraet();
  const r = await s.evaluate(() => ({
    geld: ['1.234,56', '1,234.56', '1234,56 €', '450', '12.50', '1,5', '1.234', '1.234.567', '', 'abc', '-5', '1,234', '1,2,3', '12.5.1', ','].map(geldLesen),
    format: [geld(123456), geld(5), geldOhneSymbol(123456)],
    nummer: [naechsteFallnummer([], 2026), naechsteFallnummer([{ fallnummer: '2026-001' }, { fallnummer: '2026-007' }, { fallnummer: '2025-099' }, { fallnummer: '2026-abc' }], 2026)],
    csv: ['=SUMME(A1)', '@cmd', "+cmd|' /C calc'!A0", '+49 171 1234567', '-12,50', 'a;b "c"'].map(csvZelle),
    vorlage: vorlageText('EINGANGSBESTAETIGUNG', { fallnummer: '2026-007', kundeName: 'Anna Becker', kundenAktenzeichen: 'AB-4711', schadensdatum: '2026-11-03', meldedatum: '2026-11-05' },
      { firma: 'Muster Transport GmbH', bearbeiter: 'Kim Beispiel', funktion: 'Schadensbearbeitung' }),
    sachstand: vorlageText('SACHSTANDSANFRAGE', { fallnummer: '2026-007', kundeName: 'Anna Becker', versicherungsNummer: 'VS-99', schadensdatum: '2026-11-03' }, {}),
    faellig: [-3, -1, 0, 1, 5].map(t => faelligkeit({ status: 'NEU', wiedervorlageAm: plusTage(heute(), t) }).text)
      .concat(faelligkeit({ status: 'ERLEDIGT', wiedervorlageAm: plusTage(heute(), -3) }).text),
  }));
  assert.deepEqual(r.geld, [123456, 123456, 123456, 45000, 1250, 150, 123400, 123456700, null, null, null, null, null, null, null]);
  assert.deepEqual(r.format, ['1.234,56 €', '0,05 €', '1234,56']);
  assert.deepEqual(r.nummer, ['2026-001', '2026-008']);
  assert.deepEqual(r.csv, ["'=SUMME(A1)", "'@cmd", "'+cmd|' /C calc'!A0", '+49 171 1234567', '-12,50', '"a;b ""c"""']);
  assert.equal(r.vorlage.betreff, 'Ihre Schadensmeldung vom 05.11.2026 – Eingangsbestätigung – Ihr Zeichen AB-4711 – unser Vorgang 2026-007');
  assert.ok(r.vorlage.text.startsWith('Guten Tag Anna Becker,\n\nwir bestätigen den Eingang Ihrer Schadensmeldung zum Vorfall vom 03.11.2026.'));
  assert.ok(r.vorlage.text.endsWith('Mit freundlichen Grüßen\n\ni.A. Kim Beispiel\nSchadensbearbeitung\nMuster Transport GmbH'));
  assert.ok(r.sachstand.text.startsWith('Sehr geehrte Damen und Herren,') && r.sachstand.text.includes('Schadennummer: VS-99') && r.sachstand.text.endsWith('Mit freundlichen Grüßen'));
  assert.deepEqual(r.faellig, ['seit 3 Tagen überfällig', 'seit gestern überfällig', 'heute fällig', 'morgen fällig', 'in 5 Tagen fällig', 'Keine Wiedervorlage']);
});

test('ohne Cloud: Fall anlegen, nachhaken, abschließen – bleibt nach dem Neuladen erhalten', async () => {
  const s = await geraet();
  await s.getByText('Noch keine Schadensfälle').waitFor();
  assert.equal(await s.locator('#sync').textContent(), 'Nur auf diesem Gerät');

  await s.locator('[data-neu]').first().click();
  await s.locator('#fall-form button.voll').click();
  await s.getByText('Bitte mindestens Name, Firma oder Schadensbeschreibung angeben.').waitFor();
  await s.locator('[name=kundeName]').fill('Anna Becker');
  await s.locator('[name=forderung]').fill('12,345');
  await s.locator('#fall-form button.voll').click();
  await s.getByText('Kein gültiger Betrag, z. B. 1.250,00').waitFor();
  await s.locator('[name=forderung]').fill('450');
  await s.locator('[name=beschreibung]').fill('Sofa beim Eintragen beschädigt');
  await s.locator('[name=tour]').fill('Tour 3');
  await s.locator('#fall-form button.voll').click();

  await s.getByText(`Fall ${jahr}-001 ·`).waitFor();
  await s.locator('#detail').getByText('450,00').waitFor();
  await s.locator('#wiedervorlage').getByText('in 7 Tagen fällig').waitFor();
  await s.locator('#liste').getByText('Offen (1)').waitFor();

  await s.getByRole('button', { name: '✓ Nachgehakt' }).click();
  await s.locator('#ergebnis').fill('Kundin schickt die Fotos bis Freitag');
  await s.getByRole('button', { name: '2 Wochen' }).click();
  await s.locator('#wv-form').getByRole('button', { name: 'Geschädigter' }).click();
  await s.locator('#schritt').fill('Fotos prüfen');
  await s.locator('#wv-form button.voll').click();
  await s.locator('#wiedervorlage').getByText('in 14 Tagen fällig').waitFor();
  await s.locator('#wiedervorlage').getByText('Wartet auf Geschädigter · Fotos prüfen').waitFor();
  await s.locator('.verlauf').getByText('Kundin schickt die Fotos bis Freitag').waitFor();

  await s.reload();
  await s.locator('.verlauf').getByText('Kundin schickt die Fotos bis Freitag').waitFor();

  await s.getByRole('button', { name: 'Status ändern' }).click();
  await s.getByLabel('Erledigt').check();
  await s.getByRole('button', { name: 'Übernehmen' }).click();
  await s.getByText('Der Fall ist abgeschlossen').waitFor();
  await s.locator('#liste').getByText('Keine offenen Fälle').waitFor();
  await s.locator('#liste').getByRole('button', { name: 'Abgeschlossen' }).click();
  await s.locator('#liste').getByText('Anna Becker').waitFor();
});

test('mit der Cloud verbinden: PIN festlegen, Fälle landen in Google Drive', async () => {
  const cloud = await starteCloud();
  const s = await geraet();
  await legeFallAn(s, { kundeName: 'Anna Becker', beschreibung: 'Schrankwand beschädigt' });

  await s.locator('#einstellungen-knopf').click();
  await s.locator('#cloud-url').fill(cloud.url);
  await s.locator('#verbinden').click();
  await s.getByRole('heading', { name: 'PIN festlegen' }).waitFor();
  await s.locator('#pin1').fill(PIN); await s.locator('#pin2').fill('anders');
  await s.locator('#pin-form button.voll').click();
  await s.getByText('Die beiden Eingaben stimmen nicht überein.').waitFor();
  await melde(s, PIN, true);
  await aktuell(s);

  const d = cloudDaten(cloud);
  assert.deepEqual(Object.values(d.faelle).map(f => [f.kundeName, f.fallnummer]), [['Anna Becker', `${jahr}-001`]]);
  assert.equal(Object.values(d.verlauf)[0].text, `Fall ${jahr}-001 angelegt`);
  await cloud.schliessen();
});

test('zwei Geräte: Änderungen wandern in beide Richtungen, falsche PIN wird abgelehnt', async () => {
  const cloud = await starteCloud();
  const a = await geraet(cloud.url);
  await melde(a, PIN, true); await aktuell(a);
  await legeFallAn(a, { kundeName: 'Carl Demir', kundeTelefon: '0171 5550142', fahrer: 'M. Yilmaz', beifahrer1: 'Ali Kaya', beifahrer2: 'Ben Ott' });
  await aktuell(a);

  const b = await geraet(cloud.url);
  await b.getByRole('heading', { name: 'PIN eingeben' }).waitFor();
  await melde(b, 'falsche-pin');
  await b.getByText('Die PIN war falsch.').waitFor();
  await melde(b); await aktuell(b);
  await b.locator('#liste').getByText('Carl Demir').click();
  await b.locator('#detail dd', { hasText: 'Ali Kaya, Ben Ott' }).waitFor(); // Beifahrer kommen über die Cloud mit
  await b.getByRole('button', { name: 'Status ändern' }).click();
  await b.getByLabel('Bei Versicherung').check();
  await b.locator('#status-notiz').fill('An die Versicherung gemeldet');
  await b.getByRole('button', { name: 'Übernehmen' }).click();
  await aktuell(b);

  await a.evaluate(() => pruefeStand());
  await a.locator('#detail .marke', { hasText: 'Bei Versicherung' }).waitFor();
  await a.locator('.verlauf').getByText('An die Versicherung gemeldet').waitFor();

  // Notiz während des Tippens: ein Abgleich im Hintergrund darf die Eingabe nicht verwerfen.
  await a.locator('#notiz').fill('Halb getippte Not');
  await b.locator('#notiz').fill('Notiz vom zweiten Gerät'); await b.locator('#notiz-form button').click(); await aktuell(b);
  await abgleich(a);
  await a.locator('.verlauf').getByText('Notiz vom zweiten Gerät').waitFor();
  assert.equal(await a.locator('#notiz').inputValue(), 'Halb getippte Not');
  await cloud.schliessen();
});

test('abmelden und wieder anmelden; lange Mail-Texte werden gekürzt', async () => {
  const cloud = await starteCloud();
  const a = await geraet(cloud.url, { bereich: 'firmen' });
  await melde(a, PIN, true); await aktuell(a);
  await a.evaluate(() => { location.hash = '#/einstellungen'; });
  await a.locator('#abmelden').click();
  await a.waitForFunction(() => document.querySelector('#sync').textContent === 'Anmeldung nötig');
  await a.locator('#sync').click();
  await a.getByRole('heading', { name: 'PIN eingeben' }).waitFor();
  await a.locator('#pin-spaeter').click(); // „Später“ – danach muss die Anmeldung trotzdem wieder gehen
  await a.evaluate(() => dialog('<p>anderes Fenster</p>'));
  await a.evaluate(() => $('#dialog').close());
  await a.locator('#sync').click();
  await melde(a); await aktuell(a);
  // Bereits gespeicherte, sehr lange Mail in der Kontakthistorie wird beim Start gekürzt
  const lang = await a.evaluate(() => {
    const f = merke('auftraggeber', { id: uuid(), name: 'Lang GmbH', status: 'AKTIV', geaendertAm: jetztIso() });
    merke('aktivitaeten', { id: uuid(), firmaId: f.id, art: 'EMAIL', datum: heute(), text: 'x'.repeat(20000), geaendertAm: jetztIso() });
    speichere(); return langeMailsKuerzen() && Object.values(S.aktivitaeten).find(x => x.firmaId === f.id).text.length;
  });
  assert.ok(lang <= 2000, 'gekürzt auf höchstens 2000 Zeichen');
  await cloud.schliessen();
});

test('ohne Netz wird weitergearbeitet und später nachgereicht', async () => {
  const cloud = await starteCloud();
  const s = await geraet(cloud.url);
  await melde(s, PIN, true); await aktuell(s);
  await legeFallAn(s, { kundeName: 'Dora Engel' }); await aktuell(s);

  cloud.zustand.gestoert = true;
  await s.locator('#notiz').fill('Unterwegs notiert'); await s.locator('#notiz-form button').click();
  await s.locator('#sync', { hasText: 'Offline · 2 Änderungen wartet' }).waitFor();
  await s.reload(); // auch nach einem Neustart der Seite geht nichts verloren
  await s.locator('.verlauf').getByText('Unterwegs notiert').waitFor();

  cloud.zustand.gestoert = false;
  await abgleich(s);
  assert.ok(Object.values(cloudDaten(cloud).verlauf).some(v => v.text === 'Unterwegs notiert'));
  await cloud.schliessen();
});

test('Anlage: hochladen, auf dem zweiten Gerät ansehen, dort löschen', async () => {
  const cloud = await starteCloud();
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64');
  const a = await geraet(cloud.url);
  await melde(a, PIN, true); await aktuell(a);
  await legeFallAn(a, { kundeName: 'Emil Frank' });
  await aktuell(a); // der Abgleich nach dem Anlegen zeichnet den Fall neu – erst danach die Datei wählen
  await a.locator('#datei').setInputFiles({ name: 'Sofa Armlehne.png', mimeType: 'image/png', buffer: png });
  await a.locator('.anlage img').waitFor();
  await aktuell(a);
  assert.equal(cloud.umgebung.dateien('Schadensfaelle/anlagen').length, 1);
  assert.ok(cloud.umgebung.dateien('Schadensfaelle/anlagen')[0].bytes.equals(png), 'Datei liegt unverändert in Drive');

  const b = await geraet(cloud.url);
  await melde(b); await aktuell(b);
  await b.locator('#liste').getByText('Emil Frank').click();
  await b.getByText('Anlagen (1)').waitFor();
  await b.locator('.anlage img').waitFor(); // wurde aus der Cloud nachgeladen
  await b.locator('.verlauf').getByText('Anlage hinzugefügt: Sofa Armlehne.png').waitFor();

  await b.getByRole('button', { name: 'Anlage Sofa Armlehne.png entfernen' }).click();
  await b.getByRole('button', { name: 'Entfernen', exact: true }).click();
  await aktuell(b);
  assert.equal(cloud.umgebung.dateien('Schadensfaelle/anlagen').length, 0);
  await abgleich(a);
  await a.getByText('Noch keine Fotos oder Belege.').waitFor();
  await cloud.schliessen();
});

test('gleiche Fallnummer auf zwei Geräten wird beim Abgleich aufgelöst; Löschen wirkt überall', async () => {
  const cloud = await starteCloud();
  const a = await geraet(cloud.url);
  await melde(a, PIN, true); await aktuell(a);
  const b = await geraet(cloud.url);
  await melde(b); await aktuell(b);

  cloud.zustand.gestoert = true; // beide arbeiten ohne Netz
  await legeFallAn(a, { kundeName: 'Gerät A' });
  await legeFallAn(b, { kundeName: 'Gerät B' });
  cloud.zustand.gestoert = false;
  await abgleich(a); await abgleich(b); await abgleich(a);
  for (const s of [a, b]) {
    await s.locator('#liste').getByText('Gerät A').waitFor();
    await s.locator('#liste').getByText('Gerät B').waitFor();
  }
  const nummern = Object.values(cloudDaten(cloud).faelle).map(f => f.fallnummer).sort();
  assert.deepEqual(nummern, [`${jahr}-001`, `${jahr}-002`]);
  await b.locator('#detail').getByText(`Fall ${jahr}-002`).waitFor();

  await b.getByRole('button', { name: 'Weitere Aktionen' }).click();
  await b.locator('#loeschen').click();
  await b.getByRole('button', { name: 'Löschen', exact: true }).click();
  await aktuell(b);
  await abgleich(a);
  await a.locator('#liste').getByText('Offen (1)').waitFor();
  assert.equal(await a.locator('#liste').getByText('Gerät B').count(), 0);
  assert.ok(!JSON.stringify(cloudDaten(cloud)).includes('Gerät B'));
  await cloud.schliessen();
});

test('E-Mail-Vorlage, Zusammenfassung und CSV-Export', async () => {
  const kontextOptionen = { permissions: ['clipboard-read', 'clipboard-write'] };
  const s = await geraet(null, kontextOptionen);
  await legeFallAn(s, { kundeName: 'Anna Becker', kundeEmail: 'a.becker@example.org', tour: 'Tour 3', forderung: '1.280' });

  await s.getByRole('button', { name: '✉ E-Mail' }).click();
  await s.locator('[data-vorlage=ERINNERUNG]').click();
  const mail = await s.evaluate(() => window.__geoeffnet);
  assert.ok(mail.startsWith('mailto:a.becker@example.org?subject='));
  assert.ok(decodeURIComponent(mail).includes(`Erinnerung – unser Vorgang ${jahr}-001`));
  await s.locator('.verlauf').getByText('E-Mail an Geschädigter vorbereitet: Erinnerung').waitFor();

  await s.getByRole('button', { name: 'Weitere Aktionen' }).click();
  await s.locator('#teilen').click();
  await s.getByText('Text in die Zwischenablage kopiert').waitFor();
  const kopiert = await s.evaluate(() => navigator.clipboard.readText());
  assert.ok(kopiert.startsWith(`Schadensfall ${jahr}-001\nStatus: Neu\nGeschädigter: Anna Becker`));

  await s.locator('#einstellungen-knopf').click();
  const [download] = await Promise.all([s.waitForEvent('download'), s.locator('#csv').click()]);
  const csv = require('node:fs').readFileSync(await download.path(), 'utf8');
  assert.ok(csv.startsWith('﻿Fall-Nr.;Status;Tour;'));
  assert.ok(csv.includes(`${jahr}-001;Neu;Tour 3;`) && csv.includes(';1280,00;'));
});

test('Auftraggeber: Vorlage an dessen Adresse, Auswahl aus der Liste, Abgleich der Liste', async () => {
  const cloud = await starteCloud();
  const a = await geraet(cloud.url);
  await melde(a, PIN, true); await aktuell(a);
  await legeFallAn(a, { kundeName: 'Anna Becker', kundeEmail: 'a.becker@example.org', auftraggeber: 'Möbelhaus Nordpark',
    auftraggeberKontakt: 'Frau Kaya', auftraggeberTelefon: '02191 123', auftraggeberEmail: 'dispo@nordpark.example', auftragsReferenz: 'LS-48213' });
  await a.locator('#detail').getByRole('heading', { name: 'Auftraggeber' }).waitFor();

  await a.getByRole('button', { name: '✉ E-Mail' }).click();
  await a.getByText('An den Auftraggeber').waitFor();
  await a.locator('[data-vorlage=SCHADENSMITTEILUNG]').click();
  const mail = decodeURIComponent(await a.evaluate(() => window.__geoeffnet));
  assert.ok(mail.startsWith('mailto:dispo@nordpark.example?subject='), mail);
  assert.ok(mail.includes('Guten Tag Frau Kaya,') && mail.includes('Ihr Auftrag LS-48213') && !mail.includes('a.becker@'));
  await a.locator('.verlauf').getByText('E-Mail an Auftraggeber vorbereitet: Schadensmitteilung').waitFor();

  // Nur der Auftraggeber hat eine Nummer: Anrufen wählt ihn direkt.
  await a.locator('#detail').getByRole('button', { name: '☎ Anrufen' }).click();
  assert.equal(await a.locator('[data-anruf]').count(), 0);
  assert.equal(await a.evaluate(() => window.__geoeffnet), 'tel:02191123');
  // Mit Nummer beim Geschädigten fragt die App, wen sie anrufen soll.
  await a.getByRole('button', { name: 'Bearbeiten' }).click();
  await a.locator('[name=kundeTelefon]').fill('0171 555');
  await a.locator('#fall-form button.voll').click();
  await a.locator('#detail').getByRole('button', { name: '☎ Anrufen' }).click();
  await a.locator('[data-anruf=GESCHAEDIGTER]').click();
  assert.equal(await a.evaluate(() => window.__geoeffnet), 'tel:0171555');

  // Der Auftraggeber steht jetzt in der Liste – auf dem zweiten Gerät auswählbar.
  await aktuell(a);
  const b = await geraet(cloud.url);
  await melde(b); await aktuell(b);
  await b.locator('[data-neu]').first().click();
  await b.locator('[name=kundeName]').fill('Carl Demir');
  await b.locator('#ag-auswahl').selectOption({ label: 'Möbelhaus Nordpark · Frau Kaya' });
  assert.equal(await b.locator('[name=auftraggeberKontakt]').inputValue(), 'Frau Kaya');
  assert.equal(await b.locator('[name=auftraggeberEmail]').inputValue(), 'dispo@nordpark.example');
  await b.locator('#fall-form button.voll').click();
  await b.locator('#detail dd', { hasText: '02191 123' }).waitFor();

  // In der Firma bearbeiten – A übernimmt die Änderung.
  await b.locator('#zur-firma').click();
  await b.locator('[data-crm=ansprechpartner]', { hasText: 'Frau Kaya' }).click();
  await b.locator('#crm-form [name=thema]').fill('Schäden, Reklamationen');
  await b.locator('#crm-form button.voll').click();
  await aktuell(b);
  await abgleich(a);
  assert.deepEqual(await a.evaluate(() => auftraggeberListe().map(e => [e.name, kontakteVon(e.id).map(k => [k.name, k.thema])])),
    [['Möbelhaus Nordpark', [['Frau Kaya', 'Schäden, Reklamationen']]]]);
  await cloud.schliessen();
});

test('mit veraltetem Cloud-Skript gleicht die Web-App nicht ab', async () => {
  const cloud = await starteCloud();
  cloud.umgebung.kontext.API_VERSION = 3; // ein älteres Skript ohne CRM
  const s = await geraet(cloud.url);
  await melde(s, PIN, true);
  await s.locator('#sync', { hasText: 'veraltet' }).waitFor();
  await legeFallAn(s, { kundeName: 'Anna Becker', auftraggeber: 'Möbelhaus', auftraggeberEmail: 'dispo@m.example' });
  await s.locator('#sync', { hasText: 'veraltet' }).waitFor();
  assert.equal(Object.keys(JSON.parse(cloud.umgebung.datei('Schadensfaelle/daten.json').bytes.toString()).faelle).length, 0, 'nichts gesendet');
  cloud.umgebung.kontext.API_VERSION = 17;
  await abgleich(s);
  assert.equal(Object.values(JSON.parse(cloud.umgebung.datei('Schadensfaelle/daten.json').bytes.toString()).faelle)[0].auftraggeberEmail, 'dispo@m.example');
  await cloud.schliessen();
});

test('CRM: Firma mit Ansprechpartnern, Preisen und Wiedervorlagen; Fall aus der Firma; Abgleich und Suche', async () => {
  const cloud = await starteCloud();
  const a = await geraet(cloud.url, { bereich: 'firmen' });
  await melde(a, PIN, true); await aktuell(a);
  await a.getByText('Noch keine Auftraggeber').waitFor();

  await a.locator('[data-neue-firma]').click();
  await a.locator('#crm-form [name=name]').fill('Seats and Sofas Krefeld');
  await a.locator('#crm-form [name=telefon]').fill('02151 123');
  await a.locator('#crm-form button.voll').click();
  await a.locator('#stammdaten', { hasText: 'Seats and Sofas Krefeld' }).waitFor();

  const neu = async (tabelle, felder) => {
    await a.locator(`[data-crm-neu=${tabelle}]`).click();
    for (const [name, wert] of Object.entries(felder)) {
      const feld = a.locator(`#crm-form [name=${name}]`);
      if (await feld.evaluate(el => el.tagName) === 'SELECT') await feld.selectOption(wert); else await feld.fill(wert);
    }
    await a.locator('#crm-form button.voll').click();
  };
  await neu('ansprechpartner', { name: 'Jana Krüger', thema: 'Schäden', email: 'jana@seats.example' });
  await neu('preise', { bezeichnung: 'Tourpauschale', betrag: '189,50', einheit: 'pro Tour' });
  await neu('aufgaben', { text: 'Preise 2027 verhandeln', faelligAm: '2020-01-01' });
  await neu('adressen', { art: 'LAGER', strasse: 'Industriestr. 5', plz: '47803', ort: 'Krefeld' });
  await neu('aktivitaeten', { art: 'ANRUF', kontakt: 'Jana Krüger', text: 'Mehr Touren ab Dezember' });
  await neu('dokumente', { titel: 'Rahmenvertrag', link: 'https://drive.example/vertrag' });
  await a.locator('#detail').getByText('Tourpauschale: 189,50 € pro Tour').waitFor();
  await a.locator('#detail').getByText('Lager').waitFor();

  // Neuer Fall aus der Firma: Auftraggeber und Ansprechpartner für Schäden sind vorbelegt.
  await a.locator('[data-crm-neu=faelle]').click();
  assert.equal(await a.locator('[name=auftraggeber]').inputValue(), 'Seats and Sofas Krefeld');
  assert.equal(await a.locator('[name=auftraggeberEmail]').inputValue(), 'jana@seats.example');
  await a.locator('[name=kundeName]').fill('Anna Becker');
  await a.locator('#fall-form button.voll').click();
  await a.locator('#wiedervorlage').waitFor();
  const fall = await a.evaluate(() => faelle()[0]);
  assert.equal(fall.auftraggeberUuid, await a.evaluate(() => auftraggeberListe()[0].id));
  assert.equal(await a.evaluate(() => alle('ansprechpartner').length), 1, 'kein doppelter Ansprechpartner');

  // Termine: die überfällige Wiedervorlage der Firma und der Fall
  await a.locator('#navi [data-bereich=termine]:visible').click();
  await a.locator('#termine').getByText('Überfällig (1)').waitFor();
  await a.locator('#termine').getByText('Preise 2027 verhandeln').waitFor();
  await a.locator('#navi [data-bereich=firmen]:visible').click();
  await a.locator('#firmen-suche').fill('krüger');
  await a.locator('#firmen').getByText('Ansprechpartner: Jana Krüger').waitFor();

  // Zweites Gerät bekommt alles; dort wird die Wiedervorlage erledigt.
  await aktuell(a);
  const b = await geraet(cloud.url, { bereich: 'termine' });
  await melde(b); await aktuell(b);
  await b.locator('#termine [data-erledigt]').first().click();
  await b.locator('#termine').getByText('Preise 2027 verhandeln').waitFor({ state: 'detached' });
  await aktuell(b);
  await abgleich(a);
  assert.equal(await a.evaluate(() => alle('aufgaben')[0].erledigt), true);
  const daten = cloudDaten(cloud);
  assert.deepEqual(Object.values(daten.preise).map(p => [p.bezeichnung, p.betragCent]), [['Tourpauschale', 18950]]);
  assert.equal(Object.values(daten.faelle)[0].auftraggeberUuid, Object.keys(daten.auftraggeber)[0]);
  await cloud.schliessen();
});

test('CRM als CSV exportieren', async () => {
  const s = await geraet('', { bereich: 'firmen' });
  await legeFallAnUeberSchaeden(s);
  await s.evaluate(() => { const f = firmaZumNamen('Seats and Sofas Krefeld'); Aktionen.crmSpeichern('preise', { firmaId: f.id, bezeichnung: 'Etage', betragCent: 1250, einheit: 'pro Etage', gueltigAb: '2026-01-01', notiz: '' }); });
  await s.locator('#einstellungen-knopf').click();
  const downloads = [];
  s.on('download', d => downloads.push(d));
  await s.locator('#crm-csv').click();
  await s.waitForFunction(() => true);
  await new Promise(ok => setTimeout(ok, 500));
  assert.deepEqual(downloads.map(d => d.suggestedFilename().replace(/_\d{4}-\d{2}-\d{2}/, '')).sort(),
    ['CRM_Aktivitaeten.csv', 'CRM_Ansprechpartner.csv', 'CRM_Firmen.csv', 'CRM_Preise.csv', 'CRM_Verkaufschancen.csv', 'CRM_Wiedervorlagen.csv']);
  const preise = require('node:fs').readFileSync(await downloads.find(d => d.suggestedFilename().startsWith('CRM_Preise')).path(), 'utf8');
  assert.ok(preise.startsWith('\uFEFFFirma;Bezeichnung;Betrag (EUR);Einheit;Gültig ab;Notiz\r\n'), preise);
  assert.ok(preise.includes('Seats and Sofas Krefeld;Etage;12,50;pro Etage;01.01.2026;'), preise);
});

/** Legt über den Reiter „Schäden“ einen Fall mit Auftraggeber an (die Firma entsteht dabei automatisch). */
async function legeFallAnUeberSchaeden(s) {
  await s.locator('#navi [data-bereich=faelle]:visible').click();
  await legeFallAn(s, { kundeName: 'Anna Becker', auftraggeber: 'Seats and Sofas Krefeld', auftraggeberKontakt: 'Tim Berg' });
  await s.locator('#navi [data-bereich=firmen]:visible').click();
}

test('CRM: Chancen, Schlagworte, Kennzahlen, Folgetermin, Verschieben und Zusammenführen', async () => {
  const cloud = await starteCloud();
  const a = await geraet(cloud.url, { bereich: 'firmen' });
  await melde(a, PIN, true); await aktuell(a);
  const firma = async (name, schlagworte) => {
    await a.locator('[data-neue-firma]').click();
    await a.locator('#crm-form [name=name]').fill(name);
    await a.locator('#crm-form [name=schlagworte]').fill(schlagworte);
    await a.locator('#crm-form button.voll').click();
    await a.locator('#stammdaten', { hasText: name }).waitFor();
  };
  await firma('Seats and Sofas', 'Möbel, Montage');
  await a.locator('[data-crm-neu=chancen]').click();
  await a.locator('#crm-form [name=titel]').fill('Neue Filiale Düsseldorf');
  await a.locator('#crm-form [name=phase]').selectOption('ANGEBOT');
  await a.locator('#crm-form [name=betrag]').fill('25.000');
  await a.locator('#crm-form button.voll').click();
  await a.locator('#kennzahlen', { hasText: 'Offene Chancen' }).waitFor();
  assert.match(await a.locator('#kennzahlen').innerText(), /1 · 25\.000,00/);

  // Kontakt mit Folgetermin legt eine Wiedervorlage an.
  await a.locator('[data-crm-neu=aktivitaeten]').click();
  await a.locator('#crm-form [name=text]').fill('Angebot besprochen');
  await a.locator('#crm-form [name=folgeAm]').fill('2020-01-01');
  await a.locator('#crm-form button.voll').click();
  await a.locator('#detail').getByText('Nachfassen: Angebot besprochen').first().waitFor();

  // Termine: überfällige Wiedervorlage auf morgen schieben.
  await a.locator('#navi [data-bereich=termine]:visible').click();
  await a.locator('#termine').getByText('Überfällig (1)').waitFor();
  await a.locator('#termine [data-tage="1"]').click();
  await a.locator('#termine').getByText('Demnächst (1)').waitFor();

  // Schlagwort-Filter und „Lange kein Kontakt“
  await a.locator('#navi [data-bereich=firmen]:visible').click();
  await firma('Media Markt', 'Elektro');
  await a.locator('[data-schlagwort="Elektro"]').click();
  assert.deepEqual(await a.locator('#firmen .titel').allInnerTexts(), ['Media Markt']);
  await a.locator('[data-schlagwort="Elektro"]').click();
  await a.locator('[data-firmen-status=PAUSE]').click();
  assert.deepEqual(await a.locator('#firmen .titel').allInnerTexts(), ['Media Markt'], 'mit Seats wurde gerade gesprochen');

  // Media Markt in Seats and Sofas überführen.
  await a.locator('[data-firmen-status=""]').click();
  await a.locator('#firmen').getByText('Media Markt').click();
  await a.locator('[data-firma-bearbeiten]').click();
  await a.locator('#crm-form summary').click();
  const seatsId = await a.evaluate(() => auftraggeberListe().find(e => e.name === 'Seats and Sofas').id);
  await a.locator('#zusammen-ziel').selectOption(seatsId);
  await a.locator('#zusammen-los').click();
  await a.locator('#ja').click();
  await a.locator('#stammdaten', { hasText: 'Seats and Sofas' }).waitFor();
  assert.deepEqual(await a.evaluate(() => auftraggeberListe().map(e => [e.name, e.schlagworte])), [['Seats and Sofas', 'Möbel, Montage, Elektro']]);
  await aktuell(a);
  const daten = cloudDaten(cloud);
  assert.deepEqual(Object.values(daten.chancen).map(c => [c.titel, c.phase, c.wertCent]), [['Neue Filiale Düsseldorf', 'ANGEBOT', 2500000]]);
  assert.equal(Object.values(daten.auftraggeber).filter(f => !f.geloescht).length, 1);
  await cloud.schliessen();
});

test('Finanzen: Rechnung aus der Preisliste, Summen, Zahlung, Überfällig, Angebot → Rechnung, CSV und Übersicht', async () => {
  const cloud = await starteCloud();
  const a = await geraet(cloud.url, { bereich: 'firmen' });
  await melde(a, PIN, true); await aktuell(a);
  await a.locator('[data-neue-firma]').click();
  await a.locator('#crm-form [name=name]').fill('Seats and Sofas');
  await a.locator('#crm-form [name=kundennummer]').fill('K-1001');
  await a.locator('#crm-form [name=zahlungszielTage]').fill('30');
  await a.locator('#crm-form button.voll').click();
  await a.locator('#stammdaten', { hasText: 'Seats and Sofas' }).waitFor();
  await a.locator('[data-crm-neu=preise]').click();
  await a.locator('#crm-form [name=bezeichnung]').fill('Tourpauschale');
  await a.locator('#crm-form [name=betrag]').fill('189,50');
  await a.locator('#crm-form [name=einheit]').fill('pro Tour');
  await a.locator('#crm-form button.voll').click();

  // Rechnung aus der Firma, Position aus der Preisliste, Menge 2
  await a.locator('[data-crm-neu=belege]').click();
  await a.locator('#beleg-form').waitFor();
  const jahr = new Date().getFullYear();
  assert.match(await a.locator('#detail h2').innerText(), new RegExp(`Rechnung R${jahr}-0001`));
  const preisId = await a.evaluate(() => alle('preise')[0].id);
  await a.locator('#aus-preisliste').selectOption(preisId);
  await a.locator('[data-pos="0"] [name=menge]').waitFor();
  await a.locator('[data-pos="0"] [name=menge]').fill('2');
  await a.locator('#beleg-speichern').click();
  await a.locator('#beleg-form').getByText('451,01').first().waitFor();
  const b = await a.evaluate(() => { const b = alle('belege')[0]; return { s: belegSummen(b), faellig: b.faelligAm, datum: b.datum, html: belegHtml(b) }; });
  assert.deepEqual(b.s, { netto: 37900, mwst: 7201, brutto: 45101 });
  assert.equal(b.faellig, await a.evaluate(d => plusTage(d, 30), b.datum), 'Zahlungsziel der Firma');
  assert.ok(b.html.includes('K-1001') && b.html.includes('Tourpauschale') && b.html.includes('451,01'));

  // Versendet, dann Teilzahlung und Rest
  await a.locator('[data-beleg-akt=festschreiben]').click();
  await a.locator('[data-beleg-akt=zahlung]').click();
  await a.locator('#zahlung-form [name=betrag]').fill('200');
  await a.locator('#zahlung-form button.voll').click();
  await a.locator('#detail .marke', { hasText: 'Teilbezahlt' }).waitFor();
  await a.locator('[data-beleg-akt=zahlung]').click();
  await a.locator('#zahlung-form button.voll').click();
  await a.locator('#detail .marke', { hasText: 'Bezahlt' }).waitFor();
  assert.equal(await a.evaluate(() => alle('aktivitaeten').filter(x => x.text.includes('Zahlung')).length), 2, 'Zahlungen in der Kontakthistorie');

  // Eine überfällige Rechnung und ein Angebot, das zur Rechnung wird
  await a.evaluate(() => {
    const f = alle('auftraggeber')[0].id;
    const id = BelegAktionen.neu('RECHNUNG', f, { positionen: JSON.stringify([{ bez: 'Montage', menge: 1, einzelCent: 10000 }]), status: 'OFFEN', datum: '2020-01-01', faelligAm: '2020-01-15' });
    BelegAktionen.neu('ANGEBOT', f, { positionen: JSON.stringify([{ bez: 'Etage', menge: 3, einzelCent: 1250 }]), status: 'OFFEN' });
    return id;
  });
  await a.locator('#navi [data-bereich=finanzen]:visible').click();
  await a.locator('[data-beleg-filter=UEBERFAELLIG]').first().click();
  await a.locator('#belege').getByText(`R${jahr}-0002`).waitFor();
  await a.locator('[data-beleg-filter=ANGEBOTE]').last().click();
  await a.locator('#belege').getByText(`A${jahr}-0001`).click();
  await a.locator('[data-beleg-akt=zurechnung]').click();
  await a.locator('#detail h2', { hasText: `Rechnung R${jahr}-0003` }).waitFor();
  assert.equal(await a.evaluate(() => alle('belege').find(b => b.art === 'ANGEBOT').status), 'ANGENOMMEN');

  // Startnummer: an einen bisherigen Nummernkreis anschließen
  const nummern = await a.evaluate(() => {
    S.einstellungen.rechnung = { ...rechnungsDaten(), startRechnung: 1001 };
    const r = naechsteBelegNummer('RECHNUNG'); S.einstellungen.rechnung.startRechnung = 2; return [r, naechsteBelegNummer('RECHNUNG'), naechsteBelegNummer('ANGEBOT')];
  });
  assert.deepEqual(nummern, [`R${jahr}-1001`, `R${jahr}-0004`, `A${jahr}-0002`], 'Startnummer ist Untergrenze, belegte Nummern zählen weiter');

  // Übersicht
  await a.locator('#navi [data-bereich=uebersicht]:visible').click();
  await a.locator('#liste').getByText('überfällig (1)').waitFor();
  const csv = await a.evaluate(() => belegeCsv());
  assert.ok(csv.startsWith('﻿Nummer;Art;Firma') && csv.includes(`R${jahr}-0001;Rechnung;Seats and Sofas`) && csv.includes(';Bezahlt;379,00;72,01;451,01;451,01;'), csv);
  const ics = await a.evaluate(() => termineIcs());
  assert.ok(ics.startsWith('BEGIN:VCALENDAR') && ics.includes('END:VCALENDAR'));

  await aktuell(a);
  assert.equal(Object.values(cloudDaten(cloud).belege).length, 4, 'Belege landen in der Cloud');

  // Nach Tests aufräumen: alles löschen, die Nummern beginnen wieder vorn
  await a.locator('#einstellungen-knopf').click();
  await a.locator('#belege-leeren').click();
  await a.locator('#ja').click();
  await a.getByText('4 Angebote und Rechnungen gelöscht').waitFor();
  assert.equal(await a.evaluate(() => alle('belege').length + alle('aktivitaeten').filter(x => x.text.startsWith('Zahlung')).length), 0);
  assert.equal(await a.evaluate(() => { S.einstellungen.rechnung = { ...rechnungsDaten(), startRechnung: 1 }; return naechsteBelegNummer('RECHNUNG'); }), `R${jahr}-0001`);
  await aktuell(a);
  assert.ok(Object.values(cloudDaten(cloud).belege).every(b => b.geloescht), 'auch in der Cloud gelöscht');
  await cloud.schliessen();
});

test('Fuhrpark: Fahrzeug mit Fahrzeugschein und Fristen, Tanken, Dokument, Termine, Abgleich und Drucken ohne Popup', async () => {
  const cloud = await starteCloud();
  const a = await geraet(cloud.url, { bereich: 'fuhrpark' });
  await melde(a, PIN, true); await aktuell(a);
  await a.locator('[data-neues-fahrzeug]').click();
  await a.locator('#fahrzeug-form [name=kennzeichen]').fill('kr-at 123');
  await a.locator('#fahrzeug-form [name=bezeichnung]').fill('Sprinter 3');
  await a.locator('#fahrzeug-form [name=fahrer]').fill('M. Yilmaz');
  await a.locator('#fahrzeug-form [name=kmStand]').fill('120.000');
  await a.locator('#fahrzeug-form [name=marke]').fill('Mercedes-Benz');
  await a.locator('#fahrzeug-form [name=fin]').fill('WDB9066331S123456');
  await a.locator('#fahrzeug-form [name=leistungKw]').fill('120');
  const bald = await a.evaluate(() => plusTage(heute(), 10)), vorbei = await a.evaluate(() => plusTage(heute(), -3));
  await a.locator('#fahrzeug-form [name=huBis]').fill(bald);
  await a.locator('#fahrzeug-form [name=uvvBis]').fill(vorbei);
  await a.locator('#fahrzeug-form button.voll').click();
  await a.locator('#detail h2', { hasText: 'KR-AT 123' }).waitFor();
  await a.locator('#fristen').getByText('seit').waitFor(); // UVV überfällig
  await a.locator('#detail').getByText('120 kW (163 PS)').waitFor();

  // Zweimal tanken → Verbrauch; der höhere km-Stand landet im Fahrzeug
  for (const [km, liter, betrag] of [['120000', '60', '100'], ['120800', '80', '140,50']]) {
    await a.locator('[data-fz=tanken]').click();
    await a.locator('#fz-eintrag-form [name=kmStand]').fill(km);
    await a.locator('#fz-eintrag-form [name=menge]').fill(liter);
    await a.locator('#fz-eintrag-form [name=betrag]').fill(betrag);
    await a.locator('#fz-eintrag-form button.voll').click();
  }
  await a.locator('#detail').getByText('10 l / 100 km').waitFor();
  await a.locator('#detail').getByText('120.800 km').first().waitFor();
  assert.equal(await a.evaluate(() => alle('fahrzeuge')[0].kmStand), 120800);

  // Fahrzeugschein als Datei
  await a.locator('#fz-dok-art').selectOption('Fahrzeugschein (Teil I)');
  await a.locator('#fz-datei').setInputFiles({ name: 'schein.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4 test') });
  await a.locator('#detail').getByText('Fahrzeugschein (Teil I) – schein.pdf').waitFor();

  // Schadensfall mit dem Kennzeichen erscheint beim Fahrzeug
  await a.evaluate(() => { fallMerken({ id: uuid(), fallnummer: '2026-900', status: 'NEU', kundeName: 'Kundin Test', fahrzeug: 'KR AT 123', erstelltAm: jetztIso(), geaendertAm: jetztIso() }); nachAenderung(); });
  await a.locator('#detail').getByText('Kundin Test').waitFor();

  // Fristen unter Termine (HU in 10 Tagen, UVV überfällig)
  await a.locator('#navi [data-bereich=termine]:visible').click();
  await a.locator('#termine').getByText('UVV-Prüfung · KR-AT 123').waitFor();
  await a.locator('#termine').getByText('HU (TÜV) · KR-AT 123').waitFor();
  await aktuell(a);
  const d = cloudDaten(cloud);
  assert.equal(Object.values(d.fahrzeuge)[0].kennzeichen, 'KR-AT 123');
  assert.equal(Object.values(d.fahrzeugeintraege).length, 2);

  // Zweites Gerät bekommt Fahrzeug, Akte und Dokument
  const b = await geraet(cloud.url, { bereich: 'fuhrpark' });
  await melde(b); await aktuell(b);
  await b.locator('[data-fahrzeug]', { hasText: 'KR-AT 123' }).click();
  await b.locator('#fahrzeugakte li').nth(1).waitFor();
  await b.locator('#detail').getByText('schein.pdf').waitFor();

  // Drucken öffnet kein neues Fenster mehr (wird in der Windows-App und von Popup-Blockern gesperrt)
  const druck = await a.evaluate(() => { let geoeffnet = false; window.open = () => { geoeffnet = true; return null; }; druckeHtml('<p>Test</p>'); return { geoeffnet, rahmen: !!document.querySelector('#druckrahmen') }; });
  assert.deepEqual(druck, { geoeffnet: false, rahmen: true });

  // Import aus eingelesenen Fahrzeugscheinen: neues Fahrzeug, vorhandenes nur ergänzen, Dokument nicht doppelt
  const datei = { name: 'fuhrpark.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify({
    fahrzeuge: [{ kennzeichen: 'RS-FK 4204', art: 'TRANSPORTER', marke: 'Mercedes-Benz', huBis: '2028-01-31', zgg: 3500, kfzSteuerCent: 21000, kfzSteuerNr: 'K126.8195.7793', versicherung: 'KRAVAG', versicherungCent: 352944, kfzSteuerAb: '2024-10-29', steuerBis: '2024-12-09' }, { kennzeichen: 'KR AT 123', marke: 'Andere Marke', farbe: 'Weiß' }],
    dokumente: [{ kennzeichen: 'RS-FK 4204', art: 'Fahrzeugschein (Teil I)', name: 'Schein.pdf', mimeType: 'application/pdf', base64: Buffer.from('%PDF-1.4').toString('base64') }] })) };
  await a.locator('#navi [data-bereich=fuhrpark]:visible').click();
  await a.locator('#fuhrpark-import').setInputFiles(datei);
  await a.locator('#toast', { hasText: '1 Fahrzeuge neu, 1 ergänzt, 1 Dokumente' }).waitFor();
  await a.locator('#fuhrpark-import').setInputFiles(datei); // ein zweites Mal: nichts doppelt
  await a.locator('#toast', { hasText: '0 Fahrzeuge neu, 0 ergänzt, 0 Dokumente' }).waitFor();
  const imp = await a.evaluate(() => alle('fahrzeuge').map(z => [z.kennzeichen, z.marke, z.farbe || '', anlagenVon(z.id).length]).sort());
  assert.deepEqual(imp, [['KR-AT 123', 'Mercedes-Benz', 'Weiß', 1], ['RS-FK 4204', 'Mercedes-Benz', '', 1]]);
  // Kfz-Steuer: Betrag und Nummer übernommen, Abbuchung wiederholt sich jedes Jahr am Jahrestag (nie überfällig)
  const steuer = await a.evaluate(() => { const z = alle('fahrzeuge').find(x => x.kennzeichen === 'RS-FK 4204'); const f = fristenVon(z).find(x => x.feld === 'steuerBis');
    const j = Number(heute().slice(0, 4)), erwartet = heute() <= j + '-10-29' ? j + '-10-29' : (j + 1) + '-10-29';
    return { vers: z.versicherungCent, cent: z.kfzSteuerCent, nr: z.kfzSteuerNr, datum: f.datum, erwartet, tage: f.tage }; });
  assert.equal(steuer.cent, 21000); assert.equal(steuer.vers, 352944); assert.equal(steuer.nr, 'K126.8195.7793');
  assert.equal(steuer.datum, steuer.erwartet); assert.ok(steuer.tage >= 0);
  await cloud.schliessen();
});

test('Android-App: Brücke (android.js) – Postfach, Speichern, Drucken, PDF und Zurück-Taste', async () => {
  const fs = require('node:fs');
  const bruecke = fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/android.js'), 'utf8');
  const kontext = await browser.newContext({ serviceWorkers: 'block', viewport: { width: 412, height: 900 } });
  await kontext.route('https://script.google.com/**', route => route.abort());
  // Nachbildung des Kotlin-Teils: beantwortet die Aufrufe wie MainActivity/Bruecke.kt
  await kontext.addInitScript(() => {
    localStorage.setItem('schadensfaelle-v1', JSON.stringify({ nurLokal: true }));
    localStorage.setItem('crm-bereich', 'firmen');
    window.__aufrufe = [];
    const konten = {};
    window.AndroidBruecke = {
      aufruf(id, name, args) {
        const a = JSON.parse(args); window.__aufrufe.push([name, a]);
        const ok = {
          'mail:anbieter': { IONOS: { label: 'IONOS (1&1)', imapHost: 'imap.ionos.de', imapPort: 993, imapSicherheit: 'SSL', smtpHost: 'smtp.ionos.de', smtpPort: 465, smtpSicherheit: 'SSL' } },
          'mail:konto': konten[a[0]] || null,
          'mail:hole': { mails: [], letzteUid: 0, uidValidity: 1 },
          'datei:speichern': 'Download/Ayse CRM/' + a[0],
          'pdf:erzeugen': btoa('%PDF-1.4'),
        }[name];
        if (name === 'mail:einrichten') konten[a[2]] = a[0];
        setTimeout(() => window.__androidAntwort(id, { ok: ok === undefined ? true : ok }), 10);
      },
    };
  });
  await kontext.addInitScript(bruecke);
  const s = await kontext.newPage();
  s.on('pageerror', fehler => { throw fehler; });
  await s.goto(seite.url);
  await s.waitForFunction(() => window.__appBereit);
  assert.equal(await s.evaluate(() => window.desktop.plattform), 'android');

  // Postfach einrichten (wie in der Windows-App) → Reiter „Postfach“ erscheint
  await s.evaluate(() => mailKontoDialog('SCHADEN'));
  await s.locator('#konto-form').getByText('Das Passwort wird verschlüsselt und nur auf diesem Gerät gespeichert.').waitFor();
  await s.locator('#konto-form [name=adresse]').fill('schaden@ayse.example');
  await s.locator('#konto-form [name=passwort]').fill('geheim');
  await s.locator('#konto-form button.voll').click();
  await s.locator('#navi .nav-gross [data-bereich=postfach]').waitFor({ state: 'attached' });
  assert.ok(await s.evaluate(() => window.__aufrufe.some(([n, a]) => n === 'mail:einrichten' && a[1] === 'geheim' && a[2] === 'SCHADEN')));

  // CSV-Download landet über die Brücke im Download-Ordner
  await s.locator('[data-nav-mehr]').click();
  await s.locator('.nav-gross [data-bereich=fuhrpark]').click();
  await s.evaluate(() => FuhrparkAktionen.speichern({ kennzeichen: 'RS-FK 4204' }));
  await s.locator('[data-fuhrpark-csv]').click();
  await s.locator('#toast', { hasText: 'Gespeichert: Download/Ayse CRM/Fahrze' }).waitFor();
  const csv = await s.evaluate(() => window.__aufrufe.find(([n]) => n === 'datei:speichern')[1]);
  assert.match(csv[0], /^Fahrzeuge_\d{4}-\d{2}-\d{2}\.csv$/);
  assert.ok(Buffer.from(csv[1], 'base64').toString('utf8').includes('RS-FK 4204'));

  // Drucken und PDF gehen an Android
  await s.evaluate(() => druckeHtml('<p>Beleg</p>'));
  await s.waitForFunction(() => window.__aufrufe.some(([n, a]) => n === 'drucken' && a[0] === '<p>Beleg</p>'));
  assert.equal(await s.locator('#druckrahmen').count(), 0);
  assert.equal(await s.evaluate(() => window.desktop.pdf('<p>x</p>')), Buffer.from('%PDF-1.4').toString('base64'));

  // Zurück-Taste: Dialog zu → eine Ansicht zurück → am Anfang: App verlassen (false)
  await s.evaluate(() => { location.hash = '#/fahrzeug/' + alle('fahrzeuge')[0].id; });
  await s.locator('#detail h2', { hasText: 'RS-FK 4204' }).waitFor();
  await s.evaluate(() => dialog('<p>Test</p>'));
  assert.equal(await s.evaluate(() => window.__androidZurueck()), true);
  assert.equal(await s.evaluate(() => $('#dialog').open), false);
  assert.equal(await s.evaluate(() => window.__androidZurueck()), true);
  await s.waitForFunction(() => !location.hash.startsWith('#/fahrzeug'));
  await s.evaluate(() => { history.replaceState(null, '', location.pathname); });
  assert.equal(await s.evaluate(() => window.__androidZurueck()), false);
  await kontext.close();
});

test('Tourenplanung im CRM: Tagesplan, Hinweise, Auffüllen, Personal, Rechnung, Schaden aus Tour, Abgleich', async () => {
  const cloud = await starteCloud();
  const s = await geraet(cloud.url, { bereich: 'touren' });
  await melde(s, PIN, true);
  await aktuell(s);
  const tag = await s.evaluate(() => heute()), gestern = await s.evaluate(() => plusTage(heute(), -1));
  // Ausgangslage: Fuhrpark, Personal, Einsatzarten, Firma, Abwesenheit und zwei Touren von gestern
  await s.evaluate(([tag, gestern]) => {
    const j = jetztIso(), ma = (name, x) => merke('mitarbeiter', { id: uuid(), firmaId: '', name, telefon: '', monteur: false, nichtBei: '', aktiv: true, notiz: '', ...x, geaendertAm: j }).id;
    merke('fahrzeuge', { id: uuid(), firmaId: '', kennzeichen: 'RS-AT 100', art: 'TRANSPORTER', status: 'AKTIV', zgg: 3500, geaendertAm: j });
    merke('fahrzeuge', { id: uuid(), firmaId: '', kennzeichen: 'RS-AT 200', art: 'LKW', status: 'AKTIV', zgg: 7490, geaendertAm: j });
    const ali = ma('Ali Demir', { rolle: 'FAHRER', telefon: '0171 111' }), ben = ma('Ben Ott', { rolle: 'BEIFAHRER', monteur: true }), cem = ma('Cem Kaya', { rolle: 'BEIDES' });
    ma('Dana Roth', { rolle: 'FAHRER', aktiv: false }); const neuMann = ma('Neu Mann', { rolle: 'BEIDES' });
    merke('einsatzarten', { id: uuid(), firmaId: '', name: 'Montage', beifahrer: 1, monteurNoetig: true, notiz: '', geaendertAm: j });
    merke('einsatzarten', { id: uuid(), firmaId: '', name: 'Lieferung', beifahrer: 1, monteurNoetig: false, notiz: '', geaendertAm: j });
    const f = merke('auftraggeber', { id: uuid(), name: 'Möbel Krause', status: 'AKTIV', geaendertAm: j });
    merke('adressen', { id: uuid(), firmaId: f.id, art: 'HAUPTSITZ', strasse: 'Hauptstr. 1', plz: '42853', ort: 'Remscheid', notiz: '', geaendertAm: j });
    merke('abwesenheiten', { id: uuid(), firmaId: '', mitarbeiterId: cem, von: tag, bis: tag, grund: 'Krank', notiz: '', geaendertAm: j });
    const tour = (i, x) => merke('touren', { id: uuid(), firmaId: '', datum: gestern, reihenfolge: i, auftraggeberId: f.id, auftraggeber: f.name, uhrzeit: '', belegId: '', notiz: '', ...x, geaendertAm: j });
    tour(1, { kennzeichen: 'RS-AT 100', tourNr: '7', name: 'Küche Lennep', uhrzeit: '07:00', einsatzart: 'Montage', fahrerId: ali, beifahrerIds: ben });
    tour(2, { kennzeichen: 'RS-AT 200', tourNr: '8', name: 'Sofa', einsatzart: 'Lieferung', fahrerId: cem, beifahrerIds: neuMann });
    nachAenderung();
  }, [tag, gestern]);

  // Heute: von gestern übernehmen (mit Team) – Cem ist krank und bleibt draußen
  await s.locator('#navi [data-bereich=touren]:visible').click();
  await s.locator(`[data-tp-kopie="${gestern}"]`).click();
  await s.locator('#tourplan .tour').nth(1).waitFor();
  let heuteT = await s.evaluate(() => tourenAm(heute()).map(t => [t.kennzeichen, maName(t.fahrerId), beifahrerVon(t).map(maName).join()]));
  assert.deepEqual(heuteT, [['RS-AT 100', 'Ali Demir', 'Ben Ott'], ['RS-AT 200', '', 'Neu Mann']]);
  // Hinweise aus Personal und Fuhrpark
  await s.evaluate(() => { const z = fahrzeugZumKz('RS-AT 200'); FuhrparkAktionen.speichern({ ...z, status: 'WERKSTATT' }); });
  await s.locator('#tourplan .tour').nth(1).getByText('Fahrzeug in der Werkstatt').waitFor();
  await s.locator('#tourplan .tour').nth(1).getByText('Fahrer fehlt').waitFor();
  // Fahrer eintippen: Cem ist krank → Hinweis
  const fahrer2 = s.locator('#tourplan .tour').nth(1).locator('[data-feld=fahrer]');
  await fahrer2.fill('Cem Kaya'); await fahrer2.press('Tab');
  await s.locator('#tourplan .tour').nth(1).getByText('Cem: Krank').waitFor();
  // Rückgängig, dann Teams auffüllen: Dana ist inaktiv, Cem krank, Ali/Ben/Neu verplant → niemand frei
  await s.locator('[data-tp=rueckgaengig]').click();
  await s.waitForFunction(() => !tourenAm(heute())[1].fahrerId);
  await s.evaluate(() => PersonalAktionen.speichern({ name: 'Eva Licht', rolle: 'FAHRER', fuehrerschein: 'B' }));
  await s.evaluate(() => PersonalAktionen.speichern({ name: 'Fritz Lang', rolle: 'FAHRER', fuehrerschein: 'C1, CE' }));
  await s.locator('[data-tp=auffuellen]').click();
  await s.locator('#auffuellen-form button.voll').click();
  await s.locator('#toast', { hasText: 'eingeteilt' }).waitFor();
  heuteT = await s.evaluate(() => tourenAm(heute()).map(t => maName(t.fahrerId)));
  assert.deepEqual(heuteT, ['Ali Demir', 'Fritz Lang'], 'für 7,49 t nur mit passendem Führerschein');
  // Neue Tour per Eingabe: neuer Auftraggeber wird Firma, neuer Name wird Mitarbeiter
  await s.locator('[data-tp=neu]').first().click();
  const neu = s.locator('#tourplan .tour').nth(2);
  await neu.locator('[data-feld=kennzeichen]').fill('RS-XY 1'); await neu.locator('[data-feld=kennzeichen]').press('Tab');
  await s.locator('#tourplan .tour').nth(2).locator('[data-feld=auftraggeber]').fill('Küchen Nord'); await s.locator('#tourplan .tour').nth(2).locator('[data-feld=auftraggeber]').press('Tab');
  await s.locator('#toast', { hasText: 'Küchen Nord' }).waitFor();
  assert.ok(await s.evaluate(() => !!firmaZumNamen('Küchen Nord') && tourenAm(heute())[2].auftraggeberId === firmaZumNamen('Küchen Nord').id));
  await s.locator('#tourplan .tour').nth(2).getByText('nicht im Fuhrpark').waitFor();

  // Personal: Mitarbeiter mit Abwesenheit und Auswertung
  await s.evaluate(() => { bereichWechseln('personal'); gehe('#/mitarbeiter/' + maZumNamen('Ali Demir').id); });
  await s.locator('#detail h2', { hasText: 'Ali Demir' }).waitFor();
  assert.ok((await s.locator('#detail').textContent()).includes('Möbel Krause (2)'));
  await s.locator('[data-ma=abwesenheit]').first().click();
  await s.locator('#abwesenheit-form [name=von]').fill(tag); await s.locator('#abwesenheit-form [name=bis]').fill(tag);
  await s.locator('#abw-hinweis', { hasText: 'Tour(en) eingeplant' }).waitFor();
  await s.locator('#abwesenheit-form button.voll').click();
  assert.ok(await s.evaluate(() => tourHinweise(tourenAm(heute())[0]).some(([, x]) => x === 'Ali: Urlaub')));

  // Schaden aus der Tour melden → Fall mit Tour, Team und Fahrzeug verknüpft
  await s.evaluate(() => schadenAusTour(tourenAm(heute())[0]));
  await s.locator('#fall-form').waitFor();
  assert.equal(await s.locator('#fall-form [name=fahrer]').inputValue(), 'Ali Demir');
  await s.locator('#fall-form [name=kundeName]').fill('Anna Becker');
  await s.locator('#fall-form button.voll').click();
  await s.waitForFunction(() => location.hash.startsWith('#/fall/'));
  const fall = await s.evaluate(() => { const f = S.faelle[location.hash.split('/')[2]]; return { tourId: f.tourId === tourenAm(heute())[0].id, fahrer: maName(f.fahrerId), fz: S.fahrzeuge[f.fahrzeugId]?.kennzeichen, ma: schaedenVon(maZumNamen('Ben Ott')).length }; });
  assert.deepEqual(fall, { tourId: true, fahrer: 'Ali Demir', fz: 'RS-AT 100', ma: 1 });

  // Rechnung aus Touren: Preise der Firma, Touren werden als abgerechnet markiert, kein zweites Mal
  await s.evaluate(() => { const f = firmaZumNamen('Möbel Krause'); Aktionen.crmSpeichern('preise', { firmaId: f.id, bezeichnung: 'Montage', betragCent: 25000, einheit: 'pro Einsatz' }); Aktionen.crmSpeichern('preise', { firmaId: f.id, bezeichnung: 'Lieferung', betragCent: 9000 }); });
  await s.evaluate(([v, b]) => rechnungAusTouren(firmaZumNamen('Möbel Krause').id, v, b), [gestern, tag]);
  await s.locator('#tpr-form button.voll').click();
  await s.locator('#tpr-summe', { hasText: '680,00' }).waitFor();
  await s.locator('#tpr-vorschau button.voll').click();
  await s.waitForFunction(() => location.hash.startsWith('#/beleg/'));
  assert.equal(await s.evaluate(() => alle('touren').filter(t => t.belegId).length), 4);
  await s.evaluate(([v, b]) => rechnungAusTouren(firmaZumNamen('Möbel Krause').id, v, b), [gestern, tag]);
  await s.locator('#tpr-form button.voll').click();
  await s.locator('#tpr-vorschau', { hasText: 'Keine gefahrenen, offenen Touren' }).waitFor();
  await s.evaluate(() => $('#dialog').close());

  // Globale Suche findet Mitarbeiter, Tour und Firma
  await s.locator('#globale-suche').fill('lennep');
  await s.locator('#suchtreffer .treffer').first().waitFor();
  assert.ok((await s.locator('#suchtreffer').textContent()).includes('Touren'));
  await s.locator('#suchtreffer .treffer', { hasText: 'RS-AT 100' }).first().click();
  await s.waitForFunction(() => ANSICHT.bereich === 'touren' && !location.hash.slice(2));

  // Auswertung und Abgleich in die Cloud
  await s.evaluate(() => { ANSICHT.statistikReiter = 'vergleich'; bereichWechseln('auswertung'); });
  await s.locator('#auswertung').getByText('Ali Demir').waitFor();
  // Statistik: Schäden je 100 Touren je Mitarbeiter, Gewinn & Verlust, Top & Flop
  await s.evaluate(() => { ANSICHT.statistikReiter = 'fertig'; ANSICHT.auswertungZeitraum = 'ALLES'; zeichneListe(); });
  await s.locator('[data-fertige="Schäden je 100 Touren je Mitarbeiter"]').click();
  const zeile = s.locator('#statistik-tabelle tr', { hasText: 'Ali Demir' });
  assert.match(await zeile.textContent(), /Ali Demir\s*2\s*1\s*50/);
  await s.locator('[data-statistik-reiter=guv]').click();
  await s.locator('#guv').waitFor();
  await s.locator('[data-statistik-reiter=bestenliste]').click();
  await s.locator('#liste', { hasText: 'Top-Mitarbeiter' }).waitFor();
  const r = await s.evaluate(() => { const t = tourenAm(plusTage(heute(), -1))[0]; return tourRechnung(t); });
  assert.equal(r.erloes, 25000, 'Erlös aus der Rechnung (Montage 250 €)');
  assert.equal(r.schaeden, 0);
  await aktuell(s);
  const d = cloudDaten(cloud);
  assert.equal(Object.values(d.touren).filter(t => !t.geloescht).length, 5);
  assert.equal(Object.values(d.mitarbeiter).length, 7);
  assert.equal(Object.values(d.einsatzarten).length, 2);
  assert.ok(Object.values(d.faelle).some(f => f.tourId && f.fahrerId));
  // Zweites Gerät bekommt alles
  const b = await geraet(cloud.url, { bereich: 'touren' });
  await melde(b);
  await aktuell(b);
  assert.equal(await b.evaluate(() => tourenAm(heute()).length), 3);
  await b.context().close(); await s.context().close(); await cloud.schliessen();
});

test('Personal-Import (Lohnabrechnungen) und Akquise: Anruf-Ergebnis legt Wiedervorlage und Chance an', async () => {
  const s = await geraet(null, { bereich: 'personal' });
  const datei = JSON.stringify({ mitarbeiter: [{ name: 'Demir, Ali', personalnummer: '17', eintritt: '2024-03-01', urlaubstage: 28, anstellung: 'VOLLZEIT' }, { name: 'Ben Ott', personalnummer: '18' }],
    lohn: [{ personalnummer: '17', monat: '2026-10', bruttoCent: 310000, nettoCent: 215000, kostenCent: 380000, stunden: 168 }, { name: 'Ben Ott', monat: '2026-10', bruttoCent: 250000 }] });
  await s.evaluate(() => PersonalAktionen.speichern({ name: 'Ali Demir', rolle: 'FAHRER' }));
  await s.locator('#personal-import').setInputFiles({ name: 'personal.json', mimeType: 'application/json', buffer: Buffer.from(datei) });
  await s.locator('#imp-summe', { hasText: '1 zugeordnet · 1 neu' }).waitFor();
  await s.locator('#imp-form button.voll').click();
  await s.locator('#toast', { hasText: 'Import: 1 neu, 1 ergänzt, 2 Lohnabrechnungen' }).waitFor();
  await s.locator('#personal-import').setInputFiles({ name: 'personal.json', mimeType: 'application/json', buffer: Buffer.from(datei) });
  await s.locator('#imp-form button.voll').click();
  await s.locator('#toast', { hasText: 'Import: 0 neu, 0 ergänzt, 2 Lohnabrechnungen' }).waitFor();
  // Anders geschriebener Name: Vorschlag zum Prüfen, Alias wird gespeichert, keine Dublette
  await s.evaluate(() => PersonalAktionen.speichern({ name: 'Mike Engelhardt', rolle: 'FAHRER' }));
  await s.locator('#personal-import').setInputFiles({ name: 'p2.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify({ mitarbeiter: [{ name: 'Mike Davis Engelhardt', personalnummer: '4' }, { name: 'Zoe Weg', personalnummer: '99' }],
    lohn: [{ personalnummer: '4', monat: '2026-10', bruttoCent: 190000 }, { personalnummer: '99', monat: '2026-10', bruttoCent: 1 }] })) });
  await s.locator('.imp-zeile.pruefen', { hasText: 'Mike Davis Engelhardt' }).waitFor();
  await s.locator('#imp-form [name=z1]').selectOption('WEG');
  await s.locator('#imp-form button.voll').click();
  await s.locator('#toast', { hasText: 'Import: 0 neu, 1 ergänzt, 1 Lohnabrechnungen' }).waitFor();
  assert.deepEqual(await s.evaluate(() => { const m = maZumNamen('Mike Davis Engelhardt'); return [m.name, m.personalnummer, alle('mitarbeiter').length, !!maZumNamen('Zoe Weg')]; }), ['Mike Engelhardt', '4', 3, false]);
  const r = await s.evaluate(() => { const m = maZumNamen('Ali Demir'); return [m.personalnummer, m.urlaubstage, alle('personaleintraege').length, maAuswertung(m, '2026-10-01', '2026-10-31').kostenCent]; });
  assert.deepEqual(r, ['17', 28, 3, 380000]);

  // Akquise: Interessent erfassen, Anruf mit Ergebnis „Angebot gewünscht“
  await s.evaluate(() => interessentDialog());
  await s.locator('#lead-form [name=name]').fill('Küchen Nord');
  await s.locator('#lead-form [name=telefon]').fill('0211 123');
  await s.locator('#lead-form [name=kontakt]').fill('Frau Nord');
  await s.locator('#lead-form button.voll').click();
  await s.locator('#toast', { hasText: 'Akquise-Ablauf mit 4 Wiedervorlagen' }).waitFor();
  await s.locator('[data-akquise=anruf]').click();
  await s.locator('#anruf-form [name=ergebnis]').selectOption('ANGEBOT');
  await s.locator('#anruf-form [name=text]').fill('Will Preise für Montage');
  await s.locator('#anruf-form button.voll').click();
  await s.locator('#toast', { hasText: 'Festgehalten: Angebot gewünscht' }).waitFor();
  const f = await s.evaluate(() => { const x = firmaZumNamen('Küchen Nord'); return { status: x.status, chancen: vonFirma('chancen', x.id).map(c => c.phase), aufgaben: vonFirma('aufgaben', x.id).length, anrufe: vonFirma('aktivitaeten', x.id).filter(a => a.art === 'ANRUF').length }; });
  assert.deepEqual(f, { status: 'INTERESSENT', chancen: ['ANGEBOT'], aufgaben: 5, anrufe: 1 });
  await s.context().close();
});

test('Postfach (ältere App, nur Posteingang): Unterhaltungen, gelesen, Kennzeichnen mit Datum → Termine, Archiv, Fall aus Mail, Weiterleiten, Entwurf, geplant, Serienmail', async () => {
  const kontext = await browser.newContext({ serviceWorkers: 'block', viewport: { width: 1400, height: 900 } });
  await kontext.route('https://script.google.com/**', r => r.abort());
  await kontext.addInitScript(() => {
    if (!localStorage.getItem('schadensfaelle-v1')) { localStorage.setItem('schadensfaelle-v1', JSON.stringify({ nurLokal: true, einstellungen: { mailRueckgaengig: 0 } })); localStorage.setItem('crm-bereich', 'postfach'); }
    window.__gesendet = [];
    const konto = { adresse: 'info@ayse.example', benutzer: 'info@ayse.example' };
    let abgeholt = false;
    window.desktop = { plattform: 'test', beiZiel() {}, benachrichtige: async () => true, oeffneExtern: async a => { window.__extern = a; }, oeffneDatei: async () => true,
      mail: { anbieter: async () => ({}), konto: async () => konto, trennen: async () => true,
        sende: async (e, art) => { window.__gesendet.push(e); return 'gesendet-' + window.__gesendet.length + '@ayse.example'; },
        hole: async () => { if (abgeholt) return { mails: [], letzteUid: 3, uidValidity: 1 }; abgeholt = true;
          return { letzteUid: 3, uidValidity: 1, mails: [
            { messageId: 'a1@x', von: 'dispo@krause.example', vonName: 'Dispo Krause', an: 'info@ayse.example', betreff: 'Touren nächste Woche', text: 'Hallo, bitte drei Touren einplanen.', zeitpunkt: '2026-11-17T08:00:00Z', anhaenge: [], antwortAuf: [] },
            { messageId: 'a2@x', von: 'dispo@krause.example', an: 'info@ayse.example', betreff: 'Re: Touren nächste Woche', text: 'Und eine Montage.', zeitpunkt: '2026-11-17T09:00:00Z', anhaenge: [], antwortAuf: [] },
            { messageId: 'a3@x', von: 'kunde@gmail.com', vonName: 'Anna Becker', an: 'info@ayse.example', betreff: 'Schaden am Sofa', text: 'Das Sofa ist zerkratzt.', zeitpunkt: '2026-11-17T10:00:00Z', anhaenge: [], antwortAuf: [] }] }; } } };
  });
  const s = await kontext.newPage();
  s.on('pageerror', f => { throw f; });
  await s.goto(seite.url); await s.waitForFunction(() => window.__appBereit);
  await s.evaluate(() => Aktionen.firmaSpeichern({ name: 'Möbel Krause', email: 'dispo@krause.example', status: 'AKTIV' }));
  await s.evaluate(() => postfachAbrufen(true));
  await s.evaluate(() => bereichWechseln('postfach'));
  // Posteingang: Unterhaltungen gebündelt, Ordnerbereich mit lokalen Ordnern
  await s.locator('#postfach .pf-zeile').nth(1).waitFor();
  assert.equal(await s.locator('#postfach .pf-zeile').count(), 2);
  assert.equal(await s.locator('#postfach .pf-zeile.ungelesen').count(), 2);
  assert.ok((await s.locator('[data-pf-ordner="V:UNGELESEN"]').textContent()).includes('3'));
  assert.ok(await s.locator('.pf-ordner', { hasText: 'Gesendete Elemente' }).isVisible());
  // Öffnen = gelesen; Unterhaltung; Kennzeichnen mit Datum → Termine
  await s.locator('#postfach .pf-zeile', { hasText: 'Touren nächste Woche' }).click();
  await s.locator('#detail .pf-faden', { hasText: 'Unterhaltung (2)' }).waitFor();
  await s.locator('[data-mail-akt="flagge-menue"]').click();
  await s.locator('.kontext-menue button', { hasText: 'Nächste Woche' }).click();
  assert.ok(await s.evaluate(() => mails()['a2@x'].gelesen && mails()['a2@x'].stern && wiedervorlagen().demnaechst.concat(wiedervorlagen().spaeter || []).some(x => x.mailId === 'a2@x') || !!mails()['a2@x'].erinnerungAm));
  // Archivieren (lokal) und zurückholen per Rückgängig
  await s.locator('[data-mail-akt=archiv]').click();
  await s.locator('#toast button', { hasText: 'Rückgängig' }).click();
  await s.waitForFunction(() => mailOrdnerTyp(mails()['a2@x']) === 'INBOX');
  await s.evaluate(() => mailAktion(['a2@x'], 'verschieben', '@ARCHIVE'));
  await s.evaluate(() => { ANSICHT.pfOrdner = 'K:SCHADEN|#ARCHIVE'; gehe('#/'); });
  await s.locator('#postfach .pf-zeile', { hasText: 'Touren nächste Woche' }).waitFor();
  // Fall aus der Mail des Kunden → Mail hängt am Fall
  await s.evaluate(() => { ANSICHT.pfOrdner = 'V:EINGANG'; gehe('#/mail/' + encodeURIComponent('a3@x')); });
  await s.locator('[data-mail-akt=fall]').click();
  await s.locator('#fall-form').waitFor();
  assert.equal(await s.locator('#fall-form [name=kundeEmail]').inputValue(), 'kunde@gmail.com');
  assert.equal(await s.locator('#fall-form [name=kundeName]').inputValue(), 'Anna Becker');
  await s.locator('#fall-form button.voll').click();
  await s.waitForFunction(() => location.hash.startsWith('#/fall/'));
  await s.waitForFunction(() => !!mails()['a3@x'].fallId);
  // Weiterleiten mit Vorschlag aus dem Adressbuch, Entwurf, geplantes Senden
  await s.evaluate(() => { ANSICHT.bereich = 'postfach'; gehe('#/mail/' + encodeURIComponent('a1@x')); });
  await s.locator('[data-mail-akt=weiterleiten]').click();
  assert.equal(await s.locator('#mail-form [name=betreff]').inputValue(), 'WG: Touren nächste Woche');
  await s.locator('#mail-form [data-adressen=an] .vf-eingabe').fill('dispo');
  await s.locator('.vf-vorschlaege button', { hasText: 'dispo@krause.example' }).waitFor();
  await s.locator('#mail-form [data-adressen=an] .vf-eingabe').fill('chef@ayse.example');
  await s.locator('#mail-form [data-adressen=an] .vf-eingabe').press('Enter');
  await s.locator('#mail-entwurf').click();
  await s.locator('#mail-schliessen').click();
  await s.evaluate(() => { ANSICHT.pfOrdner = 'V:ENTWUERFE'; gehe('#/'); });
  await s.locator('[data-entwurf]').first().click();
  assert.deepEqual(await s.locator('#mail-form [data-adressen=an] .vf-chip').evaluateAll(l => l.map(c => c.dataset.wert)), ['chef@ayse.example']);
  await s.locator('#vf-spaeter').click();
  await s.locator('.kontext-menue button', { hasText: 'In 1 Stunde' }).click();
  await s.locator('#toast', { hasText: 'Wird gesendet' }).waitFor();
  assert.equal(await s.evaluate(() => [Object.keys(entwuerfe()).length, Object.keys(geplanteMails()).length].join()), '0,1');
  await s.evaluate(() => { for (const x of Object.values(geplanteMails())) x.sendeAm = '2000-01-01T00:00:00Z'; return geplanteSenden(); });
  assert.equal(await s.evaluate(() => [Object.keys(geplanteMails()).length, window.__gesendet.length].join()), '0,1');
  assert.ok(await s.evaluate(() => window.__gesendet[0].text.includes('Weitergeleitete Nachricht') || window.__gesendet[0].text.includes('Touren')), 'weitergeleiteter Inhalt');
  // Serienmail: je Firma eine Mail mit eigener Anrede, in der Kontakthistorie
  await s.evaluate(() => { const id = Aktionen.firmaSpeichern({ name: 'Küchen Nord', email: 'info@nord.example', status: 'INTERESSENT' }); Aktionen.crmSpeichern('ansprechpartner', { firmaId: id, name: 'Frau Nord', email: 'nord@nord.example' }); });
  await s.evaluate(() => serienmailDialog());
  await s.locator('#serie-form [name=vorlage]').selectOption('KAPAZITAET');
  await s.locator('#serie-alle').click();
  await s.locator('#serie-los').click();
  await s.locator('#toast', { hasText: '2 Mails gesendet' }).waitFor();
  const serie = await s.evaluate(() => window.__gesendet.slice(1).map(e => [e.an, e.text.split('\n')[0]]));
  assert.deepEqual(serie.sort(), [['dispo@krause.example', 'Sehr geehrte Damen und Herren,'], ['nord@nord.example', 'Guten Tag Frau Nord,']]);
  assert.ok(await s.evaluate(() => vonFirma('aktivitaeten', firmaZumNamen('Küchen Nord').id).some(a => a.art === 'EMAIL')));
  // Mails liegen in IndexedDB, nicht im localStorage – und sind nach dem Neuladen noch da
  await s.waitForTimeout(900);
  assert.equal(await s.evaluate(() => 'mails' in JSON.parse(localStorage.getItem('schadensfaelle-v1'))), false);
  await s.reload(); await s.waitForFunction(() => window.__appBereit);
  await s.waitForFunction(() => !!(S.mails && S.mails['a1@x']));
  await kontext.close();
});

/** Simulierter Mailserver (Schnittstelle 2) im Browser: Ordner, UIDs, Flags, Verschieben, Suche, Senden. */
function imapFake() {
  const V = 7, F = { INBOX: 'INBOX', Sent: 'SENT', Archiv: 'ARCHIVE', Trash: 'TRASH', Junk: 'JUNK', Projekte: '' };
  const box = Object.fromEntries(Object.keys(F).map(k => [k, []])), naechste = Object.fromEntries(Object.keys(F).map(k => [k, 1]));
  const leg = (ordner, mail, flags = []) => { const uid = naechste[ordner]++; box[ordner].push({ uid, flags: new Set(flags), mail: { anhaenge: [], antwortAuf: [], text: '', html: '', an: 'info@ayse.example', ...mail } }); return uid; };
  const alsMail = (ordner, e) => ({ ...e.mail, uid: e.uid, ordner, gelesen: e.flags.has('\\Seen'), markiert: e.flags.has('\\Flagged'), beantwortet: e.flags.has('\\Answered'), anhaenge: e.mail.anhaenge.map(a => ({ ...a, base64: a.gross ? undefined : a.base64 })) });
  window.__imap = { box, leg, gesendet: [], aufrufe: [] };
  const ruf = async (befehl, d, art) => {
    window.__imap.aufrufe.push(befehl);
    const o = d.ordner;
    if (befehl === 'ordner') return Object.keys(F).map(p => ({ pfad: p, name: p, trenner: '/', spezial: F[p], gesamt: box[p].length, ungelesen: box[p].filter(e => !e.flags.has('\\Seen')).length, auswaehlbar: true }));
    if (befehl === 'stand') return { ordner: o, uidValidity: V, uidNext: naechste[o], gesamt: box[o].length, eintraege: box[o].filter(e => !d.seit || e.mail.zeitpunkt.slice(0, 10) >= d.seit).map(e => [e.uid, +e.flags.has('\\Seen'), +e.flags.has('\\Flagged'), +e.flags.has('\\Answered'), 0]) };
    if (befehl === 'lade') return box[o].filter(e => d.uids.map(Number).includes(e.uid)).map(e => alsMail(o, e));
    if (befehl === 'suche') return box[o].filter(e => (!d.text || (e.mail.betreff + e.mail.text).toLowerCase().includes(d.text.toLowerCase())) && (!d.von || e.mail.von.includes(d.von))).map(e => e.uid).reverse();
    if (befehl === 'anhang') { const e = box[o].find(x => x.uid === Number(d.uid)); const a = e.mail.anhaenge[Number(d.nr)]; return { name: a.name, mimeType: a.mimeType, base64: a.base64 }; }
    if (befehl === 'quelle') { const e = box[o].find(x => x.uid === Number(d.uid)); return btoa('Subject: ' + e.mail.betreff + '\r\n\r\n' + e.mail.text); }
    if (befehl === 'ordnerAktion') { if (d.aktion === 'anlegen') { box[d.pfad] = []; naechste[d.pfad] = 1; F[d.pfad] = ''; } return true; }
    if (befehl === 'aktion') {
      const l = box[o].filter(e => d.uids.map(Number).includes(e.uid)), FL = { gelesen: ['\\Seen', 1], ungelesen: ['\\Seen', 0], markieren: ['\\Flagged', 1], entmarkieren: ['\\Flagged', 0], beantwortet: ['\\Answered', 1] };
      if (FL[d.aktion]) { l.forEach(e => (FL[d.aktion][1] ? e.flags.add(FL[d.aktion][0]) : e.flags.delete(FL[d.aktion][0]))); return { neueUids: {} }; }
      if (d.aktion === 'verschieben') { const neue = {}; for (const e of l) { box[o].splice(box[o].indexOf(e), 1); neue[e.uid] = leg(d.ziel, e.mail, [...e.flags]); } return { neueUids: neue }; }
      if (d.aktion === 'loeschen') { l.forEach(e => box[o].splice(box[o].indexOf(e), 1)); return { neueUids: {} }; }
    }
    if (befehl === 'sende') { const id = 'neu-' + (window.__imap.gesendet.length + 1) + '@ayse.example'; window.__imap.gesendet.push({ ...d, art }); leg('Sent', { messageId: id, von: 'info@ayse.example', betreff: d.betreff, text: d.text, html: d.html, an: d.an, zeitpunkt: new Date().toISOString() }, ['\\Seen']); return id; }
    throw new Error('Unbekannter Befehl: ' + befehl);
  };
  const konto = { adresse: 'info@ayse.example', benutzer: 'info@ayse.example' };
  window.desktop = { plattform: 'test', beiZiel() {}, benachrichtige: async () => true, oeffneExtern: async a => { window.__extern = a; }, oeffneDatei: async () => true, speichere: async n => 'C:/' + n,
    mail: { anbieter: async () => ({}), konto: async a => (a === 'ALLGEMEIN' ? null : konto), trennen: async () => true, schnittstelle: 2, ruf, sende: (e, a) => ruf('sende', e, a), hole: async () => ({ mails: [] }) } };
}
async function postfachGeraet(einstellungen = {}) {
  const kontext = await browser.newContext({ serviceWorkers: 'block', viewport: { width: 1500, height: 950 } });
  await kontext.route('https://script.google.com/**', r => r.abort());
  await kontext.addInitScript(e => { if (!localStorage.getItem('schadensfaelle-v1')) { localStorage.setItem('schadensfaelle-v1', JSON.stringify({ nurLokal: true, einstellungen: { mailRueckgaengig: 0, ...e } })); localStorage.setItem('crm-bereich', 'postfach'); } }, einstellungen);
  await kontext.addInitScript(imapFake);
  const s = await kontext.newPage();
  s.on('pageerror', f => { throw f; });
  await s.goto(seite.url); await s.waitForFunction(() => window.__appBereit);
  await s.waitForFunction(() => MAIL.konto && MAILSPEICHER.geladen);
  return { s, kontext };
}
const heuteIso = (stunde = 8) => { const d = new Date(); d.setHours(stunde, 0, 0, 0); return d.toISOString(); };

test('Postfach wie Outlook: Serverordner, Abgleich in beide Richtungen, Relevant/Sonstige, HTML sicher, Lesen/Kennzeichnen/Archivieren/Löschen mit Rückgängig, Tastatur', async () => {
  const { s, kontext } = await postfachGeraet();
  await s.evaluate(([h1, h2, h3]) => {
    Aktionen.firmaSpeichern({ name: 'Möbel Krause', email: 'dispo@krause.example', status: 'AKTIV' });
    const pdf = btoa('%PDF-1.4 Testinhalt'), png = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';
    __imap.leg('INBOX', { messageId: 'k1@krause', von: 'dispo@krause.example', vonName: 'Dispo Krause', betreff: 'Touren KW 48', text: 'Bitte drei Touren einplanen.', zeitpunkt: h1,
      html: '<p>Bitte <b>drei Touren</b> einplanen.</p><img src="cid:logo1"><img src="https://tracker.example/pixel.gif"><a href="https://krause.example/plan">Plan</a><script>alert(1)</script>',
      anhaenge: [{ name: 'logo.png', mimeType: 'image/png', groesse: 70, cid: 'logo1', inline: true, base64: png }, { name: 'Plan.pdf', mimeType: 'application/pdf', groesse: 900000, base64: pdf, gross: true }] });
    __imap.leg('INBOX', { messageId: 'n1@shop', von: 'newsletter@shop.example', vonName: 'Shop', betreff: 'Angebote der Woche', text: 'Rabatte!', zeitpunkt: h2, abmelden: 'https://shop.example/ab' });
    __imap.leg('INBOX', { messageId: 'k2@krause', von: 'dispo@krause.example', vonName: 'Dispo Krause', betreff: 'AW: Touren KW 48', text: 'Und eine Montage.', zeitpunkt: h3, antwortAuf: ['k1@krause'], wichtig: 'HOCH', cc: 'chef@krause.example' });
    __imap.leg('Archiv', { messageId: 'alt@krause', von: 'dispo@krause.example', betreff: 'Altes', text: 'Schon erledigt', zeitpunkt: h1 }, ['\\Seen']);
  }, [heuteIso(7), heuteIso(8), heuteIso(9)]);
  await s.evaluate(() => postfachAbrufen(true));
  await s.evaluate(() => bereichWechseln('postfach'));
  // Ordnerbereich mit Serverordnern, Relevant/Sonstige
  for (const o of ['Posteingang', 'Gesendete Elemente', 'Archiv', 'Gelöschte Elemente', 'Junk-E-Mail', 'Projekte']) assert.ok(await s.locator('.pf-ordner', { hasText: o }).isVisible(), o);
  assert.equal(await s.locator('#postfach .pf-zeile').count(), 1, 'Relevant: eine Unterhaltung (2 Mails)');
  assert.equal(await s.locator('#postfach .pf-anzahl').first().textContent(), '2');
  await s.locator('[data-pf-fokus=SONSTIGE]').click();
  await s.locator('#postfach .pf-zeile', { hasText: 'Angebote der Woche' }).waitFor();
  await s.locator('[data-pf-fokus=RELEVANT]').click();
  // HTML: Skript entfernt, Inline-Bild da, externes Bild blockiert, Link öffnet extern; gelesen auf dem Server
  await s.evaluate(() => gehe('#/mail/' + encodeURIComponent('k1@krause')));
  await s.locator('#detail .pf-info', { hasText: 'Externe Bilder' }).waitFor();
  const rahmen = s.frameLocator('#pf-koerper iframe');
  await rahmen.locator('b', { hasText: 'drei Touren' }).waitFor();
  assert.equal(await rahmen.locator('script').count(), 0);
  assert.match(await rahmen.locator('img').first().getAttribute('src'), /^blob:/);
  assert.equal(await rahmen.locator('img[data-blockiert]').count(), 1);
  await rahmen.locator('a', { hasText: 'Plan' }).click();
  await s.waitForFunction(() => window.__extern === 'https://krause.example/plan');
  await s.waitForFunction(() => __imap.box.INBOX.find(e => e.mail.messageId === 'k1@krause').flags.has('\\Seen'));
  // Großer Anhang wird erst beim Öffnen geladen
  assert.equal(await s.locator('.pf-anh button', { hasText: 'Plan.pdf' }).count(), 1);
  await s.locator('.pf-anh button', { hasText: 'Plan.pdf' }).click();
  await s.locator('#anh-oeffnen').waitFor(); await s.locator('#dialog [data-zu]').last().click();
  // Kennzeichnen per Einfg, Kategorie, Anheften
  await s.keyboard.press('Insert');
  await s.waitForFunction(() => __imap.box.INBOX.find(e => e.mail.messageId === 'k1@krause').flags.has('\\Flagged'));
  await s.locator('[data-mail-akt="kategorie-menue"]').click();
  await s.locator('.kontext-menue button', { hasText: 'Grüne Kategorie' }).click();
  assert.deepEqual(await s.evaluate(() => mails()['k1@krause'].kategorien), ['gruen']);
  // Archivieren per E: auf dem Server verschoben, nächste Mail offen; Rückgängig holt zurück
  await s.keyboard.press('e');
  await s.waitForFunction(() => __imap.box.Archiv.some(e => e.mail.messageId === 'k1@krause') && !__imap.box.INBOX.some(e => e.mail.messageId === 'k1@krause'));
  assert.notEqual(await s.evaluate(() => decodeURIComponent(route().id || '')), 'k1@krause');
  await s.locator('#toast button', { hasText: 'Rückgängig' }).click();
  await s.waitForFunction(() => __imap.box.INBOX.some(e => e.mail.messageId === 'k1@krause'));
  // Löschen → Gelöschte Elemente; dort endgültig nach Rückfrage
  await s.evaluate(() => gehe('#/mail/' + encodeURIComponent('n1@shop')));
  await s.keyboard.press('Delete');
  await s.waitForFunction(() => __imap.box.Trash.some(e => e.mail.messageId === 'n1@shop'));
  await s.evaluate(() => mailLoeschen(['n1@shop']));
  await s.locator('#ja').click();
  await s.waitForFunction(() => !__imap.box.Trash.length && !mails()['n1@shop']);
  // Änderungen von anderswo (z. B. Outlook am Handy): gelesen + verschoben → hier übernommen
  await s.evaluate(() => { const b = __imap.box; const e = b.INBOX.find(x => x.mail.messageId === 'k2@krause'); e.flags.add('\\Seen'); b.INBOX.splice(b.INBOX.indexOf(e), 1); __imap.leg('Projekte', e.mail, [...e.flags]); });
  await s.evaluate(() => postfachAbrufen(true));
  assert.equal(await s.evaluate(() => [mails()['k2@krause'].ordner, mails()['k2@krause'].gelesen].join()), 'Projekte,true');
  // Ordner per Klick, Ziehen auf Ordner verschiebt
  await s.evaluate(() => { gehe('#/'); ANSICHT.pfOrdner = 'K:SCHADEN|INBOX'; zeichne(); });
  await s.locator('#postfach .pf-zeile', { hasText: 'Touren KW 48' }).dragTo(s.locator('.pf-o[data-pf-ordner="K:SCHADEN|Projekte"]'));
  await s.waitForFunction(() => __imap.box.Projekte.some(e => e.mail.messageId === 'k1@krause'));
  // Suche mit Operatoren über alle Ordner
  await s.locator('#postfach-suche').fill('von:dispo ist:gelesen');
  await s.waitForTimeout(250);
  assert.ok(await s.locator('#postfach .pf-zeile').count() >= 2);
  await kontext.close();
});

test('Postfach: Verfassen (Chips, Cc, Format, Signatur, Wichtigkeit), Antworten an alle, Anhang-Erinnerung, Senden rückgängig, Regeln, Blockieren, Zurückstellen, Abwesenheitsnotiz, Mehrfachauswahl', async () => {
  const { s, kontext } = await postfachGeraet({ mailRueckgaengig: 5 });
  await s.evaluate(([h]) => {
    Aktionen.firmaSpeichern({ name: 'Möbel Krause', email: 'dispo@krause.example', status: 'AKTIV' });
    __imap.leg('INBOX', { messageId: 'k3@krause', von: 'dispo@krause.example', vonName: 'Dispo Krause', an: 'info@ayse.example, lager@krause.example', cc: 'chef@krause.example', betreff: 'Lieferung Freitag', text: 'Passt Freitag?', zeitpunkt: h });
  }, [heuteIso(10)]);
  await s.evaluate(() => postfachAbrufen());
  await s.evaluate(() => { bereichWechseln('postfach'); gehe('#/mail/' + encodeURIComponent('k3@krause')); });
  // Allen antworten: An = Absender, Cc = übrige ohne eigene Adresse; Betreff AW:, Signatur, Zitat
  await s.locator('[data-mail-akt=allen]').click();
  assert.deepEqual(await s.locator('[data-adressen=an] .vf-chip').evaluateAll(l => l.map(c => c.dataset.wert)), ['"Dispo Krause" <dispo@krause.example>']);
  assert.deepEqual((await s.locator('[data-adressen=cc] .vf-chip').evaluateAll(l => l.map(c => c.dataset.wert))).sort(), ['chef@krause.example', 'lager@krause.example']);
  assert.equal(await s.locator('#mail-form [name=betreff]').inputValue(), 'AW: Lieferung Freitag');
  await s.locator('#vf-editor blockquote', { hasText: 'Passt Freitag?' }).waitFor();
  // Formatierung und Wichtigkeit; Anhang erwähnt, aber keiner dran → Rückfrage
  await s.locator('#vf-editor').click();
  await s.keyboard.press('Control+Home');
  await s.locator('[data-format=bold]').click();
  await s.keyboard.type('Ja, passt – anbei die Bestätigung.');
  await s.locator('#vf-wichtig').click();
  await s.locator('#mail-senden').click();
  await s.locator('dialog.klein', { hasText: 'Anhang' }).waitFor();
  await s.locator('dialog.klein button.voll').click();
  // Senden rückgängig: erst im Ausgang, Rückgängig öffnet wieder als Entwurf
  await s.locator('#toast button', { hasText: 'Rückgängig' }).click();
  await s.locator('#mail-form').waitFor();
  assert.equal(await s.evaluate(() => __imap.gesendet.length), 0, 'noch nicht gesendet');
  await s.evaluate(() => { S.einstellungen.mailRueckgaengig = 0; });
  await s.locator('#mail-senden').click();
  await s.locator('dialog.klein button.voll').click();
  await s.waitForFunction(() => __imap.gesendet.length === 1);
  const g = await s.evaluate(() => __imap.gesendet[0]);
  assert.match(g.html, /<b>Ja, passt/); assert.equal(g.wichtig, 'HOCH'); assert.match(g.cc, /chef@krause/); assert.match(g.text, /Ja, passt/);
  assert.deepEqual(g.referenzen, ['k3@krause']); assert.equal(g.antwortAuf, 'k3@krause');
  await s.waitForFunction(() => mails()['k3@krause'].beantwortet && __imap.box.INBOX.find(e => e.mail.messageId === 'k3@krause').flags.has('\\Answered'));
  // Neue Mail: Adresse per Vorschlag, ungültige Adresse wird markiert
  await s.evaluate(() => mailAnFirma());
  await s.locator('[data-adressen=an] .vf-eingabe').fill('krause');
  await s.locator('.vf-vorschlaege button', { hasText: 'dispo@krause.example' }).click();
  await s.locator('[data-adressen=an] .vf-eingabe').fill('kaputt@');
  await s.locator('[data-adressen=an] .vf-eingabe').press('Enter');
  assert.equal(await s.locator('[data-adressen=an] .vf-chip.falsch').count(), 1);
  await s.locator('#mail-verwerfen').click();
  // Regel: Mails von newsletter@ → Kategorie + Ordner Projekte; blockierter Absender → Junk
  await s.evaluate(() => { S.einstellungen.mailRegeln = [{ id: 'r1', name: 'Newsletter', an: true, bedingungen: { von: 'newsletter@' }, aktionen: { ordner: 'SCHADEN|Projekte', kategorie: 'blau' } }];
    S.einstellungen.mailBlockiert = ['@spam.example'];
    __imap.leg('INBOX', { messageId: 'n2@shop', von: 'newsletter@shop.example', betreff: 'Neue Angebote', text: 'x', zeitpunkt: new Date().toISOString() });
    __imap.leg('INBOX', { messageId: 'sp@spam', von: 'gewinn@spam.example', betreff: 'Gewinn!', text: 'x', zeitpunkt: new Date().toISOString() }); });
  await s.evaluate(() => postfachAbrufen());
  await s.waitForFunction(() => __imap.box.Projekte.some(e => e.mail.messageId === 'n2@shop') && __imap.box.Junk.some(e => e.mail.messageId === 'sp@spam'));
  assert.deepEqual(await s.evaluate(() => mails()['n2@shop'].kategorien), ['blau']);
  // Abwesenheitsnotiz: einmal je Absender, mit Auto-Submitted; nicht an Newsletter
  await s.evaluate(() => { S.einstellungen.mailAbwesend = { an: true, text: 'Bin im Urlaub.' };
    __imap.leg('INBOX', { messageId: 'k4@krause', von: 'dispo@krause.example', betreff: 'Frage', text: '?', zeitpunkt: new Date().toISOString() });
    __imap.leg('INBOX', { messageId: 'k5@krause', von: 'dispo@krause.example', betreff: 'Noch eine Frage', text: '?', zeitpunkt: new Date().toISOString() }); });
  await s.evaluate(() => postfachAbrufen());
  const auto = await s.evaluate(() => __imap.gesendet.filter(x => /Automatische Antwort/.test(x.betreff)));
  assert.equal(auto.length, 1); assert.equal(auto[0].an, 'dispo@krause.example'); assert.equal(auto[0].kopfzeilen['Auto-Submitted'], 'auto-replied');
  // Zurückstellen: verschwindet aus dem Posteingang, kommt zur Zeit ungelesen zurück
  await s.evaluate(() => { S.einstellungen.mailAbwesend = { an: false }; gehe('#/'); ANSICHT.pfOrdner = 'V:EINGANG'; zeichne(); });
  await s.evaluate(() => zurueckstellen(['k4@krause'], new Date(Date.now() + 3600000).toISOString()));
  assert.equal(await s.locator('#postfach .pf-zeile', { hasText: 'Frage' }).count(), 1, 'nur noch „Noch eine Frage“');
  await s.evaluate(() => { mails()['k4@krause'].zurueckBis = '2000-01-01T00:00:00Z'; mails()['k4@krause'].gelesen = true; zurueckgestellteWecken(); mailsGeaendert(); });
  await s.waitForFunction(() => !mails()['k4@krause'].zurueckBis && !mails()['k4@krause'].gelesen);
  // Mehrfachauswahl: Strg+Klick, Leiste „gelesen“
  await s.evaluate(() => { S.einstellungen.mailUnterhaltung = false; zeichne(); });
  await s.locator('#postfach .pf-zeile', { hasText: 'Noch eine Frage' }).click({ modifiers: ['Control'] });
  await s.locator('#postfach .pf-zeile', { hasText: 'Frage' }).filter({ hasNotText: 'Noch eine' }).click({ modifiers: ['Control'] });
  await s.locator('.pf-auswahl [data-pf-mehrfach=gelesen]').click();
  await s.waitForFunction(() => ['k4@krause', 'k5@krause'].every(id => __imap.box.INBOX.find(e => e.mail.messageId === id).flags.has('\\Seen')));
  // Einstellungen-Dialog: alle Bereiche öffnen ohne Fehler
  await s.evaluate(() => mailEinstellungenDialog());
  for (const tab of ['SIGNATUREN', 'VORLAGEN', 'KATEGORIEN', 'REGELN', 'QUICKSTEPS', 'JUNK', 'ABWESEND', 'ALLGEMEIN']) await s.locator(`[data-pf-tab=${tab}]`).click();
  await kontext.close();
});

test('Runde 4: Aufgaben, Kalender, Zeiterfassung mit ArbZG, Entwürfe, Excel, Fahrerkarte, Fahrer-Modus mit Unterschrift', async () => {
  const { fahrerkarte } = require('./ddd.js');
  const s = await geraet(null, { bereich: 'aufgaben', timezoneId: 'Europe/Berlin' });
  const ids = await s.evaluate(() => {
    const f = Aktionen.firmaSpeichern({ name: 'Möbel Krause', status: 'AKTIV' }); Aktionen.crmSpeichern('adressen', { firmaId: f, art: 'HAUPTSITZ', strasse: 'Hauptstr. 1', plz: '42853', ort: 'Remscheid' });
    FuhrparkAktionen.speichern({ kennzeichen: 'RS-AT 100', status: 'AKTIV', zgg: 3500 });
    const a = PersonalAktionen.speichern({ name: 'Ali Demir', rolle: 'FAHRER', wochenstunden: 40 });
    const t = TourAktionen.neu(heute(), { kennzeichen: 'RS-AT 100', auftraggeberId: f, auftraggeber: '', name: 'Küche', uhrzeit: '06:30', fahrerId: a });
    TourAktionen.neu(plusTage(heute(), -1), { kennzeichen: 'RS-AT 100', auftraggeberId: f, auftraggeber: '', name: 'Sofa', uhrzeit: '07:15', fahrerId: a });
    return { a, t };
  });
  // Aufgaben per Schnelleingabe: „morgen“ und „@Ali“ werden erkannt
  await s.locator('#aufgabe-schnell input').fill('Reifen prüfen morgen @Ali');
  await s.locator('#aufgabe-schnell input').press('Enter');
  await s.locator('#aufgaben', { hasText: 'Reifen prüfen' }).waitFor();
  assert.deepEqual(await s.evaluate(() => { const x = alle('aufgabenIntern')[0]; return [x.titel, x.faelligAm === plusTage(heute(), 1), maName(x.zustaendigId)]; }), ['Reifen prüfen morgen', true, 'Ali Demir']);
  await s.locator('[data-aufgabe-erledigt]').first().click();
  await s.waitForFunction(() => alle('aufgabenIntern')[0].erledigt);
  // Kalender zeigt Touren und Feiertage
  assert.equal(await s.evaluate(() => feiertage(2026, 'NW')['2026-06-04']), 'Fronleichnam');
  assert.equal(await s.evaluate(() => feiertage(2026, 'NW')['2026-04-03']), 'Karfreitag');
  await s.evaluate(() => bereichWechseln('kalender'));
  await s.locator('.kal-zelle.heute', { hasText: '1 Tour' }).waitFor();
  // Zeiterfassung: Verstöße werden angezeigt, Entwürfe zählen nicht
  await s.evaluate(id => { ZeitAktionen.speichern({ mitarbeiterId: id, datum: '2026-11-02', beginn: '05:00', ende: '17:00', pauseMin: 20 }); ZeitAktionen.speichern({ mitarbeiterId: id, datum: '2026-11-03', beginn: '03:00', ende: '08:00', pauseMin: 0 }); }, ids.a);
  const h = await s.evaluate(id => [arbzgPruefen(id, '2026-11-02').map(x => x[1]), arbzgPruefen(id, '2026-11-03').map(x => x[1])], ids.a);
  assert.ok(h[0].some(x => x.includes('mehr als 10 h')) && h[0].some(x => x.includes('zu wenig Pause')), h[0]);
  assert.ok(h[1].some(x => x.includes('Ruhezeit nur 10:00 h')), h[1]);
  const n = await s.evaluate(id => ZeitAktionen.entwuerfeAusPlan(maVon(id), heute().slice(0, 7)), ids.a);
  assert.ok(n >= 1);
  const vorher = await s.evaluate(id => zeitMonat(maVon(id), heute().slice(0, 7)).summe, ids.a);
  await s.evaluate(id => { const z = alle('zeiten').find(x => x.quelle === 'PLAN' && x.datum === heute()); ZeitAktionen.bestaetigen(z, { beginn: '06:30', ende: '15:00', pauseMin: 30 }); }, ids.a);
  const nachher = await s.evaluate(id => zeitMonat(maVon(id), heute().slice(0, 7)).summe, ids.a);
  assert.equal(nachher - vorher, 480, 'erst bestätigte Zeiten zählen (8 h)');
  // Excel ist eine gültige ZIP-Datei mit Blatt je Mitarbeiter
  const xlsx = await s.evaluate(async id => { const b = zeitenXlsx([maVon(id)], heute().slice(0, 7)); const u = new Uint8Array(await b.arrayBuffer()); return [u[0], u[1], b.size, new TextDecoder().decode(u).includes('Ali Demir')]; }, ids.a);
  assert.deepEqual([xlsx[0], xlsx[1], xlsx[3]], [0x50, 0x4b, true]);
  // Fahrerkarte einlesen: Kennzeichen-Alias, Zeiten aus der Karte, Lenkzeit-Prüfung
  await s.evaluate(() => bereichWechseln('zeiten'));
  await s.locator('[data-tacho-import-knopf]').click();
  const karte = fahrerkarte({ kartenNr: 'DF000099', nachname: 'Demir', vorname: 'Ali', tage: [{ datum: '2026-11-10', km: 300, wechsel: [[0, 'RUHE'], [300, 'LENKEN'], [600, 'RUHE'], [620, 'LENKEN'], [800, 'RUHE']] }],
    fahrzeuge: [{ kz: 'RS AT 100 X', kmVon: 1000, kmBis: 1300, ab: '2026-11-10T05:00:00Z', bis: '2026-11-10T14:00:00Z' }] });
  await s.locator('#tacho-datei').setInputFiles({ name: 'karte.ddd', mimeType: 'application/octet-stream', buffer: karte });
  await s.locator('[data-tacho-alias]').selectOption({ label: 'RS-AT 100' });
  await s.locator('#tacho-los').click();
  await s.locator('#toast', { hasText: 'Fahrerkarte: 1 Tage übernommen, 1 Arbeitszeiten' }).waitFor();
  const t = await s.evaluate(() => { const m = maZumNamen('Ali Demir'), z = zeitenVon(m.id, '2026-11-10', '2026-11-10')[0], tt = tachotageVon(m.id)[0];
    return { nr: m.fahrerkarteNr, quelle: z.quelle, beginn: z.beginn, ende: z.ende, lenk: tt.fahrMin, alias: fahrzeugZumKz('RS AT 100 X') && fahrzeugZumKz('RS AT 100 X').kennzeichen, km: fahrzeugZumKz('RS-AT 100').kmStand,
      verstoss: Object.values(lenkzeitPruefung(tachotageVon(m.id))).flat().map(x => x[1]) }; });
  assert.deepEqual([t.nr, t.quelle, t.beginn, t.ende, t.lenk, t.alias, t.km], ['DF000099', 'TACHO', '06:00', '14:20', 480, 'RS-AT 100', 1300]);
  assert.ok(t.verstoss.some(x => x.includes('über 4,5 h gelenkt')), t.verstoss);
  // Fahrer-Modus: Stempeluhr und Abliefern mit Unterschrift
  await s.evaluate(id => { S.einstellungen.ichId = id; bereichWechseln('fahrer'); }, ids.a);
  await s.locator('[data-stempel=start]').click();
  await s.locator('[data-stempel=ende]').click();
  assert.equal(await s.evaluate(id => zeitenVon(id, heute(), heute()).some(z => z.quelle === 'STEMPEL'), ids.a), true);
  await s.locator('[data-fahrer=abliefern]').first().click();
  await s.locator('#abliefern-form [name=empfaenger]').fill('Frau Kunde');
  const c = await s.locator('#sig').boundingBox();
  await s.mouse.move(c.x + 20, c.y + 30); await s.mouse.down(); await s.mouse.move(c.x + 120, c.y + 60); await s.mouse.move(c.x + 200, c.y + 20); await s.mouse.up();
  await s.locator('#abliefern-form button.voll').click();
  await s.locator('#toast', { hasText: 'Abgeliefert' }).waitFor();
  const ab = await s.evaluate(id => { const t = S.touren[id]; return [t.status, t.empfaenger, anlagenVon(id).map(a => a.anzeigename.split(' – ')[0])]; }, ids.t);
  assert.deepEqual(ab, ['ERLEDIGT', 'Frau Kunde', ['Unterschrift']]);
  await s.context().close();
});

test('iPhone: kein seitliches Scrollen in allen Bereichen, Menü und Dialoge bedienbar', async () => {
  const { devices } = require('playwright');
  const kontext = await browser.newContext({ ...devices['iPhone 13'], serviceWorkers: 'block' });
  await kontext.route('https://script.google.com/**', r => r.abort());
  await kontext.addInitScript(() => { if (!localStorage.getItem('schadensfaelle-v1')) localStorage.setItem('schadensfaelle-v1', JSON.stringify({ nurLokal: true })); });
  const s = await kontext.newPage(); s.on('pageerror', f => { throw f; });
  await s.goto(seite.url); await s.waitForFunction(() => window.__appBereit);
  await s.evaluate(() => { const f = Aktionen.firmaSpeichern({ name: 'Eine Firma mit einem wirklich sehr langen Namen GmbH & Co. KG Niederlassung West', status: 'AKTIV' });
    const a = PersonalAktionen.speichern({ name: 'Ali Demir' }); FuhrparkAktionen.speichern({ kennzeichen: 'RS-AT 100' }); S.einstellungen.ichId = a;
    TourAktionen.neu(heute(), { kennzeichen: 'RS-AT 100', auftraggeberId: f, auftraggeber: '', name: 'Langer Tourname Nummer eins mit vielen Wörtern', fahrerId: a }); nachAenderung(); });
  for (const b of ['uebersicht', 'fahrer', 'kalender', 'termine', 'aufgaben', 'touren', 'personal', 'zeiten', 'fuhrpark', 'firmen', 'faelle', 'finanzen', 'auswertung']) {
    await s.evaluate(x => bereichWechseln(x), b);
    const [w, v] = await s.evaluate(() => [document.documentElement.scrollWidth, innerWidth]);
    assert.ok(w <= v + 1, `${b}: ${w} > ${v}`);
  }
  await s.locator('#menue-knopf').click();
  await s.locator('.nav-gross [data-bereich=zeiten]').click();
  await s.waitForFunction(() => ANSICHT.bereich === 'zeiten' && !document.body.classList.contains('nav-offen'));
  await s.evaluate(() => neuMenue());
  const d = await s.locator('#dialog').boundingBox();
  assert.ok(d.width >= 389, 'Dialoge am Handy über die ganze Breite');
  await kontext.close();
});

test('Logins: Admin legt Fahrer-Login an, Fahrer meldet sich an und sieht nur seine Touren', async () => {
  const cloud = await starteCloud();
  const a = await geraet(cloud.url, { bereich: 'touren' });
  await melde(a, PIN, true);
  await aktuell(a);
  await a.evaluate(() => {
    const f1 = Aktionen.firmaSpeichern({ name: 'Möbel Krause', status: 'AKTIV' }), f2 = Aktionen.firmaSpeichern({ name: 'Geheim GmbH', status: 'AKTIV' });
    Aktionen.crmSpeichern('preise', { firmaId: f1, bezeichnung: 'Montage', betragCent: 25000 });
    const ali = PersonalAktionen.speichern({ name: 'Ali Demir', rolle: 'FAHRER', telefon: '0171 1', stundenlohnCent: 1500 }), cem = PersonalAktionen.speichern({ name: 'Cem Kaya', rolle: 'FAHRER' });
    TourAktionen.neu(heute(), { kennzeichen: 'RS-AT 100', auftraggeberId: f1, auftraggeber: '', name: 'Küche', fahrerId: ali });
    TourAktionen.neu(heute(), { kennzeichen: 'RS-AT 200', auftraggeberId: f2, auftraggeber: '', name: 'Fremd', fahrerId: cem });
  });
  await abgleich(a);
  // Admin: Login für Ali über die Mitarbeiter-Seite
  await a.evaluate(() => { bereichWechseln('personal'); gehe('#/mitarbeiter/' + maZumNamen('Ali Demir').id); });
  await a.locator('[data-ma=login]').click();
  await a.locator('#benutzer-form [name=login]').waitFor();
  assert.equal(await a.locator('#benutzer-form [name=login]').inputValue(), 'ali.demir');
  await a.locator('#benutzer-form [name=passwort]').fill('fahrer-123');
  await a.locator('#benutzer-form button.voll').click();
  await a.locator('#dialog pre', { hasText: 'Benutzername: ali.demir' }).waitFor();
  const zugang = await a.locator('#dialog pre').textContent();
  await a.locator('#z-fertig').click();
  await a.locator('#benutzer-liste').waitFor();
  await a.evaluate(() => $('#dialog').close());
  // Fahrer-Gerät
  const f = await geraet(cloud.url, { bereich: 'firmen' });
  await f.locator('#login-name').fill('ali.demir');
  await f.locator('#login-pw').fill('falsch1');
  await f.locator('#login-form button.voll').click();
  await f.locator('#login-fehler', { hasText: 'falsch' }).waitFor();
  await f.locator('#login-pw').fill('fahrer-123');
  await f.locator('#login-form button.voll').click();
  await aktuell(f);
  await f.locator('.fahrer-tour', { hasText: 'Küche' }).waitFor();
  const sicht = await f.evaluate(() => ({ bereich: ANSICHT.bereich, touren: alle('touren').map(t => t.name), firmen: auftraggeberListe().map(x => x.name), preise: alle('preise').length,
    lohn: alle('mitarbeiter').map(m => m.stundenlohnCent), nav: [...document.querySelectorAll('.nav-gross [data-bereich]')].map(b => b.dataset.bereich) }));
  assert.equal(sicht.bereich, 'fahrer');
  assert.deepEqual(sicht.touren, ['Küche']);
  assert.deepEqual(sicht.firmen, ['Möbel Krause']);
  assert.equal(sicht.preise, 0);
  assert.ok(sicht.lohn.every(x => x === undefined));
  assert.ok(!sicht.nav.includes('firmen') && !sicht.nav.includes('finanzen') && sicht.nav.includes('zeiten'));
  // Fahrer liefert ab → Admin sieht den Status
  await f.locator('[data-fahrer=abliefern]').click();
  await f.locator('#abliefern-form [name=empfaenger]').fill('Frau Kunde');
  await f.locator('#abliefern-form button.voll').click();
  await f.locator('#toast', { hasText: 'Abgeliefert' }).waitFor();
  await abgleich(f);
  await abgleich(a);
  assert.deepEqual(await a.evaluate(() => { const t = tourenAm(heute()).find(x => x.name === 'Küche'); return [t.status, t.empfaenger]; }), ['ERLEDIGT', 'Frau Kunde']);
  // Admin sperrt den Login → Fahrer muss sich neu anmelden
  await a.evaluate(() => gehe('#/einstellungen'));
  await a.locator('#benutzer-knopf').click();
  await a.locator('[data-benutzer]').first().click();
  await a.locator('#benutzer-form [name=aktiv]').uncheck();
  await a.locator('#benutzer-form button.voll').click();
  await a.locator('#benutzer-liste', { hasText: 'gesperrt' }).waitFor();
  await f.evaluate(() => abgleich());
  await f.locator('#login-form').waitFor();
  await a.context().close(); await f.context().close(); await cloud.schliessen();
});

test('Monatsabrechnung je Firma und Briefe (Urlaubsbestätigung, Beschäftigung, Firma)', async () => {
  const s = await geraet(null, { bereich: 'finanzen', timezoneId: 'Europe/Berlin' });
  const ids = await s.evaluate(() => {
    const f = Aktionen.firmaSpeichern({ name: 'Möbel Krause', status: 'AKTIV' }), g = Aktionen.firmaSpeichern({ name: 'Küchen Nord', status: 'AKTIV' });
    Aktionen.crmSpeichern('adressen', { firmaId: f, art: 'HAUPTSITZ', strasse: 'Hauptstr. 1', plz: '42853', ort: 'Remscheid' });
    Aktionen.crmSpeichern('preise', { firmaId: f, bezeichnung: 'Montage', betragCent: 25000, einheit: 'je Tour' });
    Aktionen.crmSpeichern('preise', { firmaId: f, bezeichnung: 'Lieferung', betragCent: 9000 });
    FuhrparkAktionen.speichern({ kennzeichen: 'RS-AT 100', status: 'AKTIV' });
    const a = PersonalAktionen.speichern({ name: 'Ali Demir', rolle: 'FAHRER', wochenstunden: 40, eintritt: '2024-03-01', urlaubstage: 28 });
    const zr = zeitraum('LETZTER_MONAT'), t = { kennzeichen: 'RS-AT 100', auftraggeber: '', fahrerId: a };
    TourAktionen.neu(zr.von, { ...t, auftraggeberId: f, einsatzart: 'Montage' }); TourAktionen.neu(plusTage(zr.von, 1), { ...t, auftraggeberId: f, einsatzart: 'Montage' });
    TourAktionen.neu(plusTage(zr.von, 2), { ...t, auftraggeberId: f, einsatzart: 'Lieferung' }); TourAktionen.neu(plusTage(zr.von, 3), { ...t, auftraggeberId: g, einsatzart: 'Lieferung' });
    TourAktionen.neu(plusTage(zr.von, 4), { ...t, auftraggeberId: f, einsatzart: 'Montage', fahrerId: '' }); // nicht gefahren
    return { f, g, a };
  });
  await s.locator('[data-monatsabrechnung]').first().click();
  await s.locator('#sammel-liste', { hasText: 'Küchen Nord' }).waitFor();
  assert.ok(await s.locator('#sammel-liste', { hasText: 'ohne Preis' }).count(), 'fehlender Preis wird gemeldet');
  await s.locator('#sammel-los').click();
  const r = await s.evaluate(f => { const b = alle('belege').filter(x => x.art === 'RECHNUNG'), k = b.find(x => x.firmaId === f);
    return { n: b.length, pos: JSON.parse(k.positionen).map(p => [p.bez, p.menge, p.einzelCent]), abgerechnet: alle('touren').filter(t => t.belegId).length, status: k.status }; }, ids.f);
  assert.deepEqual(r, { n: 2, pos: [['Lieferung', 1, 9000], ['Montage', 2, 25000]], abgerechnet: 4, status: 'ENTWURF' });
  // zweiter Lauf: nichts mehr offen
  await s.locator('[data-monatsabrechnung]').first().click();
  await s.locator('#sammel-liste', { hasText: 'alles abgerechnet' }).waitFor();
  await s.evaluate(() => $('#dialog').close());
  // Brief an Mitarbeiter: Beschäftigungsbescheinigung mit Daten
  await s.evaluate(id => { window.__druck = ''; window.druckeHtml = h => { window.__druck = h; }; location.hash = '#/mitarbeiter/' + id; }, ids.a);
  await s.locator('[data-brief="BESCHAEFTIGUNG"]').click();
  const text = await s.locator('#brief-form [name=text]').inputValue();
  assert.ok(text.includes('Ali Demir') && text.includes('01.03.2024') && text.includes('40 Stunden'), text);
  await s.locator('#brief-form button.voll').click();
  const h = await s.evaluate(() => window.__druck);
  assert.ok(h.includes('Bescheinigung über das Beschäftigungsverhältnis') && h.includes('Ali Demir'));
  assert.ok(await s.evaluate(id => alle('personaleintraege').some(e => e.mitarbeiterId === id && e.text.startsWith('Brief:')), ids.a));
  // Urlaubsbestätigung: Tage und Rest
  await s.evaluate(() => $('#dialog').close());
  await s.evaluate(id => { const id2 = uuid(); PersonalAktionen.abwesenheit({ id: id2, mitarbeiterId: id, von: '2026-12-14', bis: '2026-12-18', grund: 'Urlaub' }); briefDialog({ vorlage: 'URLAUB', abwesenheitId: id2, mitarbeiterId: id }); }, ids.a);
  const ut = await s.locator('#brief-form [name=text]').inputValue();
  assert.ok(ut.includes('14.12.2026') && ut.includes('18.12.2026') && ut.includes('(5 Arbeitstage)'), ut);
  // Brief an Firma: Anschrift und Preise
  await s.evaluate(() => $('#dialog').close());
  await s.evaluate(f => briefDialog({ vorlage: 'PREISE', firmaId: f }), ids.f);
  assert.ok((await s.locator('#brief-form [name=anschrift]').inputValue()).includes('42853 Remscheid'));
  assert.ok((await s.locator('#brief-form [name=text]').inputValue()).includes('Montage: 250,00'));
  await s.close();
});

test('Mitarbeiter zusammenführen: Doppelte finden, Touren, Zeiten, Lohn und Dokumente umhängen', async () => {
  const s = await geraet(null, { bereich: 'personal' });
  const ids = await s.evaluate(async () => {
    const a = PersonalAktionen.speichern({ name: 'Fatih', rolle: 'FAHRER', telefon: '0171 1' }), c = PersonalAktionen.speichern({ name: 'Cem Ott', rolle: 'BEIFAHRER' });
    FuhrparkAktionen.speichern({ kennzeichen: 'RS-AT 100', status: 'AKTIV' });
    TourAktionen.neu(heute(), { kennzeichen: 'RS-AT 100', auftraggeber: 'X', fahrerId: c, beifahrerIds: a });
    ZeitAktionen.speichern({ mitarbeiterId: a, datum: heute(), beginn: '07:00', ende: '15:00', pauseMin: 30 });
    await personalImportieren(JSON.stringify({ mitarbeiter: [{ name: 'Fatih Akyüz', personalnummer: '17', geburtstag: '1990-01-02' }], lohn: [{ personalnummer: '17', monat: '2026-10', bruttoCent: 90000 }],
      dokumente: [{ personalnummer: '17', art: 'Lohnabrechnung', name: 'Oktober 2026.pdf', mimeType: 'application/pdf', base64: btoa('%PDF-1') }] }), { 0: 'NEU' });
    bereichWechseln('personal');
    return { a, b: maZumNamen('Fatih Akyüz').id };
  });
  await s.locator('[data-doppelte]', { hasText: 'Doppelte (1)' }).click();
  await s.locator('[data-doppelt]').click();
  await s.locator('#zus-form [name=bleibt][value="' + ids.a + '"]').waitFor();
  assert.equal(await s.locator('#zus-form [name=bleibt]:checked').getAttribute('value'), ids.a, 'Eintrag mit Touren bleibt');
  assert.equal(await s.locator('#zus-form [name=name]').inputValue(), 'Fatih Akyüz');
  await s.locator('#zus-form button.voll').click();
  await s.locator('#ja').click();
  await s.locator('#toast', { hasText: 'Zusammengeführt' }).waitFor();
  const r = await s.evaluate(id => { const m = maVon(id); return { n: alle('mitarbeiter').length, name: m.name, nr: m.personalnummer, geb: m.geburtstag, tel: m.telefon, alias: m.aliase,
    touren: alle('touren').filter(t => teamVon(t).includes(id)).length, zeiten: alle('zeiten').filter(z => z.mitarbeiterId === id).length,
    lohn: personalakteVon(id).filter(e => e.art === 'LOHN').length, doks: anlagenVon(id).map(a => a.anzeigename) }; }, ids.a);
  assert.deepEqual(r, { n: 2, name: 'Fatih Akyüz', nr: '17', geb: '1990-01-02', tel: '0171 1', alias: 'Fatih', touren: 1, zeiten: 1, lohn: 1, doks: ['Lohnabrechnung – Oktober 2026.pdf'] });
  assert.ok(await s.evaluate(id => !!Dateien.hole(anlagenVon(id)[0].id), ids.a));
  assert.equal(await s.locator('[data-doppelte]').count(), 0);
  await s.close();
});

test('Tour senden: WhatsApp an das ganze Team auf einmal', async () => {
  const s = await geraet(null, { bereich: 'touren' });
  await s.evaluate(() => {
    const a = PersonalAktionen.speichern({ name: 'Ali Demir', rolle: 'FAHRER', telefon: '0171 1111' }), b = PersonalAktionen.speichern({ name: 'Ben Ott', rolle: 'BEIFAHRER', telefon: '0172 2222' });
    FuhrparkAktionen.speichern({ kennzeichen: 'RS-AT 100', status: 'AKTIV' });
    TourAktionen.neu(heute(), { kennzeichen: 'RS-AT 100', auftraggeber: 'Möbel Krause', name: 'Küche', fahrerId: a, beifahrerIds: b });
    window.__links = []; window.oeffneLink = u => window.__links.push(u); window.oeffne = u => window.__links.push(u);
    zeichne();
  });
  await s.locator('[data-tour-akt="senden"]').first().click();
  assert.equal(await s.locator('#dialog[open]').count(), 0, 'kein Zwischenschritt');
  await s.evaluate(() => tourSenden(alle('touren')[0]));
  await s.locator('#tp-wa-alle', { hasText: 'an alle 2' }).click();
  await s.locator('#tp-sms-alle').click();
  const l = await s.evaluate(() => window.__links);
  assert.equal(l[0], l[1], 'Knopf an der Tour = WhatsApp an alle'); l.shift();
  assert.ok(l[0].startsWith('https://wa.me/?text=') && decodeURIComponent(l[0]).includes('Fahrer: Ali Demir – 0171 1111') && decodeURIComponent(l[0]).includes('Beifahrer: Ben Ott'), l[0]);
  assert.ok(l[1].startsWith('sms:+491711111,+491722222?body='), l[1]);
  await s.close();
});

test('Fristen-Matrix und Ausrüstung: fällige Fristen, Ausgabe und Rückgabe', async () => {
  const s = await geraet(null, { bereich: 'personal', viewport: { width: 390, height: 844 } });
  const id = await s.evaluate(() => {
    const a = PersonalAktionen.speichern({ name: 'Ali Demir', rolle: 'FAHRER', fuehrerscheinBis: plusTage(heute(), -3), modul95Bis: plusTage(heute(), 10), unterweisungBis: plusTage(heute(), 200) });
    PersonalAktionen.speichern({ name: 'Ben Ott', rolle: 'BEIFAHRER', unterweisungBis: plusTage(heute(), 300) });
    PersonalAktionen.eintrag({ mitarbeiterId: a, art: 'AUSRUESTUNG', datum: '2026-01-05', text: 'Tankkarte DKV 12' });
    PersonalAktionen.eintrag({ mitarbeiterId: a, art: 'AUSRUESTUNG', datum: '2026-01-05', text: 'Schlüssel Halle' });
    bereichWechseln('personal'); return a;
  });
  await s.locator('.werkzeug-mehr').click();
  await s.locator('[data-fristen-matrix]').click();
  await s.locator('.matrix tbody tr').first().waitFor();
  assert.equal(await s.locator('.matrix tbody tr').first().locator('th').textContent(), 'Ali Demir', 'Überfällige zuerst');
  assert.equal(await s.locator('.matrix td.rot').count(), 1); assert.equal(await s.locator('.matrix td.gelb').count(), 1);
  assert.ok(await s.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Seite scrollt nicht seitlich');
  await s.locator('#matrix-faellig').check();
  await s.locator('.matrix tbody tr').first().waitFor();
  assert.equal(await s.locator('.matrix tbody tr').count(), 1);
  await s.evaluate(() => $('#dialog').close());
  if (!(await s.locator('[data-ausruestung]').isVisible())) await s.locator('.werkzeug-mehr').click();
  await s.locator('[data-ausruestung]', { hasText: '(2)' }).click();
  await s.locator('[data-aus-zurueck]').first().click();
  await s.locator('#toast', { hasText: 'zurückgegeben' }).waitFor();
  assert.deepEqual(await s.evaluate(id => ausruestungOffen(id).map(e => e.text), id), ['Schlüssel Halle']);
  await s.evaluate(() => { window.__druck = ''; window.druckeHtml = h => { window.__druck = h; }; });
  await s.locator('[data-aus-druck]').click();
  assert.ok(await s.evaluate(() => window.__druck.includes('Tankkarte DKV 12') && window.__druck.includes('Unterschrift Mitarbeiter')));
  await s.close();
});

test('Fahrzeuge zusammenführen: Touren, Akte, Schäden und Alias', async () => {
  const s = await geraet(null, { bereich: 'fuhrpark' });
  const ids = await s.evaluate(() => {
    const a = FuhrparkAktionen.speichern({ kennzeichen: 'RS-AT 100', status: 'AKTIV', bezeichnung: 'Sprinter 1', kmStand: 120000 });
    const b = FuhrparkAktionen.speichern({ kennzeichen: 'RSAT100', status: 'AKTIV', fin: 'WDB123', huBis: '2027-05-31', kmStand: 125000 });
    TourAktionen.neu(heute(), { kennzeichen: 'RS-AT 100', auftraggeber: 'X' }); TourAktionen.neu(plusTage(heute(), 1), { kennzeichen: 'RS-AT 100', auftraggeber: 'X' });
    TourAktionen.neu(plusTage(heute(), 2), { kennzeichen: 'RSAT100', auftraggeber: 'Y' });
    FuhrparkAktionen.eintragSpeichern({ fahrzeugId: b, art: 'WARTUNG', datum: heute(), text: 'Ölwechsel' });
    bereichWechseln('fuhrpark'); return { a, b };
  });
  await s.locator('[data-fz-doppelte]', { hasText: '(1)' }).click();
  await s.locator('[data-fz-doppelt]').click();
  assert.equal(await s.locator('#fzz-form [name=bleibt]:checked').getAttribute('value'), ids.a);
  assert.equal(await s.locator('#fzz-form [name=kz]').inputValue(), 'RS-AT 100');
  await s.locator('#fzz-form button.voll').click();
  await s.locator('#ja').click();
  await s.locator('#toast', { hasText: 'Zusammengeführt' }).waitFor();
  const r = await s.evaluate(id => { const z = S.fahrzeuge[id]; return { n: alle('fahrzeuge').length, kz: z.kennzeichen, alias: z.aliase, fin: z.fin, hu: z.huBis, km: z.kmStand,
    touren: tourenVonFahrzeug(z).length, akte: eintraegeVon(id).length, perAlias: fahrzeugZumKz('RSAT 100').id === id }; }, ids.a);
  assert.deepEqual(r, { n: 1, kz: 'RS-AT 100', alias: '', fin: 'WDB123', hu: '2027-05-31', km: 125000, touren: 3, akte: 1, perAlias: true });
  await s.close();
});

test('Gutschrift mindert Umsatz und offenen Betrag; Finanzen erst ab Stichtag', async () => {
  const s = await geraet(null, { bereich: 'finanzen' });
  const ids = await s.evaluate(() => {
    const f = Aktionen.firmaSpeichern({ name: 'Möbel Krause', status: 'AKTIV' });
    const r = BelegAktionen.neu('RECHNUNG', f, { positionen: JSON.stringify([{ bez: 'Montage', menge: 2, einzelCent: 25000 }]) });
    BelegAktionen.speichern({ ...S.belege[r], status: 'OFFEN' });
    const alt = BelegAktionen.neu('RECHNUNG', f, { datum: '2020-01-10', positionen: JSON.stringify([{ bez: 'Alt', menge: 1, einzelCent: 99900 }]) });
    BelegAktionen.speichern({ ...S.belege[alt], status: 'BEZAHLT' });
    return { f, r };
  });
  await s.evaluate(id => { location.hash = '#/beleg/' + id; }, ids.r);
  await s.locator('[data-beleg-akt="gutschrift"]').click();
  await s.locator('#gs-teil').click();
  await s.waitForFunction(() => alle('belege').some(b => b.art === 'GUTSCHRIFT'));
  const gid = await s.evaluate(() => { const g = alle('belege').find(b => b.art === 'GUTSCHRIFT'); BelegAktionen.speichern({ ...g, positionen: JSON.stringify([{ bez: 'Preisnachlass', menge: 1, einzelCent: 10000 }]) }); return g.id; });
  const vorher = await s.evaluate(r => offenCent(S.belege[r]), ids.r);
  assert.equal(vorher, 59500, 'Entwurf zählt noch nicht');
  await s.evaluate(g => { location.hash = '#/beleg/' + g; }, gid);
  await s.locator('[data-beleg-akt="festschreiben"]').click();
  const r = await s.evaluate(([r, g]) => ({ nr: S.belege[g].nummer.startsWith('G'), offen: offenCent(S.belege[r]), jahr: finanzKennzahlen().jahr, html: belegHtml(S.belege[g]).includes('Zu Rechnung') }), [ids.r, gid]);
  assert.deepEqual(r, { nr: true, offen: 59500 - 11900, jahr: 50000 - 10000 + (new Date().getFullYear() === 2020 ? 99900 : 0), html: true });
  // Rest gutschreiben: Rechnung gilt als erledigt
  await s.evaluate(r => { const g = BelegAktionen.gutschrift(S.belege[r], false); BelegAktionen.speichern({ ...S.belege[g], status: 'OFFEN', positionen: JSON.stringify([{ bez: 'Rest', menge: 1, einzelCent: 40000 }]) }); }, ids.r);
  assert.deepEqual(await s.evaluate(r => [offenCent(S.belege[r]), istRechnungOffen(S.belege[r]), belegMarke(S.belege[r])[0]], ids.r), [0, false, 'Gutgeschrieben']);
  // Stichtag: alte Rechnung von 2020 fällt aus der Statistik
  const st = await s.evaluate(() => { const ohne = statistikRechnen('rechnungen', 'netto', 'auftraggeber', null, '2019-01-01', '2099-12-31'); S.einstellungen.finanzAb = '2021-01-01';
    const mit = statistikRechnen('rechnungen', 'netto', 'auftraggeber', null, '2019-01-01', '2099-12-31'); return [ohne.zeilen, mit.zeilen, finanzKennzahlen().jahr]; });
  assert.deepEqual(st.slice(0, 2), [4, 3], 'Rechnung von 2020 zählt ab Stichtag nicht mehr');
  await s.close();
});

test('Bestehende Rechnungen korrigieren: Zahlung zurücknehmen, Korrekturrechnung ersetzt die alte, Storno aufheben, Gutschrift und Angebot berichtigen', async () => {
  const s = await geraet(null, { bereich: 'finanzen' });
  const ids = await s.evaluate(() => {
    const f = Aktionen.firmaSpeichern({ name: 'Möbel Krause', status: 'AKTIV' });
    const pos = JSON.stringify([{ bez: 'Montage', menge: 2, einzelCent: 25000 }]);
    const r1 = BelegAktionen.neu('RECHNUNG', f, { positionen: pos }); BelegAktionen.speichern({ ...S.belege[r1], status: 'OFFEN' });
    BelegAktionen.zahlung(S.belege[r1], 59500, heute());
    const r2 = BelegAktionen.neu('RECHNUNG', f, { positionen: pos, bestellnummer: 'B-77' }); BelegAktionen.speichern({ ...S.belege[r2], status: 'OFFEN' });
    BelegAktionen.zahlung(S.belege[r2], 10000, '2026-11-01');
    const t = uuid(); merke('touren', { id: t, datum: heute(), auftraggeberId: f, belegId: r2, geaendertAm: jetztIso() });
    const r3 = BelegAktionen.neu('RECHNUNG', f, { positionen: pos }); BelegAktionen.speichern({ ...S.belege[r3], status: 'STORNIERT' });
    const g = BelegAktionen.gutschrift(S.belege[r3], true); BelegAktionen.speichern({ ...S.belege[g], status: 'BEZAHLT', bezahltCent: 59500, bezahltAm: heute() });
    const a = BelegAktionen.neu('ANGEBOT', f, { positionen: pos }); BelegAktionen.speichern({ ...S.belege[a], status: 'ABGELEHNT' });
    const r4 = BelegAktionen.neu('RECHNUNG', f, { positionen: pos }); BelegAktionen.speichern({ ...S.belege[r4], status: 'OFFEN' });
    return { f, r1, r2, r3, r4, g, a, t };
  });
  const oeffne = id => s.evaluate(id => { location.hash = '#/beleg/' + id; }, id);
  // 1) versehentlich bezahlt → wieder unbezahlt (gesperrte, bezahlte Rechnung)
  assert.equal(await s.evaluate(id => S.belege[id].status, ids.r1), 'BEZAHLT');
  await oeffne(ids.r1);
  await s.locator('[data-beleg-akt="korrigieren"]').click();
  await s.locator('#kr-status').click();
  await s.locator('[data-br-betrag="0"]').click();
  await s.locator('#berichtigen-form [name="grund"]').fill('Zahlung gehörte zu anderer Rechnung');
  await s.locator('#br-ok').click();
  const r1 = await s.evaluate(id => ({ st: S.belege[id].status, c: S.belege[id].bezahltCent, am: S.belege[id].bezahltAm, offen: istRechnungOffen(S.belege[id]),
    verlauf: alle('aktivitaeten').some(a => /berichtigt: Status Bezahlt → Offen, bezahlt .* → 0,00.*anderer Rechnung/.test(a.text)) }), ids.r1);
  assert.deepEqual(r1, { st: 'OFFEN', c: 0, am: null, offen: true, verlauf: true });
  await s.locator('#detail [data-beleg-akt="zahlung"]').waitFor();
  // 2) Korrekturrechnung: übernimmt alles, ersetzt die alte erst beim Versenden
  await oeffne(ids.r2);
  await s.locator('[data-beleg-akt="korrigieren"]').click();
  await s.locator('#kr-neu').click();
  await s.waitForFunction(alt => location.hash.startsWith('#/beleg/') && !location.hash.endsWith(alt), ids.r2);
  const kid = await s.evaluate(() => location.hash.split('/').pop());
  const entwurf = await s.evaluate(([k, r]) => ({ von: S.belege[k].korrekturVon === r, pos: S.belege[k].positionen === S.belege[r].positionen, bnr: S.belege[k].bestellnummer, alt: S.belege[r].status, nr: S.belege[k].nummer !== S.belege[r].nummer }), [kid, ids.r2]);
  assert.deepEqual(entwurf, { von: true, pos: true, bnr: 'B-77', alt: 'TEILBEZAHLT', nr: true }, 'alte Rechnung gilt weiter, solange die Korrektur Entwurf ist');
  await s.locator('#beleg-form [name="einzel"]').first().fill('200,00');
  await s.locator('#beleg-speichern').click();
  await s.waitForFunction(k => positionen(S.belege[k])[0].einzelCent === 20000, kid); // erst gespeichert, dann festschreiben (auf langsamen Rechnern sonst alter Preis)
  await s.locator('[data-beleg-akt="festschreiben"]').click();
  const k = await s.evaluate(([k, r, t]) => ({ alt: S.belege[r].status, ersetzt: S.belege[r].ersetztDurch === k, st: S.belege[k].status, bez: S.belege[k].bezahltCent, offen: offenCent(S.belege[k]),
    tour: S.touren[t].belegId === k, umsatz: alle('belege').filter(zaehltUmsatz).filter(b => b.id === r || b.id === k).map(b => b.id === k),
    xml: eRechnungXml(S.belege[k]), html: belegHtml(S.belege[k]).includes('Ersetzt Rechnung'), nrAlt: S.belege[r].nummer,
    verlauf: alle('aktivitaeten').some(a => a.text.includes('durch Korrekturrechnung')) }), [kid, ids.r2, ids.t]);
  assert.deepEqual({ ...k, xml: undefined }, { alt: 'STORNIERT', ersetzt: true, st: 'TEILBEZAHLT', bez: 10000, offen: 47600 - 10000, tour: true, umsatz: [true], xml: undefined, html: true, nrAlt: k.nrAlt, verlauf: true });
  assert.ok(k.xml.includes('<ram:TypeCode>384</ram:TypeCode>') && k.xml.includes(`<ram:IssuerAssignedID>${k.nrAlt}</ram:IssuerAssignedID>`), 'E-Rechnung: korrigierte Rechnung (384) mit Verweis');
  // ersetzte Rechnung kann nicht wieder gültig gemacht werden, solange die Korrektur gilt
  await oeffne(ids.r2);
  await s.locator('[data-beleg-akt="korrigieren"]').click();
  assert.equal(await s.locator('#kr-neu').count(), 1);
  await s.locator('#kr-status').click();
  assert.equal(await s.locator('#berichtigen-form [name="status"]').isDisabled(), true);
  await s.locator('#berichtigen-form [data-zu]').click();
  // 3) Storno aus Versehen → aufheben
  await oeffne(ids.r3);
  await s.locator('[data-beleg-akt="korrigieren"]').click();
  await s.locator('#kr-status').click();
  await s.locator('#berichtigen-form [name="status"]').selectOption('GUELTIG');
  await s.locator('#br-ok').click();
  assert.equal(await s.evaluate(id => S.belege[id].status, ids.r3), 'OFFEN');
  // 4) Gutschrift: „ausgezahlt“ zurücknehmen; Angebot wieder offen; versendete Rechnung zurück auf Entwurf
  for (const [id, wert, soll] of [[ids.g, 'OFFEN', 'OFFEN'], [ids.a, 'OFFEN', 'OFFEN'], [ids.r4, 'ENTWURF', 'ENTWURF']]) {
    await oeffne(id);
    await s.locator(id === ids.r4 ? '[data-beleg-akt="korrigieren"]' : '[data-beleg-akt="berichtigen"]').click();
    if (id === ids.r4) await s.locator('#kr-status').click();
    await s.locator('#berichtigen-form [name="status"]').selectOption(wert);
    await s.locator('#br-ok').click();
    assert.equal(await s.evaluate(id => S.belege[id].status, id), soll);
  }
  assert.equal(await s.evaluate(id => S.belege[id].bezahltCent, ids.g), 0);
  // Nummer eines gelöschten Entwurfs wird wieder vergeben (auch wenn danach schon weitere Rechnungen existieren)
  const wieder = await s.evaluate(() => {
    const f = alle('belege')[0].firmaId, e1 = BelegAktionen.neu('RECHNUNG', f), e2 = BelegAktionen.neu('RECHNUNG', f);
    const n1 = S.belege[e1].nummer; BelegAktionen.loeschen(S.belege[e1]);
    const e3 = BelegAktionen.neu('RECHNUNG', f);
    return [S.belege[e3].nummer === n1, S.belege[e2].nummer > n1, new Set(alle('belege').map(b => b.nummer)).size === alle('belege').length];
  });
  assert.deepEqual(wieder, [true, true, true]);
  // offizielle Prüfung der korrigierten Rechnung (wie im E-Rechnungs-Test)
  const regeln = process.env.ERECHNUNG_REGELN;
  if (regeln) {
    const xml = await s.evaluate(k => {
      S.einstellungen.rechnung = { ...rechnungsDaten(), name: 'Ayse Transport UG', strasse: 'Musterstraße 1', plzOrt: '12345 Musterstadt', telefon: '0123 456789', email: 'info@example.de',
        ustId: 'DE987654321', steuer: '126/5000/1234', iban: 'DE89 3704 0044 0532 0130 00', bic: 'COBADEFFXXX', bank: 'Musterbank' };
      const f = S.belege[k].firmaId; S.auftraggeber[f] = { ...S.auftraggeber[f], email: 'rechnung@example.com' };
      Aktionen.crmSpeichern('adressen', { firmaId: f, art: 'RECHNUNG', strasse: 'Industriestraße 5', plz: '47803', ort: 'Krefeld' });
      return eRechnungXml(S.belege[k]);
    }, kid);
    const fs = require('node:fs'), os = require('node:os'), { execFileSync } = require('node:child_process');
    const datei = path.join(os.tmpdir(), 'xrechnung-korrektur.xml'); fs.writeFileSync(datei, xml);
    let aus; try { aus = execFileSync('python3', [path.join(__dirname, '../../tools/erechnung-pruefen.py'), datei, regeln], { encoding: 'utf8' }); } catch (e) { aus = e.stdout; }
    const erg = JSON.parse(aus);
    assert.deepEqual({ xsd: erg.xsd, fehler: erg.fehler }, { xsd: [], fehler: [] }, aus);
  }
  await s.close();
});

test('Lieferavis, Statuslink für den Endkunden und Bewertung nach der Lieferung', async () => {
  const cloud = await starteCloud();
  const a = await geraet(cloud.url, { bereich: 'touren' });
  await melde(a, PIN, true); await aktuell(a);
  const tid = await a.evaluate(() => {
    FuhrparkAktionen.speichern({ kennzeichen: 'RS-AT 100', status: 'AKTIV' });
    const m = PersonalAktionen.speichern({ name: 'Ali Demir', rolle: 'FAHRER' }), f = Aktionen.firmaSpeichern({ name: 'Möbel Krause', status: 'AKTIV' });
    window.__links = []; window.oeffne = u => window.__links.push(u); window.oeffneLink = u => window.__links.push(u);
    return TourAktionen.neu(heute(), { kennzeichen: 'RS-AT 100', auftraggeberId: f, auftraggeber: '', name: 'Küche', fahrerId: m });
  });
  await a.evaluate(id => tourKundeDialog(S.touren[id]), tid);
  await a.locator('#kunde-form [name=kundeName]').fill('Frau Meier');
  await a.locator('#kunde-form [name=kundeTelefon]').fill('0171 5555');
  await a.locator('#kunde-form [name=zeitfenster]').fill('8–12 Uhr');
  await a.locator('[data-avis="sms"]').click();
  const sms = decodeURIComponent(await a.evaluate(() => window.__links[0]));
  assert.ok(sms.startsWith('sms:+491715555') && sms.includes('Guten Tag Frau Meier') && sms.includes('zwischen 8 und 12 Uhr') && sms.includes('status.html?s='), sms);
  const link = sms.match(/https?:\S+status\.html\S+/)[0];
  await abgleich(a);
  // Kunde öffnet den Link (ohne App, ohne Anmeldung)
  const k = await (await browser.newContext({ serviceWorkers: 'block', viewport: { width: 390, height: 844 } })).newPage();
  await k.goto(link);
  await k.locator('.status', { hasText: 'Geplant' }).waitFor();
  assert.ok(await k.locator('dd', { hasText: '8–12 Uhr' }).count());
  assert.equal(await k.locator('[data-stern]').count(), 0, 'vor der Lieferung keine Bewertung');
  assert.ok(await k.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  // Abgeliefert → Kunde bewertet
  await a.evaluate(id => merke('touren', { ...S.touren[id], status: 'ERLEDIGT', abgeliefertAm: jetztIso(), empfaenger: 'Meier', geaendertAm: jetztIso() }), tid);
  await abgleich(a);
  await k.reload();
  await k.locator('.status', { hasText: 'Zugestellt' }).waitFor();
  await k.locator('[data-stern="4"]').click();
  await k.locator('#text').fill('Sehr freundlich');
  await k.locator('#senden').click();
  await k.locator('text=Vielen Dank für Ihre Bewertung').waitFor();
  await abgleich(a);
  assert.deepEqual(await a.evaluate(id => [S.touren[id].bewertung, S.touren[id].bewertungText], tid), [4, 'Sehr freundlich']);
  const st = await a.evaluate(() => [...statistikRechnen('touren', 'bewertung', 'auftraggeber', null, '2000-01-01', '2099-12-31').ergebnis.values()].map(e => e.wert));
  assert.deepEqual(st, [4]);
  await k.context().close(); await a.context().close(); await cloud.schliessen();
});

test('Stoppliste (optional): mehrere Kunden, Route, Avis je Stopp, Fahrer hakt ab, Kunde sieht Reihenfolge', async () => {
  const cloud = await starteCloud();
  const a = await geraet(cloud.url, { bereich: 'touren' });
  await melde(a, PIN, true); await aktuell(a);
  const tid = await a.evaluate(() => {
    FuhrparkAktionen.speichern({ kennzeichen: 'RS-AT 100', status: 'AKTIV' });
    const m = PersonalAktionen.speichern({ name: 'Ali Demir', rolle: 'FAHRER' });
    window.__links = []; window.oeffne = u => window.__links.push(u); window.oeffneLink = u => window.__links.push(u);
    const t = TourAktionen.neu(heute(), { kennzeichen: 'RS-AT 100', auftraggeber: 'Möbel Krause', name: 'Sammeltour', fahrerId: m });
    merke('touren', { ...S.touren[t], kundeName: 'Frau Meier', kundeTelefon: '0171 1', kundeAdresse: 'Hauptstr. 1, Remscheid', geaendertAm: jetztIso() });
    return t;
  });
  // Ohne Stopps: Tourzeile unverändert
  assert.equal(await a.locator('[data-tour-stopps]').count(), 0);
  await a.evaluate(id => tourMehr(S.touren[id]), tid);
  await a.locator('[data-tm="stopps"]').click();
  await a.locator('#stopp-liste .stopp', { hasText: 'Frau Meier' }).waitFor(); // Endkunde wird Stopp 1
  for (const [name, adr, tel] of [['Herr Kaya', 'Bahnhofstr. 5, Solingen', '0172 2'], ['Lager Nord', 'Industriestr. 9, Wuppertal', '']]) {
    await a.locator('#stopp-neu').click();
    await a.locator('#stopp-form [name=kundeName]').fill(name); await a.locator('#stopp-form [name=adresse]').fill(adr); await a.locator('#stopp-form [name=kundeTelefon]').fill(tel);
    if (name === 'Lager Nord') await a.locator('#stopp-form [name=art]').selectOption('ABHOLUNG');
    await a.locator('#stopp-form button.voll').click();
  }
  await a.locator('#stopp-liste .stopp').nth(2).waitFor();
  // Reihenfolge ändern: Lager Nord nach oben
  await a.locator('#stopp-liste .stopp').nth(2).locator('[data-stopp-akt="hoch"]').click();
  assert.deepEqual(await a.evaluate(id => stoppsVon(id).map(s => s.kundeName), tid), ['Frau Meier', 'Lager Nord', 'Herr Kaya']);
  await a.locator('#stopp-route').click();
  const route = decodeURIComponent(await a.evaluate(() => window.__links.pop()));
  assert.ok(route.includes('destination=Bahnhofstr. 5') && route.includes('waypoints=Hauptstr. 1, Remscheid|Industriestr. 9'), route);
  // Avis an Herrn Kaya per SMS mit eigenem Statuslink
  await a.locator('#stopp-liste .stopp').nth(2).locator('[data-stopp-akt="bearbeiten"]').click();
  await a.locator('[data-savis="sms"]').click();
  const sms = decodeURIComponent(await a.evaluate(() => window.__links.pop()));
  assert.ok(sms.startsWith('sms:+491722') && sms.includes('Guten Tag Herr Kaya') && sms.includes('status.html?s='), sms);
  const link = sms.match(/https?:\S+status\.html\S+/)[0];
  assert.ok((await a.evaluate(id => tourNachricht(S.touren[id]), tid)).includes('2. Abholung: Lager Nord – Industriestr. 9, Wuppertal'));
  await a.evaluate(id => tourStatusSetzen(S.touren[id], 'UNTERWEGS'), tid);
  await abgleich(a);
  const k = await (await browser.newContext({ serviceWorkers: 'block', viewport: { width: 390, height: 844 } })).newPage();
  await k.goto(link);
  await k.locator('text=Noch 2 Stopps vor Ihnen.').waitFor();
  // Fahrer-Ansicht: Stopps abhaken
  await a.evaluate(id => { if ($('#dialog').open) $('#dialog').close(); S.einstellungen.ichId = S.touren[id].fahrerId; bereichWechseln('fahrer'); }, tid);
  await a.locator('.fahrer-stopps .stopp.naechster', { hasText: 'Frau Meier' }).waitFor();
  a.on('dialog', d => d.accept('Meier'));
  await a.locator('.fahrer-stopps .stopp.naechster [data-fs="fertig"]').click();
  await a.locator('.fahrer-stopps .stopp.naechster', { hasText: 'Lager Nord' }).waitFor();
  await abgleich(a);
  await k.reload(); await k.locator('text=Noch 1 Stopp vor Ihnen.').waitFor();
  assert.ok(await k.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  await k.context().close(); await a.context().close(); await cloud.schliessen();
});

test('Tourenplanung: unbekannter Name legt niemanden an, sondern fragt „Meintest du …?“', async () => {
  const s = await geraet(null, { bereich: 'touren' });
  const tid = await s.evaluate(() => {
    FuhrparkAktionen.speichern({ kennzeichen: 'RS-AT 100', status: 'AKTIV' });
    PersonalAktionen.speichern({ name: 'Fatih Akyüz', rolle: 'FAHRER' }); PersonalAktionen.speichern({ name: 'Mehmet Eybilen', rolle: 'FAHRER' });
    return TourAktionen.neu(heute(), { kennzeichen: 'RS-AT 100', auftraggeber: 'X' });
  });
  const feld = s.locator(`[data-tour-zeile="${tid}"] [data-feld="fahrer"]`);
  await feld.fill('Fatik'); await feld.press('Enter'); await feld.blur();
  await s.locator('#dialog h2', { hasText: '„Fatik“ gibt es nicht im Personal' }).waitFor();
  assert.deepEqual(await s.locator('[data-meintest]').allTextContents().then(l => l.map(x => x.split(' ').slice(0, 2).join(' '))), ['Fatih Akyüz']);
  assert.equal(await s.evaluate(() => alle('mitarbeiter').length), 2, 'niemand angelegt');
  await s.locator('[data-meintest]').click();
  assert.equal(await s.evaluate(id => maName(S.touren[id].fahrerId), tid), 'Fatih Akyüz');
  // Nur der Nachname reicht als Vorschlag; völlig Unbekanntes: nur Hinweis
  assert.deepEqual(await s.evaluate(() => aehnlicheMitarbeiter('eybilen').map(m => m.name)), ['Mehmet Eybilen']);
  assert.deepEqual(await s.evaluate(() => aehnlicheMitarbeiter('Zoltan').length), 0);
  await s.close();
});

test('Rollkarte (PDF) → Tour mit Stoppliste: Vorschau, Korrektur, Übernahme', async () => {
  // Rollkarte wie vom Auftraggeber (Tabelle mit Kopfzeile, zweizeilige Adressen, Zeitfenster mit Ankunft, Routenzeile)
  const p = await (await browser.newContext()).newPage();
  const z = (n, a, adr, tel, zf, eta, b) => `<tr><td>${n}</td><td>${a}</td><td>${adr}</td><td>${tel}</td><td>${zf}<br>→ ${eta} Uhr</td><td>${b} €</td><td></td></tr>`;
  await p.setContent(`<style>body{font:12px Arial}td,th{padding:6px 10px;text-align:left;vertical-align:middle}td:nth-child(3){width:170px}</style>
    <h3>Rollkarte Testfiliale &nbsp; 20.11.2026</h3><table><tr><th>Name</th><th>Auftragsnummer</th><th>Adresse</th><th>Telefonnummer</th><th>Zeitfenster</th><th>Summe/Betrag</th><th>Erledigt</th></tr>
    ${z('Meier', '10700001', 'Hauptstr. 1, 42853 Remscheid', '0171111111', '10-13 Uhr', '10:06', '130,00')}
    ${z('Kaya', '10700002', 'Lange Industriestraße 120, 42719 Solingen-Wald', '0172222222', '12-16 Uhr', '11:40', '275,00')}
    ${z('Ott', '10700003', 'Weg 3, 4799 Krefeld', '', '10-16:30 Uhr', '13:33', '154,00')}</table>
    <p>559,00 €</p><p>Route: Abfahrt 09:30 Uhr Filiale → 3 Kunden → Ankunft ca. 15:02 Uhr · 120 km · Fahrzeit 02:32 h</p>`);
  const pdf = await p.pdf({ format: 'A4', landscape: true }); await p.context().close();
  const s = await geraet(null, { bereich: 'touren' });
  const wahl = s.waitForEvent('filechooser');
  await s.evaluate(() => rollkarteImport());
  await (await wahl).setFiles({ name: 'rollkarte.pdf', mimeType: 'application/pdf', buffer: pdf });
  await s.locator('#rk-form [data-rk]').nth(2).waitFor({ timeout: 20000 });
  assert.equal(await s.locator('#rk-form [name=datum]').inputValue(), '2026-11-20');
  assert.equal(await s.locator('#rk-form [name=abfahrt]').inputValue(), '09:30');
  const zeilen = await s.$$eval('#rk-form [data-rk]', l => l.map(z => ['kundeName', 'adresse', 'kundeTelefon', 'zeitfenster', 'auftragNr', 'betrag'].map(n => z.querySelector(`[name=${n}]`).value)));
  assert.deepEqual(zeilen[0], ['Meier', 'Hauptstr. 1, 42853 Remscheid', '0171111111', '10-13 Uhr', '10700001', '130,00']);
  assert.equal(zeilen[1][1], 'Lange Industriestraße 120, 42719 Solingen-Wald');
  assert.ok(await s.locator('#rk-form [data-rk="2"].pruefen', { hasText: 'PLZ prüfen' }).count(), 'vierstellige PLZ wird markiert');
  await s.locator('#rk-form [data-rk="2"] [name=adresse]').fill('Weg 3, 47799 Krefeld');
  await s.locator('#rk-form button.voll').click();
  await s.locator('#stopp-liste .stopp').nth(2).waitFor();
  const r = await s.evaluate(() => { const t = alle('touren')[0]; return { datum: t.datum, zeit: t.uhrzeit, n: stoppsVon(t.id).length, adr: stoppsVon(t.id)[2].adresse, summe: stoppSumme(t.id), eta: stoppsVon(t.id)[1].eta }; });
  assert.deepEqual(r, { datum: '2026-11-20', zeit: '09:30', n: 3, adr: 'Weg 3, 47799 Krefeld', summe: 55900, eta: '11:40' });
  // Dieselbe PDF erneut in diese Tour: schon vorhandene Aufträge sind abgewählt
  const wahl2 = s.waitForEvent('filechooser');
  await s.locator('#stopp-pdf').click();
  await (await wahl2).setFiles({ name: 'rollkarte.pdf', mimeType: 'application/pdf', buffer: pdf });
  await s.locator('#rk-form [data-rk]').first().waitFor({ timeout: 20000 });
  assert.equal(await s.locator('#rk-form [name=an]:checked').count(), 0);
  await s.close();
});

test('Sprachen: Fahrer-Ansicht auf Türkisch und Arabisch (RTL), Kunden-Nachrichten bleiben deutsch', async () => {
  const s = await geraet(null, { bereich: 'fahrer', viewport: { width: 390, height: 844 } });
  const tid = await s.evaluate(() => {
    FuhrparkAktionen.speichern({ kennzeichen: 'RS-AT 100', status: 'AKTIV' });
    const m = PersonalAktionen.speichern({ name: 'Ali Demir', rolle: 'FAHRER' }); S.einstellungen.ichId = m;
    const t = TourAktionen.neu(heute(), { kennzeichen: 'RS-AT 100', auftraggeber: 'Möbel Krause', name: 'Küche', fahrerId: m, uhrzeit: '07:00' });
    StoppAktionen.speichern({ tourId: t, reihenfolge: 1, kundeName: 'Frau Meier', adresse: 'Hauptstr. 1, Remscheid', betragCent: 13000 });
    bereichWechseln('fahrer'); return t;
  });
  await s.locator('[data-sprache-wahl]').first().click();
  await s.locator('[data-sprache="tr"]').click();
  await s.locator('[data-stempel="start"]', { hasText: 'İşe başla' }).waitFor();
  assert.ok(await s.locator('.fahrer-tour', { hasText: 'Teslim et' }).count());
  assert.equal(await s.evaluate(() => document.documentElement.lang), 'tr');
  // Nach außen bleibt alles deutsch
  const aussen = await s.evaluate(id => [lieferavisText(S.touren[id]), tourNachricht(S.touren[id])], tid);
  assert.ok(aussen[0].startsWith('Guten Tag') && aussen[1].includes('Stopps:'), aussen.join('\n'));
  // Arabisch: von rechts nach links, kein seitliches Scrollen
  await s.evaluate(() => { S.einstellungen.sprache = 'ar'; spracheAnwenden(); zeichne(); });
  await s.locator('[data-stempel="start"]', { hasText: 'بدء العمل' }).waitFor();
  assert.equal(await s.evaluate(() => document.documentElement.dir), 'rtl');
  assert.ok(await s.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'kein seitliches Scrollen in RTL');
  await s.locator('[data-fahrer="abliefern"]').click();
  await s.locator('#abliefern-form h2', { hasText: 'تسليم' }).waitFor();
  assert.ok(await s.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  // Alle Sprachen vollständig
  assert.deepEqual(await s.evaluate(() => Object.entries(UI_UEBERSETZUNG).filter(([, l]) => l.length !== UI_SCHLUESSEL.length || l.some(x => !x)).map(([k]) => k)), []);
  await s.close();
});

test('Rechte: Disposition und Fahrer sehen keine Exporte/Importe und nur erlaubte Einstellungen', async () => {
  const s = await geraet(null, { bereich: 'personal' });
  const ansicht = async rolle => s.evaluate(r => { S.zugang = r === 'ADMIN' ? null : { rolle: r, name: 'X' }; S.nurLokal = false; S.apiUrl = 'https://example.invalid/exec'; zeichne(); return rolle(); }, rolle);
  await s.evaluate(() => { PersonalAktionen.speichern({ name: 'Ali Demir', rolle: 'FAHRER' }); });
  // Admin (ohne Cloud) sieht Exporte
  assert.ok(await s.locator('[data-personal-csv]').isVisible());
  await ansicht('DISPO');
  assert.equal(await s.evaluate(() => rolle()), 'DISPO');
  assert.equal(await s.locator('[data-personal-csv]').isVisible(), false, 'CSV versteckt');
  assert.equal(await s.locator('label:has(#personal-import)').isVisible(), false, 'Import versteckt');
  // auch über Programm-Klick blockiert
  await s.evaluate(() => { window.__csv = 0; const b = document.createElement('button'); b.dataset.personalCsv = ''; document.body.appendChild(b); window.ladeHerunter = () => window.__csv++; b.click(); });
  assert.equal(await s.evaluate(() => window.__csv), 0);
  await s.locator('#toast', { hasText: 'Nur für Admins' }).waitFor();
  await s.evaluate(() => { location.hash = '#/einstellungen'; });
  await s.locator('#detail h3', { hasText: 'Bedienung' }).waitFor();
  const dispo = await s.$$eval('#detail > .karte.block h3', l => l.map(x => x.textContent));
  assert.ok(!dispo.some(x => /Cloud|Datensicherung|Buchhaltung|Kosten|Firmendaten|^Daten$/.test(x)), dispo.join(', '));
  // Fahrer: nur Bedienung (Sprache, Darstellung) und Anmeldung
  await ansicht('FAHRER');
  await s.evaluate(() => { location.hash = '#/x'; location.hash = '#/einstellungen'; zeichne(); });
  await s.locator('#detail h3', { hasText: 'Bedienung' }).waitFor();
  assert.deepEqual(await s.$$eval('#detail > .karte.block h3', l => l.map(x => x.textContent)), ['Bedienung']);
  assert.equal(await s.locator('#detail [data-einst-wahl="ichId"]').count(), 0);
  assert.equal(await s.locator('#detail [data-einst-wahl="sprache"]').count(), 1);
  await s.close();
});

test('Windows-App im Windows-Stil: Start ohne Fehler, Titelleiste folgt dem Design, Taskleisten-Zähler, Kontextmenü, Statusleiste', async () => {
  const kontext = await browser.newContext({ serviceWorkers: 'block', viewport: { width: 1400, height: 900 } });
  await kontext.route('https://script.google.com/**', r => r.abort());
  await kontext.addInitScript(() => {
    if (!localStorage.getItem('schadensfaelle-v1')) { localStorage.setItem('schadensfaelle-v1', JSON.stringify({ nurLokal: true, einstellungen: { thema: 'dunkel' } })); localStorage.setItem('crm-bereich', 'firmen'); }
    window.__fenster = { titel: [], zaehler: [] };
    window.desktop = { plattform: 'win32', beiZiel() {}, benachrichtige: async () => true, oeffneExtern: async () => true, oeffneDatei: async () => true,
      mail: { anbieter: async () => ({}), konto: async () => null, schnittstelle: 2, ruf: async () => [] },
      fenster: { info: () => ({ windows: true, windows11: true, mica: true, akzent: '#C239B3', dunkel: true, titelHoehe: 48 }), beiAenderung() {}, beiZoom() {},
        titelleiste: async (f, s, th) => { window.__fenster.titel.push([f, s, th]); return true; }, zaehler: async n => { window.__fenster.zaehler.push(n); return true; }, blinken: async () => true, zoom: async () => 1 } };
  });
  const s = await kontext.newPage();
  const fehler = []; s.on('pageerror', f => fehler.push(f.message));
  await s.goto(seite.url); await s.waitForFunction(() => window.__appBereit);
  assert.deepEqual(fehler, [], 'Start ohne Fehler');
  assert.ok(await s.evaluate(() => document.documentElement.classList.contains('win') && document.documentElement.classList.contains('mica')));
  assert.equal(await s.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--win-akzent').trim()), '#C239B3');
  assert.deepEqual(await s.evaluate(() => window.__fenster.titel.at(-1)), ['#202020', '#FFFFFF', 'dark'], 'Titelleiste dunkel');
  await s.evaluate(() => { S.einstellungen.thema = 'hell'; themaSetzen(); });
  assert.deepEqual(await s.evaluate(() => window.__fenster.titel.at(-1)), ['#F3F3F3', '#1A1A1A', 'light']);
  await s.locator('#statusleiste').waitFor();
  await s.evaluate(() => Aktionen.firmaSpeichern({ name: 'Möbel Krause', status: 'AKTIV', telefon: '0211 1' }));
  await s.locator('[data-firma]').first().click({ button: 'right' });
  await s.locator('.kontext-menue button', { hasText: 'Öffnen' }).waitFor();
  await s.keyboard.press('Escape');
  assert.equal(await s.locator('.kontext-menue').count(), 0);
  assert.deepEqual(fehler, []);
  await kontext.close();
});

test('Kontakthistorie der Firma begrenzt: 10 neueste, gekürzt, Filter, Suche, weitere anzeigen', async () => {
  const s = await geraet(null, { bereich: 'firmen' });
  const id = await s.evaluate(() => {
    const f = Aktionen.firmaSpeichern({ name: 'Viel Kontakt GmbH', status: 'AKTIV' });
    for (let i = 0; i < 35; i++) Aktionen.crmSpeichern('aktivitaeten', { firmaId: f, datum: plusTage(heute(), -i), art: i % 5 === 0 ? 'ANRUF' : 'EMAIL', kontakt: '', text: `Eintrag ${i}\n` + 'Zeile\n'.repeat(20) });
    return f;
  });
  await s.evaluate(i => { location.hash = '#/firma/' + i; }, id);
  const box = s.locator('[data-bereich-firma="aktivitaeten"]');
  await box.locator('[data-crm="aktivitaeten"]').first().waitFor();
  assert.equal(await box.locator('[data-crm="aktivitaeten"]').count(), 10, 'zuerst nur 10');
  assert.ok(await box.locator('.drei-zeilen').first().evaluate(e => e.scrollHeight > e.clientHeight), 'langer Text gekürzt');
  await box.locator('[data-historie-mehr]').click();
  assert.equal(await box.locator('[data-crm="aktivitaeten"]').count(), 30);
  await box.locator('[data-historie-mehr]').click();
  assert.equal(await box.locator('[data-crm="aktivitaeten"]').count(), 35);
  await box.locator('[data-historie-art="ANRUF"]').click();
  assert.equal(await box.locator('[data-crm="aktivitaeten"]').count(), 7, 'nur Anrufe');
  await box.locator('[data-historie-art=""]').click();
  await s.locator('#historie-suche').fill('Eintrag 17');
  assert.equal(await box.locator('[data-crm="aktivitaeten"]').count(), 1, 'Suche');
  await s.close();
});

test('E-Rechnung: XRechnung (CII) aus Rechnung und Gutschrift – Pflichtangaben, Schema und Geschäftsregeln; Empfang lesbar', async () => {
  const s = await geraet(null, { bereich: 'finanzen' });
  const r = await s.evaluate(() => {
    S.einstellungen.bearbeiter = 'Max Mustermann';
    const fid = uuid(); S.auftraggeber[fid] = { id: fid, name: 'Muster Möbel', rechtsform: 'GmbH', ustId: 'DE123456789', kundennummer: 'K-100', geaendertAm: jetztIso() };
    const id = BelegAktionen.neu('RECHNUNG', fid);
    BelegAktionen.speichern({ ...S.belege[id], status: 'OFFEN', datum: '2026-11-20', faelligAm: '2026-12-04', leistungVon: '2026-11-01', leistungBis: '2026-11-15',
      positionen: JSON.stringify([{ bez: 'Tourpauschale Krefeld & Umgebung', einheit: 'pro Tour', menge: 12, einzelCent: 55025 }, { bez: 'Montage', einheit: 'Std', menge: 3.5, einzelCent: 4500 }]) });
    const luecken = eRechnungLuecken(S.belege[id]);
    S.einstellungen.rechnung = { ...rechnungsDaten(), name: 'Ayse Transport UG', strasse: 'Musterstraße 1', plzOrt: '12345 Musterstadt', telefon: '0123 456789', email: 'info@example.de',
      ustId: 'DE987654321', steuer: '126/5000/1234', iban: 'DE89 3704 0044 0532 0130 00', bic: 'COBADEFFXXX', bank: 'Musterbank' };
    Aktionen.crmSpeichern('adressen', { firmaId: fid, art: 'RECHNUNG', strasse: 'Industriestraße 5', plz: '47803', ort: 'Krefeld' });
    Aktionen.crmSpeichern('ansprechpartner', { firmaId: fid, name: 'Buchhaltung', thema: 'Abrechnung', email: 'rechnung@example.com' });
    const xml = eRechnungXml(S.belege[id]);
    const gid = BelegAktionen.gutschrift(S.belege[id], false);
    BelegAktionen.speichern({ ...S.belege[gid], status: 'OFFEN', datum: '2026-11-25', positionen: JSON.stringify([{ bez: 'Gutschrift Wartezeit', einheit: '', menge: 1, einzelCent: 5000 }]) });
    const gxml = eRechnungXml(S.belege[gid]);
    const gelesen = eRechnungLesen(xml);
    return { luecken, nachher: eRechnungLuecken(S.belege[id]), xml, gxml, gelesen, summe: belegSummen(S.belege[id]) };
  });
  assert.ok(r.luecken.some(x => /Eigene Firmendaten/.test(x)) && r.luecken.some(x => /E-Mail des Kunden/.test(x)), 'fehlende Angaben werden genannt');
  assert.deepEqual(r.nachher, [], 'alles da');
  assert.equal(r.gelesen.nummer.length > 0, true); assert.equal(r.gelesen.verkaeufer.name, 'Ayse Transport UG'); assert.equal(r.gelesen.positionen.length, 2);
  assert.equal(Math.round(Number(r.gelesen.brutto) * 100), r.summe.brutto, 'Empfang liest den Gesamtbetrag');
  assert.ok(r.xml.includes('<ram:BillingSpecifiedPeriod>') && r.xml.includes('unitCode="HUR"') && r.xml.includes('xrechnung_3.0'));
  assert.ok(r.gxml.includes('<ram:TypeCode>381</ram:TypeCode>') && r.gxml.includes('<ram:InvoiceReferencedDocument>'));
  // offizielle Prüfung (Schema + EN 16931 + XRechnung), wenn die Regelwerke vorliegen (ERECHNUNG_REGELN)
  const regeln = process.env.ERECHNUNG_REGELN;
  if (regeln) {
    const fs = require('node:fs'), os = require('node:os'), { execFileSync } = require('node:child_process');
    for (const [name, xml] of [['rechnung', r.xml], ['gutschrift', r.gxml]]) {
      const datei = path.join(os.tmpdir(), `xrechnung-${name}.xml`); fs.writeFileSync(datei, xml);
      let aus;
      try { aus = execFileSync('python3', [path.join(__dirname, '../../tools/erechnung-pruefen.py'), datei, regeln], { encoding: 'utf8' }); }
      catch (e) { aus = e.stdout; }
      const erg = JSON.parse(aus);
      assert.deepEqual({ xsd: erg.xsd, fehler: erg.fehler }, { xsd: [], fehler: [] }, name + ': ' + aus);
    }
  }
  // Empfang: Datei öffnen zeigt die Rechnung lesbar
  await s.locator('#er-datei').setInputFiles({ name: 'eingang.xml', mimeType: 'application/xml', buffer: Buffer.from(r.xml) });
  await s.locator('#er-ansicht', { hasText: 'Ayse Transport UG' }).waitFor();
  await s.close();
});

test('In-App-Update: neue Oberfläche wird geladen, zu neue Fassung verlangt App-Update, Einstellungen „Updates“', async () => {
  const s = await geraet(null, { bereich: 'uebersicht' });
  let manifest = { stand: 9999999999, version: '9.9.9', minNativ: 1, dateien: {} };
  await s.context().route('https://aysetransport.github.io/schaden/app-update.json*', r => r.fulfill({ contentType: 'application/json', body: JSON.stringify(manifest) }));
  await s.evaluate(() => {
    window.__upd = { installiert: 0 };
    window.desktop = { web: { info: async () => ({ nativ: 1, app: '5.13.0', gebuendelt: 1, aktiv: 0 }), installieren: async () => { window.__upd.installiert++; return { stand: 9999999999, neu: true }; },
      ok: async () => true, zuruecksetzen: async () => true } };
  });
  await s.evaluate(() => updatePruefen(false));
  assert.equal(await s.evaluate(() => window.__upd.installiert), 1, 'automatisch geladen');
  await s.locator('#dialog', { hasText: 'Update geladen' }).waitFor();
  assert.ok(await s.locator('#upd-neu').isVisible());
  await s.locator('#dialog [data-zu]').click();
  // Fassung braucht neue App → nichts laden, Hinweis
  manifest = { ...manifest, minNativ: 2 };
  await s.evaluate(() => { UPD.bereit = false; return updatePruefen(true); });
  assert.equal(await s.evaluate(() => window.__upd.installiert), 1, 'nicht geladen');
  assert.equal(await s.evaluate(() => UPD.appNoetig), true);
  const karte = await s.evaluate(() => updateKarte());
  assert.ok(karte.includes('Jetzt nach Updates suchen') && karte.includes('App-Update') && karte.includes('5.13.0'), karte);
  // Modus „nicht suchen“
  manifest = { ...manifest, minNativ: 1 };
  await s.evaluate(() => { UPD.appNoetig = false; S.einstellungen.updateModus = 'AUS'; return updatePruefen(false); });
  assert.equal(await s.evaluate(() => window.__upd.installiert), 1, 'AUS: nichts geladen');
  await s.close();
});

test('Externe Rechnung und Leistungszeitraum: Betrag anteilig nach Tagen auf die Monate, auch nachträglich', async () => {
  const s = await geraet(null, { bereich: 'finanzen' });
  await s.evaluate(() => { const id = uuid(); S.auftraggeber[id] = { id, name: 'Altkunde GmbH', geaendertAm: jetztIso() }; nachAenderung(); });
  await s.locator('[data-externe-rechnung]').first().click();
  await s.locator('#extern-form [name=nummer]').fill('RE-2026-0815');
  await s.locator('#extern-form [name=datum]').fill('2026-11-20');
  await s.locator('#extern-form [name=netto]').fill('3100,00');
  await s.locator('#extern-form [name=von]').fill('2026-10-01');
  await s.locator('#extern-form [name=bis]').fill('2026-11-30');
  await s.locator('#extern-form [name=status]').selectOption('BEZAHLT');
  await s.locator('#extern-form button.voll').click();
  await s.locator('#toast', { hasText: 'Externe Rechnung erfasst' }).waitFor();
  const r = await s.evaluate(() => {
    const b = Object.values(S.belege).find(x => x.nummer === 'RE-2026-0815');
    const z = belegZeilen().filter(v => v.belegId === b.id).map(v => [v.datum.slice(0, 7), tourRechnung(v).erloes]);
    return { extern: b.extern, status: b.status, umsatz: umsatzNetto(b), z, pdf: !!document.querySelector('[data-beleg-akt="pdf"]') };
  });
  assert.equal(r.extern, true); assert.equal(r.status, 'BEZAHLT'); assert.equal(r.umsatz, 310000);
  assert.deepEqual(r.z, [['2026-10', 157541], ['2026-11', 152459]], '31 + 30 von 61 Tagen → 1.575,41 € Oktober, 1.524,59 € November');
  assert.equal(r.pdf, false, 'externe Rechnung wird nicht als eigene gedruckt');
  // eigene, schon bezahlte Rechnung: Zeitraum nachträglich setzen
  const id = await s.evaluate(() => { const f = Object.keys(S.auftraggeber)[0], id = BelegAktionen.neu('RECHNUNG', f);
    BelegAktionen.speichern({ ...S.belege[id], status: 'BEZAHLT', datum: '2026-11-05', positionen: JSON.stringify([{ bez: 'x', menge: 1, einzelCent: 90000 }]) }); return id; });
  await s.evaluate(i => { location.hash = '#/beleg/' + i; }, id);
  await s.locator('[data-leistung="leistungVon"]').fill('2026-09-01');
  await s.locator('[data-leistung="leistungVon"]').dispatchEvent('change');
  await s.locator('[data-leistung="leistungBis"]').fill('2026-11-30');
  await s.locator('[data-leistung="leistungBis"]').dispatchEvent('change');
  const z2 = await s.evaluate(i => belegZeilen().filter(v => v.belegId === i).map(v => [v.datum.slice(0, 7), tourRechnung(v).erloes]), id);
  assert.deepEqual(z2, [['2026-09', 29670], ['2026-10', 30659], ['2026-11', 29671]], 'nach Tagen (30/31/30 von 91) – Summe bleibt 900 €');
  assert.ok((await s.evaluate(i => belegHtml(S.belege[i]), id)).includes('Leistungszeitraum'));
  await s.close();
});

test('Ergebnisrechnung: jede verbuchte Rechnung zählt genau einmal, Rest geschätzt – Summen stimmen überall', async () => {
  const s = await geraet(null, { bereich: 'auswertung' });
  const r = await s.evaluate(() => {
    const tag = heute(), firma = n => { const id = uuid(); S.auftraggeber[id] = { id, name: n, geaendertAm: jetztIso() }; return id; };
    const fA = firma('A GmbH'), fB = firma('B KG');
    Aktionen.crmSpeichern('preise', { firmaId: fA, bezeichnung: 'Tourpauschale', betragCent: 50000, gueltigAb: '2020-01-01' });
    PersonalAktionen.speichern({ name: 'Ali', rolle: 'FAHRER' }); const ali = maZumNamen('Ali').id;
    const tour = (extra) => TourAktionen.speichern({ datum: tag, kennzeichen: 'RS-FK 1', fahrerId: ali, auftraggeberId: fA, ...extra });
    const rechnung = (fid, cent, status = 'OFFEN', extra = {}) => { const id = BelegAktionen.neu('RECHNUNG', fid);
      BelegAktionen.speichern({ ...S.belege[id], status, datum: tag, positionen: JSON.stringify([{ bez: 'x', menge: 1, einzelCent: cent }]), ...extra }); return id; };
    const binde = (tid, bid) => merke('touren', { ...S.touren[tid], belegId: bid, geaendertAm: jetztIso() });
    // 1) Rechnung über 2 gefahrene Touren (1.200 €), 2) eine davon mit Gutschrift 200 €
    const t1 = tour({ name: 'T1' }), t2 = tour({ name: 'T2' }), r1 = rechnung(fA, 120000, 'BEZAHLT'); binde(t1, r1); binde(t2, r1);
    const g1 = BelegAktionen.gutschrift(S.belege[r1], false); BelegAktionen.speichern({ ...S.belege[g1], status: 'OFFEN', datum: tag, positionen: JSON.stringify([{ bez: 'g', menge: 1, einzelCent: 20000 }]) });
    // 3) Pauschalrechnung ohne Tour (bezahlt) 3.000 €
    const r2 = rechnung(fB, 300000, 'BEZAHLT');
    // 4) gefahrene Tour ohne Rechnung → Preisliste 500 € geschätzt
    const t3 = tour({ name: 'T3' });
    // 5) Tour an Entwurf-Rechnung → geschätzt, Entwurf zählt nicht
    const t4 = tour({ name: 'T4' }), r3 = rechnung(fA, 99900, 'ENTWURF'); binde(t4, r3);
    // 6) Tour ohne Fahrer an verbuchter Rechnung 400 €
    const t5 = TourAktionen.speichern({ datum: tag, auftraggeberId: fA, name: 'T5' }), r4 = rechnung(fA, 40000, 'OFFEN'); binde(t5, r4);
    // 7) stornierte Rechnung zählt nicht
    rechnung(fB, 777700, 'STORNIERT');
    nachAenderung();
    const zr = { von: plusTage(tag, -1), bis: plusTage(tag, 1) };
    const zeilen = ergebnisZeilen(zr.von, zr.bis), rr = x => tourRechnung(x);
    const ist = alle('belege').reduce((n, b) => n + umsatzNetto(b), 0);
    const abgerechnet = zeilen.filter(x => !rr(x).geschaetzt).reduce((n, x) => n + rr(x).erloes, 0);
    const geschaetzt = zeilen.filter(x => rr(x).geschaetzt).reduce((n, x) => n + rr(x).erloes, 0);
    const q = QUELLEN.touren, qz = q.zeilen().filter(x => q.datum(x) >= zr.von && q.datum(x) <= zr.bis);
    const baukasten = qz.reduce((n, x) => n + q.masse.erloes[1](x), 0);
    const firmen = Object.fromEntries([fA, fB].map(f => [S.auftraggeber[f].name, zeilen.filter(x => x.auftraggeberId === f).reduce((n, x) => n + rr(x).erloes, 0)]));
    return { ist, abgerechnet, geschaetzt, baukasten, firmen, t1: rr(S.touren[t1]).erloes, t2: rr(S.touren[t2]).erloes, t3: rr(S.touren[t3]), t4: rr(S.touren[t4]), t5: rr(S.touren[t5]).erloes,
      guv: guvHtml(zr), firmaB: firmaTourenHtml(S.auftraggeber[fB]) };
  });
  assert.equal(r.ist, 120000 - 20000 + 300000 + 40000, 'Ist-Umsatz');
  assert.equal(r.abgerechnet, r.ist, 'jede verbuchte Rechnung genau einmal');
  assert.equal(r.t1 + r.t2, 100000, 'Rechnung minus Gutschrift auf die beiden Touren verteilt');
  assert.equal(r.t5, 40000, 'Tour ohne Fahrer bekommt ihren Rechnungsanteil');
  assert.ok(r.t3.geschaetzt && r.t3.erloes === 50000, 'ohne Rechnung: Preisliste, geschätzt');
  assert.ok(r.t4.geschaetzt && r.t4.erloes === 50000, 'Entwurf: noch geschätzt');
  assert.equal(r.geschaetzt, 100000);
  assert.equal(r.baukasten, r.ist + r.geschaetzt, 'Baukasten = Ist + geschätzt');
  assert.deepEqual(r.firmen, { 'A GmbH': 100000 + 40000 + 100000, 'B KG': 300000 });
  assert.ok(r.guv.includes('5.400,00'), 'G&V-Erlös 5.400 € (4.400 Ist + 1.000 geschätzt)');
  assert.ok(r.firmaB.includes('Gewinn/Verlust') && r.firmaB.includes('3.000,00'), 'Firma nur mit Pauschalrechnung');
  await s.close();
});

test('Statistik: „Kosten pro Einsatz“ bei Mitarbeiter und Fahrzeug haben Vorrang', async () => {
  const s = await geraet(null, { bereich: 'auswertung' });
  const r = await s.evaluate(() => {
    PersonalAktionen.speichern({ name: 'Ali Demir', rolle: 'FAHRER' }); PersonalAktionen.speichern({ name: 'Veli Kaya', rolle: 'FAHRER' });
    const ali = maZumNamen('Ali Demir'), veli = maZumNamen('Veli Kaya');
    PersonalAktionen.speichern({ ...ali, einsatzKostenCent: 18000 });
    FuhrparkAktionen.speichern({ kennzeichen: 'RS-FK 4201', art: 'LKW', status: 'AKTIV', einsatzKostenCent: 9500 });
    const id = TourAktionen.speichern({ datum: heute(), kennzeichen: 'RS-FK 4201', fahrerId: ali.id, beifahrerIds: veli.id, beifahrer: veli.id, name: 'Test' });
    const t = S.touren[id], k = tourRechnung(t);
    // Rechnung ohne Tourbezug (Pauschale) fließt als Erlös in Gewinn & Verlust ein
    const fid = uuid(); S.auftraggeber[fid] = { id: fid, name: 'Pauschal GmbH', geaendertAm: jetztIso() };
    const rid = BelegAktionen.neu('RECHNUNG', fid);
    BelegAktionen.speichern({ ...S.belege[rid], status: 'OFFEN', datum: heute(), positionen: JSON.stringify([{ bez: 'Montagepauschale November', menge: 1, einzelCent: 250000 }]) });
    // verbuchte Rechnung mit Touren: eine gefahrene (zählt über die Tour) und eine ohne Fahrer (Betrag fehlte bisher)
    const ohneFahrer = TourAktionen.speichern({ datum: heute(), name: 'Nur Kunde', auftraggeberId: fid });
    const gefahrenId = TourAktionen.speichern({ datum: heute(), kennzeichen: 'RS-FK 4201', fahrerId: veli.id, name: 'Gefahren', auftraggeberId: fid });
    const r2 = BelegAktionen.neu('RECHNUNG', fid);
    BelegAktionen.speichern({ ...S.belege[r2], status: 'BEZAHLT', datum: heute(), positionen: JSON.stringify([{ bez: 'Touren', menge: 2, einzelCent: 50000 }]) });
    for (const id of [ohneFahrer, gefahrenId]) merke('touren', { ...S.touren[id], belegId: r2, geaendertAm: jetztIso() });
    nachAenderung();
    const zr = { von: plusTage(heute(), -1), bis: plusTage(heute(), 1) };
    const virt = belegZeilen().find(v => v.belegId === rid), guv = guvHtml(zr);
    const qz = QUELLEN.touren.zeilen().filter(r => r.datum === heute());
    const anzahl = qz.reduce((n, r) => n + QUELLEN.touren.masse.anzahl[1](r), 0), erloes = qz.reduce((n, r) => n + QUELLEN.touren.masse.erloes[1](r), 0);
    return { personal: k.personal, fahrzeug: k.fahrzeug, tag: kostenAnnahmen().personalTag, gefahren: k.gefahren, team: teamVon(t).length,
      entwurfHinweis: (() => { const e = BelegAktionen.neu('RECHNUNG', fid); BelegAktionen.speichern({ ...S.belege[e], datum: heute(), positionen: JSON.stringify([{ bez: 'X', menge: 1, einzelCent: 10000 }]) });
        const vor = guvHtml(zr).includes('Entwurf/Entwürfe'), zaehltVorher = zaehltUmsatz(S.belege[e]); alsVersendet(S.belege[e]);
        return { vor, zaehltVorher, nachher: S.belege[e].status, zaehlt: zaehltUmsatz(S.belege[e]), weg: !guvHtml(zr).includes('Entwurf/Entwürfe') }; })(),
      firmaNurRechnung: (() => { const f2 = uuid(); S.auftraggeber[f2] = { id: f2, name: 'Nur Rechnung KG', geaendertAm: jetztIso() };
        const rb = BelegAktionen.neu('RECHNUNG', f2); BelegAktionen.speichern({ ...S.belege[rb], status: 'OFFEN', datum: heute(), positionen: JSON.stringify([{ bez: 'Pauschale', menge: 1, einzelCent: 120000 }]) });
        BelegAktionen.zahlung(S.belege[rb], belegSummen(S.belege[rb]).brutto, heute()); return firmaTourenHtml(S.auftraggeber[f2]); })(),
      gesamt: QUELLEN.touren.zeilen().filter(r => r.datum === heute()).reduce((n, r) => n + tourRechnung(r).erloes, 0),
      belegSumme: alle('belege').reduce((n, b) => n + umsatzNetto(b), 0),
      virtErloes: virt && tourRechnung(virt).erloes, guvHatPauschale: guv.includes('ohne Tourbezug'), anzahl, erloes, tourErloes: k.erloes };
  });
  assert.equal(r.team, 2, 'zwei im Team');
  assert.equal(r.virtErloes, 250000, 'Rechnung ohne Tour zählt als Erlös');
  assert.ok(r.guvHatPauschale, 'Gewinn & Verlust zeigt Rechnungen ohne Tourbezug');
  assert.equal(r.anzahl, 3, 'Rechnungen zählen nicht als Tour (3 Touren: 2 mit Fahrzeug/Team, 1 mit Rechnungserlös)');
  assert.deepEqual(r.entwurfHinweis, { vor: true, zaehltVorher: false, nachher: 'OFFEN', zaehlt: true, weg: true }, 'Entwurf: Hinweis, nach Druck/PDF zählt er');
  assert.ok(r.firmaNurRechnung.includes('Gewinn/Verlust') && r.firmaNurRechnung.includes('1.200,00'), 'bezahlte Rechnung ohne Tour im Firmen-Gewinn: ' + r.firmaNurRechnung);
  assert.equal(r.gesamt, r.belegSumme, 'jede verbuchte Rechnung zählt genau einmal – auch mit Touren ohne Fahrer');
  if (r.gefahren) {
    assert.equal(r.fahrzeug, 9500, 'Fahrzeug: Kosten pro Einsatz');
    assert.equal(r.personal, 18000 + r.tag, 'Ali mit Kosten pro Einsatz, Veli mit Tagessatz');
  }
  await s.close();
});

test('Rechnung: Unterschrift vom Geschäftsführer statt Bearbeiter, Hinweis „elektronisch erstellt“', async () => {
  const s = await geraet(null, { bereich: 'finanzen' });
  const r = await s.evaluate(() => {
    S.einstellungen.bearbeiter = 'Max Mustermann';
    S.einstellungen.rechnung = { ...rechnungsDaten(), name: 'Ayse Transport UG', geschaeftsfuehrer: 'Erika Musterfrau' };
    const fid = uuid(); S.auftraggeber[fid] = { id: fid, name: 'Kunde GmbH', geaendertAm: jetztIso() };
    const id = BelegAktionen.neu('RECHNUNG', fid);
    const b = S.belege[id], vorher = belegHtml(b);
    const gf = belegHtml({ ...b, unterzeichner: 'GF' });
    const gs = S.belege[BelegAktionen.gutschrift({ ...b, unterzeichner: 'GF' }, true)];
    S.einstellungen.rechnung = { ...rechnungsDaten(), hinweisElektronisch: '' };
    return { vorher, gf, gsUnterz: gs.unterzeichner, ohneHinweis: belegHtml(b) };
  });
  assert.ok(r.vorher.includes('Max Mustermann<br>Ayse Transport UG'), 'Standard: Bearbeiter');
  assert.ok(r.vorher.includes('ohne Unterschrift und Stempel gültig'), 'Hinweis standardmäßig drin');
  assert.ok(r.gf.includes('Erika Musterfrau<br>Geschäftsführer<br>Ayse Transport UG'), 'Geschäftsführer unterschreibt');
  assert.ok(!r.gf.includes('Max Mustermann<br>Ayse'), 'Bearbeiter nicht mehr in der Unterschrift');
  assert.ok(r.gf.includes('Geschäftsführer: Erika Musterfrau'), 'Geschäftsführer in der Fußzeile');
  assert.equal(r.gsUnterz, 'GF', 'Gutschrift übernimmt die Unterschrift');
  assert.ok(!r.ohneHinweis.includes('ohne Unterschrift und Stempel'), 'Hinweis abschaltbar');
  // abweichende IBAN je Auftraggeber
  const bank = await s.evaluate(() => {
    S.einstellungen.rechnung = { ...rechnungsDaten(), iban: 'DE89 3704 0044 0532 0130 00', bank: 'Musterbank' };
    const b = Object.values(S.belege).find(x => x.art === 'RECHNUNG'), f = S.auftraggeber[b.firmaId];
    const normal = belegHtml(b);
    S.auftraggeber[b.firmaId] = { ...f, rechnungIban: 'DE28 1234 5678 9012 3456 78', rechnungBank: 'Volksbank', rechnungBic: 'GENODED1XXX' };
    return { normal, abw: belegHtml(b) };
  });
  assert.ok(bank.normal.includes('IBAN DE89 3704') && bank.normal.includes('Musterbank'), 'Standard-IBAN');
  assert.ok(bank.abw.includes('IBAN DE28 1234') && bank.abw.includes('Volksbank') && bank.abw.includes('GENODED1XXX'), 'abweichende IBAN');
  assert.ok(!bank.abw.includes('DE89 3704'), 'Standard-IBAN nicht mehr drin');
  // Auswahl im Formular wird gespeichert
  const id = await s.evaluate(() => Object.keys(S.belege).find(k => S.belege[k].art === 'RECHNUNG'));
  await s.evaluate(i => { location.hash = '#/beleg/' + i; }, id);
  await s.locator('#beleg-form [name=unterzeichner]').selectOption('GF');
  await s.locator('#beleg-speichern').click();
  assert.equal(await s.evaluate(i => S.belege[i].unterzeichner, id), 'GF');
  await s.close();
});

test('Rolle geändert (Fahrer → Disposition): Gerät holt einmal den kompletten Bestand', async () => {
  const cloud = await starteCloud();
  const a = await geraet(cloud.url, { bereich: 'personal' });
  await melde(a, PIN, true); await aktuell(a);
  await a.evaluate(() => { for (const n of ['Perihan Kilic', 'Ali Demir', 'Mehmet Yilmaz']) PersonalAktionen.speichern({ name: n, rolle: 'FAHRER', telefon: '0171' });
    Aktionen.crmSpeichern('touren', { datum: heute(), kunde: 'Kunde A', mitarbeiter: maZumNamen('Ali Demir').id }); });
  await abgleich(a);
  const pid = await a.evaluate(() => maZumNamen('Perihan Kilic').id);
  await a.evaluate(id => api('benutzerSpeichern', PIN, { login: 'perihan.kilic', name: 'Perihan Kilic', rolle: 'FAHRER', mitarbeiterId: id, passwort: 'perihan-123' }), pid);
  const k = await browser.newContext({ serviceWorkers: 'block', viewport: { width: 1200, height: 900 } });
  await k.route('https://script.google.com/**', r => r.abort());
  const p = await k.newPage(); await p.goto(seite.url);
  await p.evaluate(url => { S.apiUrl = url; S.nurLokal = false; speichere(); }, cloud.url);
  await p.evaluate(() => einloggenMit('perihan.kilic', 'perihan-123'));
  await abgleich(p);
  assert.equal(await p.evaluate(() => alle('mitarbeiter').length), 1, 'als Fahrer nur sich selbst');
  // Admin macht sie zur Disposition – ihr Gerät hat noch den alten Stand (seit > 0)
  const bid = await a.evaluate(async () => (await api('benutzerListe', PIN)).find(b => b.login === 'perihan.kilic').id);
  await a.evaluate(id => api('benutzerSpeichern', PIN, { id, login: 'perihan.kilic', name: 'Perihan Kilic', rolle: 'DISPO', aktiv: true }), bid);
  await p.evaluate(() => einloggenMit('perihan.kilic', 'perihan-123'));
  await abgleich(p); await abgleich(p);
  assert.equal(await p.evaluate(() => rolle()), 'DISPO');
  assert.equal(await p.evaluate(() => alle('mitarbeiter').length), 3, 'alle Mitarbeiter');
  assert.equal(await p.evaluate(() => alle('touren').length), 1, 'alle Touren');
  await k.close(); await a.close(); await cloud.schliessen();
});

// (läuft nach dem Logins-Test, nutzt eine eigene Test-Cloud)
test('Persönlicher Anmeldelink: Fahrer tippt den Link an und ist angemeldet', async () => {
  const cloud = await starteCloud();
  const a = await geraet(cloud.url, { bereich: 'personal' });
  await melde(a, PIN, true); await aktuell(a);
  await a.evaluate(() => PersonalAktionen.speichern({ name: 'Ali Demir', rolle: 'FAHRER', telefon: '0171 1' }));
  await abgleich(a);
  await a.evaluate(() => api('benutzerSpeichern', PIN, { login: 'ali.demir', name: 'Ali Demir', rolle: 'FAHRER', mitarbeiterId: maZumNamen('Ali Demir').id, passwort: 'fahrer-123' }));
  const text = await a.evaluate(() => zugangText('Ali Demir', 'ali.demir', 'fahrer-123'));
  const link = text.match(/https?:\S+#anmelden=\S+/)[0];
  assert.ok(text.includes('Passwort: fahrer-123') && link.includes('#anmelden='), text);
  // frisches Gerät ohne jede Einrichtung
  const k = await browser.newContext({ serviceWorkers: 'block', viewport: { width: 390, height: 844 } });
  await k.route('https://script.google.com/**', r => r.abort());
  const f = await k.newPage();
  await f.goto(link.replace(/^https?:\/\/[^/#]+\/[^#]*/, seite.url));
  await f.waitForFunction(() => window.__appBereit);
  await f.locator('#toast', { hasText: 'Angemeldet als Ali Demir' }).waitFor();
  assert.equal(await f.evaluate(() => [rolle(), ANSICHT.bereich, location.hash.includes('anmelden')].join()), 'FAHRER,fahrer,false');
  // Gerät mit Daten: ein Link zu einer anderen Cloud verbindet erst nach Rückfrage
  const fremd = 'https://script.google.com/macros/s/FREMD123/exec';
  await f.goto(seite.url + '#anmelden=' + await f.evaluate(c => b64url(JSON.stringify({ u: 'x', p: 'y', c })), fremd)); await f.reload();
  await f.waitForFunction(() => window.__appBereit);
  await f.locator('#dialog', { hasText: 'Mit einer anderen Cloud verbinden?' }).waitFor();
  assert.notEqual(await f.evaluate(() => apiUrl()), fremd, 'ohne Bestätigung bleibt die bisherige Cloud');
  await f.locator('#dialog [data-zu]').click();
  assert.notEqual(await f.evaluate(() => apiUrl()), fremd);
  await k.close(); await a.context().close(); await cloud.schliessen();
});

test('Ganze Oberfläche übersetzt: Büro-Ansicht auf Türkisch, Daten und Vorschauen bleiben unverändert', async () => {
  const s = await geraet(null, { bereich: 'touren' });
  await s.evaluate(() => { const f = Aktionen.firmaSpeichern({ name: 'Möbel Krause', status: 'AKTIV' }); FuhrparkAktionen.speichern({ kennzeichen: 'RS-AT 100', status: 'AKTIV' });
    const m = PersonalAktionen.speichern({ name: 'Ali Demir', rolle: 'FAHRER' }); window.__tid = TourAktionen.neu(heute(), { kennzeichen: 'RS-AT 100', auftraggeberId: f, auftraggeber: '', name: 'Küche', fahrerId: m });
    S.einstellungen.sprache = 'tr'; spracheAnwenden(); zeichne(); });
  await s.waitForFunction(() => !!window.UI_UEBERSETZUNG);
  await s.locator('#navi', { hasText: 'Takvim' }).waitFor();
  assert.ok(await s.locator('[data-tp="drucken"]', { hasText: 'Yazdır' }).count(), 'Knopf übersetzt');
  // Dialog: Bedienung übersetzt, Daten (Kundenname) und Nachrichtenvorschau bleiben
  await s.evaluate(() => tourSenden(S.touren[window.__tid]));
  await s.locator('#dialog pre.vorschau').waitFor();
  assert.ok((await s.locator('#dialog pre.vorschau').textContent()).includes('Auftraggeber: Möbel Krause'), 'Vorschau bleibt deutsch');
  assert.ok(await s.locator('#dialog', { hasText: 'Ali Demir' }).count());
  // Zahlen im Text bleiben erhalten
  assert.equal(await s.evaluate(() => uebersetzeText('3 Stopps, 2 offen')), '3 durak, 2 açık');
  assert.equal(await s.evaluate(() => uebersetzeText('Möbel Krause')), 'Möbel Krause');
  await s.evaluate(() => { S.einstellungen.sprache = 'de'; spracheAnwenden(); });
  await s.close();
});

test('Hauptadmin schaltet die Cloud-PIN ab – danach nur noch persönliche Logins', async () => {
  const cloud = await starteCloud();
  const a = await geraet(cloud.url, { bereich: 'uebersicht' });
  await melde(a, PIN, true); await aktuell(a);
  // mit der PIN: niclas.lueg anlegen und im Dialog zum Hauptadmin machen
  await a.evaluate(() => api('benutzerSpeichern', PIN, { login: 'niclas.lueg', name: 'Niclas', rolle: 'ADMIN', passwort: 'geheim-123' }));
  await a.evaluate(() => benutzerVerwaltung());
  await a.locator('[data-benutzer]', { hasText: 'Niclas' }).click();
  await a.locator('#benutzer-form [name=hauptadmin]').check();
  await a.locator('#benutzer-form button.voll').click();
  await a.locator('[data-benutzer]', { hasText: '👑 Niclas' }).waitFor();
  await a.evaluate(() => $('#dialog').close());
  // als niclas.lueg anmelden und die PIN abschalten
  await a.evaluate(() => einloggenMit('niclas.lueg', 'geheim-123'));
  assert.equal(await a.evaluate(() => istHauptadmin()), true);
  await a.evaluate(() => { location.hash = '#/einstellungen'; });
  await a.locator('#pin-schalter').click();
  await a.locator('#ja', { hasText: 'Abschalten' }).click();
  await a.locator('#toast', { hasText: 'abgeschaltet' }).waitFor();
  // ein Gerät mit PIN: wird zur Login-Anmeldung geschickt, ohne PIN-Knopf
  const b = await geraet(cloud.url, { bereich: 'uebersicht' });
  await b.evaluate(() => anmeldung());
  await b.locator('#login-form').waitFor();
  assert.equal(await b.locator('#zur-pin').count(), 0);
  await b.context().close(); await a.context().close(); await cloud.schliessen();
});

test('Übersetzungen aus dem Netz: nur neuere, gültige Daten werden übernommen', async () => {
  const s = await geraet(null, { bereich: 'uebersicht' });
  const r = await s.evaluate(async () => {
    S.einstellungen.sprache = 'tr'; spracheAnwenden(); await new Promise(ok => setTimeout(ok, 300)); await UE.laedt;
    const alt = window.UI_STAND, k = ['Probetext'];
    return [woerterbuchUebernehmen({ stand: alt - 1, schluessel: k, uebersetzung: { tr: ['eski'] } }),
      woerterbuchUebernehmen({ stand: alt + 1, schluessel: k, uebersetzung: { tr: [{ böse: 1 }] } }),
      woerterbuchUebernehmen({ stand: alt + 1, schluessel: k, uebersetzung: { tr: ['yeni'] } }), uebersetzeText('Probetext')];
  });
  assert.deepEqual(r, [false, false, true, 'yeni']);
  await s.evaluate(() => { S.einstellungen.sprache = 'de'; spracheAnwenden(); });
  await s.close();
});

test('Finanzen: Gutschriftverfahren, Eingangsrechnungen, Tankbelege in Massen (mit und ohne Fahrzeug), Ausgaben in der Statistik', async () => {
  const s = await geraet(null, { bereich: 'finanzen' });
  await s.evaluate(() => { const id = uuid(); S.auftraggeber[id] = { id, name: 'Spedition Nord', geaendertAm: jetztIso() };
    const z = uuid(); S.fahrzeuge[z] = { id: z, kennzeichen: 'KR-AT 100', status: 'AKTIV', geaendertAm: jetztIso() }; nachAenderung(); });
  // Auftraggeber rechnet per Gutschrift ab → Einnahme wie eine Rechnung
  await s.locator('[data-externe-rechnung]').first().click();
  await s.locator('#extern-form [name=art]').selectOption('GV');
  await s.locator('#extern-form [name=nummer]').fill('GS-77');
  await s.locator('#extern-form [name=datum]').fill('2026-11-10');
  await s.locator('#extern-form [name=netto]').fill('1000,00');
  await s.locator('#extern-form button.voll').click();
  await s.locator('#toast', { hasText: 'Gutschrift (Gutschriftverfahren) erfasst' }).waitFor();
  const gv = await s.evaluate(() => { const b = Object.values(S.belege).find(x => x.nummer === 'GS-77'); return { gv: b.gutschriftverfahren, umsatz: umsatzNetto(b), offen: istRechnungOffen(b) }; });
  assert.deepEqual(gv, { gv: true, umsatz: 100000, offen: true });
  // Eingangsrechnung von Hand; „Löschen“ kommt im Tab-Weg erst nach „Speichern“
  await s.locator('[data-neue-eingangsrechnung]').first().click();
  assert.equal(await s.evaluate(() => document.activeElement.name), 'lieferant', 'Start im ersten Feld');
  await s.locator('#eingang-form [name=lieferant]').fill('Werkstatt Meier');
  await s.locator('#eingang-form [name=nummer]').fill('W-1');
  await s.locator('#eingang-form [name=datum]').fill('2026-11-05');
  await s.locator('#eingang-form [name=brutto]').fill('119,00');
  await s.locator('#eingang-form [name=kategorie]').selectOption('WERKSTATT');
  await s.locator('#eingang-form [name=faelligAm]').fill('2026-11-06');
  await s.locator('#eingang-form button.voll').click();
  const e = await s.evaluate(() => { const x = Object.values(S.eingangsrechnungen).find(y => y.nummer === 'W-1'); return { netto: x.nettoCent, mwst: x.mwstCent, faellig: eingangFaellig(x), id: x.id }; });
  assert.deepEqual([e.netto, e.mwst, e.faellig], [10000, 1900, true]);
  await s.locator('[data-beleg-filter="EINGANG"]').click();
  await s.locator(`[data-eingang="${e.id}"]`).click();
  const knoepfe = await s.evaluate(() => [...document.querySelectorAll('#eingang-form .dialog-knoepfe button')].map(b => b.id || b.textContent.trim()));
  assert.deepEqual(knoepfe, ['Abbrechen', 'Speichern', 'eingang-loeschen']);
  await s.locator('#dialog [data-zu]').click();
  // gemischte Steuersätze (1.000,00 netto + 145,00 Steuer): nur „bezahlt“ setzen ändert keinen Cent
  const gemischt = await s.evaluate(() => { const id = uuid(); merke('eingangsrechnungen', { id, firmaId: '', art: 'RECHNUNG', lieferant: 'Großhandel', nummer: 'G-9', datum: '2026-11-02', nettoCent: 100000, mwstCent: 14500, kategorie: 'SONSTIGES', status: 'OFFEN', eRechnung: true, geaendertAm: jetztIso() }); nachAenderung(); return id; });
  await s.locator(`[data-eingang="${gemischt}"]`).click();
  await s.locator('#eingang-form [name=status]').selectOption('BEZAHLT');
  await s.locator('#eingang-form button.voll').click();
  assert.deepEqual(await s.evaluate(i => { const x = S.eingangsrechnungen[i]; return [x.nettoCent, x.mwstCent, x.status, x.eRechnung]; }, gemischt), [100000, 14500, 'BEZAHLT', true]);
  // Tankbelege: runtertippen, Enter springt weiter, neue Zeile kommt von selbst; ohne Kennzeichen → Ausgabe „Kraftstoff“
  await s.locator('[data-tankbelege]').first().click();
  const zeile = n => s.locator(`#tank-tabelle tbody tr:nth-child(${n})`);
  await zeile(1).locator('[data-t=datum]').fill('2026-11-12');
  await zeile(1).locator('[data-t=datum]').press('Enter');
  assert.equal(await s.evaluate(() => document.activeElement.dataset.t), 'kz', 'Enter springt ins nächste Feld');
  await zeile(1).locator('[data-t=kz]').fill('KR-AT 100');
  await zeile(1).locator('[data-t=liter]').fill('50');
  await zeile(1).locator('[data-t=betrag]').fill('89,50');
  await zeile(1).locator('[data-t=km]').fill('120000');
  await zeile(2).locator('[data-t=betrag]').fill('59,50');
  await zeile(2).locator('[data-t=ort]').fill('Aral');
  assert.equal(await s.locator('#tank-tabelle tbody tr').count(), 3, 'immer eine leere Zeile unten');
  await zeile(2).locator('[data-t=ort]').press('Control+Enter');
  await s.locator('#toast', { hasText: '2 Tankbelege gespeichert' }).waitFor();
  const t = await s.evaluate(() => { const fz = Object.values(S.fahrzeuge)[0], a = alle('fahrzeugeintraege').find(x => x.art === 'TANKEN'), k = alle('eingangsrechnungen').find(x => x.kategorie === 'KRAFTSTOFF');
    return { akte: [a.betragCent, a.menge, a.datum], km: fz.kmStand, ohne: [k.nettoCent + k.mwstCent, k.lieferant, k.status, k.datum] }; });
  assert.deepEqual(t, { akte: [8950, 5000, '2026-11-12'], km: 120000, ohne: [5950, 'Aral', 'BEZAHLT', '2026-11-12'] });
  // unbekanntes Kennzeichen wird markiert, nichts gespeichert
  await s.locator('[data-tankbelege]').first().click();
  await zeile(1).locator('[data-t=kz]').fill('XX-UNBEKANNT');
  await zeile(1).locator('[data-t=betrag]').fill('10');
  await s.locator('#tank-form button.voll').click();
  assert.ok(await s.locator('#tank-fehler').isVisible());
  assert.equal(await zeile(1).locator('[data-t=kz]').getAttribute('aria-invalid'), 'true');
  await s.locator('#dialog [data-zu]').click();
  // Statistik: Ausgaben und Ergebnis
  const guv = await s.evaluate(() => guvHtml({ von: '2026-11-01', bis: '2026-11-30' }));
  assert.ok(guv.includes('id="ausgaben"') && guv.includes('Werkstatt / Reparatur') && guv.includes('Kraftstoff'), 'Ausgaben nach Kategorie');
  await s.close();
});

test('App-Update (neues Setup/APK): geladen → fragen → installieren', async () => {
  const s = await geraet(null, { bereich: 'uebersicht' });
  await s.context().route('https://aysetransport.github.io/schaden/app-update.json*', r => r.fulfill({ contentType: 'application/json', body: JSON.stringify({ stand: 1, minNativ: 1, dateien: {} }) }));
  await s.evaluate(() => { window.__inst = 0; window.desktop = { web: { info: async () => ({ nativ: 2 }), installieren: async () => ({}), ok: async () => true, zuruecksetzen: async () => true },
    app: { pruefen: async () => ({ bereit: true, version: '4.18.0' }), installieren: async () => { window.__inst++; return true; } } }; });
  await s.evaluate(() => updatePruefen(false));
  await s.locator('#dialog', { hasText: 'App-Update bereit' }).waitFor();
  await s.locator('#dialog [data-app-installieren]').click();
  assert.equal(await s.evaluate(() => window.__inst), 1);
  assert.ok((await s.evaluate(() => updateKarte())).includes('App-Update 4.18.0 installieren'));
  await s.close();
});

test('Tourenplanung: Tour/Ziel als Auswahl aus den Stammdaten der Firma (Preisliste), Rechnung mit Ziel-Preisen', async () => {
  const s = await geraet(null, { bereich: 'touren' });
  const tag = '2026-11-10';
  await s.evaluate(tag => {
    const f = uuid(), m = uuid(), jetzt = jetztIso();
    S.auftraggeber[f] = { id: f, name: 'Möbel Krause', geaendertAm: jetzt };
    S.mitarbeiter[m] = { id: m, name: 'Ali Demir', rolle: 'FAHRER', aktiv: true, geaendertAm: jetzt };
    merke('einsatzarten', { id: uuid(), name: 'Tourpauschale', beifahrer: 0, geaendertAm: jetzt });
    for (const [b, c] of [['Tourpauschale', 30000], ['Tour 12 – Hamburg', 45000], ['Tour 7 – Bremen', 38000]]) merke('preise', { id: uuid(), firmaId: f, bezeichnung: b, betragCent: c, einheit: 'je Tour', geaendertAm: jetzt });
    const tour = (name, kz) => merke('touren', { id: uuid(), datum: tag, reihenfolge: 0, kennzeichen: kz, auftraggeberId: f, auftraggeber: 'Möbel Krause', name, einsatzart: 'Tourpauschale', fahrerId: m, geaendertAm: jetzt });
    tour('Tour 12 – Hamburg', 'KR-A 1'); tour('Tour 12 – Hamburg', 'KR-A 2'); tour('Tour 7 – Bremen', 'KR-A 3'); tour('', 'KR-A 4'); tour('HH alt', 'KR-A 5');
    ANSICHT.tourTag = tag; nachAenderung();
  }, tag);
  // Auswahl statt Freitext: nur die Ziele, nicht die Einsatzart aus der Preisliste
  const auswahl = await s.evaluate(() => [...document.querySelectorAll('select[data-feld="name"]')].map(x => [...x.options].map(o => o.textContent.trim()).filter(Boolean)));
  assert.equal(auswahl.length, 5, 'jede Tourzeile hat ein Auswahlfeld');
  assert.ok(auswahl.some(l => JSON.stringify(l) === JSON.stringify(['Tour 12 – Hamburg', 'Tour 7 – Bremen'])), JSON.stringify(auswahl)); // Zeilen ohne festen Ablauf
  assert.ok(auswahl.some(l => l.includes('HH alt')), 'alter Freitext bleibt sichtbar');
  const hinweise = await s.locator('#liste').textContent();
  assert.ok(hinweise.includes('Ziel nicht in den Stammdaten') && hinweise.includes('Ziel fehlt'));
  // Ziel wählen
  const zeile = s.locator('[data-tour-zeile]').filter({ has: s.locator('select[data-feld="name"] option[selected]', { hasText: 'HH alt' }) });
  await zeile.locator('select[data-feld="name"]').selectOption('Tour 12 – Hamburg');
  await s.evaluate(() => { const t = Object.values(S.touren).find(x => !x.name); merke('touren', { ...t, name: 'Tour 7 – Bremen' }); nachAenderung(); });
  // Rechnung: je Ziel eine Position mit dem Ziel-Preis (nicht der Tourpauschale)
  const r = await s.evaluate(tag => { const f = Object.values(S.auftraggeber)[0];
    return sammelPositionen(f, tourenVonFirma(f, tag, tag), tag).map(g => [g.pos.bez, g.pos.menge, g.pos.einzelCent]); }, tag);
  assert.deepEqual(r, [['Tour 12 – Hamburg', 3, 45000], ['Tour 7 – Bremen', 2, 38000]]);
  assert.equal(await s.evaluate(() => tourRechnung(Object.values(S.touren).find(t => t.name === 'Tour 7 – Bremen')).erloes), 38000, 'Statistik schätzt mit demselben Preis');
  await s.evaluate(() => { const f = Object.values(S.auftraggeber)[0]; rechnungAusTourenVorschau(f, '2026-11-10', '2026-11-10', 'ART'); });
  assert.equal(await s.locator('#tpr-summe').textContent(), await s.evaluate(() => geld(3 * 45000 + 2 * 38000)));
  // jede Tour einzeln mit Datum: fünf Positionen, gleiche Summe
  await s.evaluate(() => { const f = Object.values(S.auftraggeber)[0]; rechnungAusTourenVorschau(f, '2026-11-09', '2026-11-10', 'EINZELN'); });
  assert.equal(await s.locator('#tpr-summe').textContent(), await s.evaluate(() => geld(3 * 45000 + 2 * 38000)));
  await s.locator('#tpr-vorschau button.voll').click();
  const pos = await s.evaluate(() => positionen(S.belege[location.hash.split('/').pop()]).map(p => [p.bez, p.menge, p.einzelCent]));
  assert.equal(pos.length, 5);
  assert.deepEqual(pos[0], ['10.11.2026 · Tour 12 – Hamburg (Tourpauschale)', 1, 45000]);
  assert.ok(pos.every(p => p[0].startsWith('10.11.2026 · ') && p[1] === 1));
  await s.close();
});

test('Firmen zusammenführen nimmt die Touren mit; Touren einer schon gelöschten Firma lassen sich gesammelt neu zuordnen', async () => {
  const s = await geraet(null, { bereich: 'touren' });
  const tag = '2026-11-12';
  const r = await s.evaluate(tag => {
    const ziel = Aktionen.firmaSpeichern({ name: 'Möbel Krause GmbH', status: 'AKTIV' }), weg = Aktionen.firmaSpeichern({ name: 'Moebel Krause', status: 'AKTIV' });
    const tour = (x) => merke('touren', { id: uuid(), firmaId: '', datum: tag, reihenfolge: 0, kennzeichen: 'KR-B 1', name: 'Hamburg', ...x, geaendertAm: jetztIso() }).id;
    const a = tour({ auftraggeberId: weg, auftraggeber: 'Moebel Krause' }), b = tour({ auftraggeber: 'Moebel Krause' }); // verknüpft bzw. nur über den Namen
    Aktionen.firmenZusammenfuehren(S.auftraggeber[weg], S.auftraggeber[ziel]);
    return { ziel, a: S.touren[a].auftraggeberId, b: S.touren[b].auftraggeberId, n: tourenVonFirma(S.auftraggeber[ziel]).length };
  }, tag);
  assert.deepEqual([r.a, r.b, r.n], [r.ziel, r.ziel, 2], 'beide Touren gehören jetzt zur verbleibenden Firma');
  // Altfall: Touren zeigen auf eine Firma, die es nicht mehr gibt → Hinweis, einmal neu wählen repariert alle
  await s.evaluate(tag => {
    for (let i = 0; i < 3; i++) merke('touren', { id: uuid(), firmaId: '', datum: tag, reihenfolge: i + 5, kennzeichen: 'KR-C ' + i, auftraggeberId: 'weg-0000', auftraggeber: 'Krause alt', geaendertAm: jetztIso() });
    ANSICHT.tourTag = tag; nachAenderung();
  }, tag);
  assert.equal(await s.locator('#liste .marke', { hasText: 'Firma gelöscht' }).count(), 3);
  const feld = s.locator('[data-tour-zeile]').filter({ has: s.locator('.marke', { hasText: 'Firma gelöscht' }) }).first().locator('[data-feld="auftraggeber"]');
  await feld.fill('Möbel Krause GmbH');
  await feld.dispatchEvent('change');
  await s.locator('#toast', { hasText: '2 weitere Touren' }).waitFor();
  assert.equal(await s.evaluate(z => tourenVonFirma(S.auftraggeber[z]).length, r.ziel), 5);
  assert.equal(await s.locator('#liste .marke', { hasText: 'Firma gelöscht' }).count(), 0);
  // Zusammenführen merkt sich den alten Namen als Alias; ein Alias ordnet Touren ohne gültige Firma zu
  assert.equal(await s.evaluate(z => S.auftraggeber[z].aliase, r.ziel), 'Moebel Krause');
  const n = await s.evaluate(([z, tag]) => {
    for (let i = 0; i < 2; i++) merke('touren', { id: uuid(), firmaId: '', datum: tag, reihenfolge: i + 9, auftraggeberId: 'weg-1111', auftraggeber: 'Krause ganz alt', geaendertAm: jetztIso() });
    Aktionen.firmaSpeichern({ ...S.auftraggeber[z], aliase: 'Moebel Krause, Krause ganz alt' });
    return [tourenVonFirma(S.auftraggeber[z]).length, firmaZumNamen('krause ganz alt').id === z];
  }, [r.ziel, tag]);
  assert.deepEqual(n, [7, true], 'zwei weitere Touren über den Alias; Name findet die Firma');
  await s.close();
});
