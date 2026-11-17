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
const aktuell = s => s.waitForFunction(() => document.querySelector('#sync').dataset.zustand === 'aktuell' && document.querySelector('#sync').textContent === 'Cloud aktuell');
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
  cloud.umgebung.kontext.API_VERSION = 6;
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
  await a.locator('[data-bereich=termine]').click();
  await a.locator('#termine').getByText('Überfällig (1)').waitFor();
  await a.locator('#termine').getByText('Preise 2027 verhandeln').waitFor();
  await a.locator('[data-bereich=firmen]').click();
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

test('CRM: Sicherung des Browser-CRM importieren und als CSV exportieren', async () => {
  const s = await geraet('', { bereich: 'firmen' });
  await legeFallAnUeberSchaeden(s);
  const sicherung = { firmen: [
    { id: 'f1', name: 'seats and sofas krefeld', rechtsform: 'GmbH', status: 'aktiv', telefon: '02151 123', email: '', website: 'seats.example', notizen: 'Rahmenvertrag',
      adressen: [{ id: 'a', art: 'Lager', strasse: 'Industriestr. 5', plz: '47803', ort: 'Krefeld', notiz: '' }],
      kontakte: [{ id: 'k', name: 'Jana Krüger', thema: 'Disposition', telefon: '', email: 'jana@seats.example', notiz: '' }],
      preise: [{ id: 'p', bez: 'Etage', betrag: 12.5, einheit: 'pro Etage', ab: '2026-01-01', notiz: '' }],
      aktivitaeten: [{ id: 'x', datum: '2026-11-02', art: 'Anruf', kontakt: 'Jana Krüger', text: 'Preise besprochen' }],
      aufgaben: [{ id: 't', faellig: '2026-12-01', text: 'Angebot', kontakt: '', erledigt: false }],
      dokumente: [{ id: 'd', titel: 'Vertrag', link: 'https://drive.example/v', notiz: '' }] },
    { id: 'f2', name: 'Neue Möbel AG', status: 'interessent' }] };
  await s.locator('#einstellungen-knopf').click();
  await s.locator('#crm-import').setInputFiles({ name: 'crm-sicherung.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(sicherung)) });
  await s.getByText('Importiert: 1 neue Firma(n), 1 mit vorhandenen zusammengeführt, 6 Einträge').waitFor();
  // Zweiter Import legt nichts doppelt an.
  await s.locator('#crm-import').setInputFiles({ name: 'crm-sicherung.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(sicherung)) });
  await s.getByText('Importiert: 0 neue Firma(n), 2 mit vorhandenen zusammengeführt, 0 Einträge').waitFor();
  const r = await s.evaluate(() => ({ firmen: auftraggeberListe().map(e => [e.name, e.rechtsform, e.status]), preise: alle('preise').map(p => p.betragCent),
    kontakte: alle('ansprechpartner').map(k => k.name).sort() }));
  assert.deepEqual(r.firmen, [['Neue Möbel AG', '', 'INTERESSENT'], ['Seats and Sofas Krefeld', 'GmbH', 'AKTIV']]);
  assert.deepEqual(r.preise, [1250]);
  assert.deepEqual(r.kontakte, ['Jana Krüger', 'Tim Berg']);

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
  await s.locator('[data-bereich=faelle]').click();
  await legeFallAn(s, { kundeName: 'Anna Becker', auftraggeber: 'Seats and Sofas Krefeld', auftraggeberKontakt: 'Tim Berg' });
  await s.locator('[data-bereich=firmen]').click();
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
  await a.locator('#detail').getByText('Nachfassen: Angebot besprochen').waitFor();

  // Termine: überfällige Wiedervorlage auf morgen schieben.
  await a.locator('[data-bereich=termine]').click();
  await a.locator('#termine').getByText('Überfällig (1)').waitFor();
  await a.locator('#termine [data-tage="1"]').click();
  await a.locator('#termine').getByText('Demnächst (1)').waitFor();

  // Schlagwort-Filter und „Lange kein Kontakt“
  await a.locator('[data-bereich=firmen]').click();
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
  await a.locator('[data-bereich=finanzen]').click();
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
  await a.locator('[data-bereich=uebersicht]').click();
  await a.locator('#liste').getByText('Überfällig (1)').waitFor();
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
  await a.locator('[data-bereich=termine]').click();
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
    fahrzeuge: [{ kennzeichen: 'RS-FK 4204', art: 'TRANSPORTER', marke: 'Mercedes-Benz', huBis: '2028-01-31', zgg: 3500 }, { kennzeichen: 'KR AT 123', marke: 'Andere Marke', farbe: 'Weiß' }],
    dokumente: [{ kennzeichen: 'RS-FK 4204', art: 'Fahrzeugschein (Teil I)', name: 'Schein.pdf', mimeType: 'application/pdf', base64: Buffer.from('%PDF-1.4').toString('base64') }] })) };
  await a.locator('[data-bereich=fuhrpark]').click();
  await a.locator('#fuhrpark-import').setInputFiles(datei);
  await a.locator('#toast', { hasText: '1 Fahrzeuge neu, 1 ergänzt, 1 Dokumente' }).waitFor();
  await a.locator('#fuhrpark-import').setInputFiles(datei); // ein zweites Mal: nichts doppelt
  await a.locator('#toast', { hasText: '0 Fahrzeuge neu, 0 ergänzt, 0 Dokumente' }).waitFor();
  const imp = await a.evaluate(() => alle('fahrzeuge').map(z => [z.kennzeichen, z.marke, z.farbe || '', anlagenVon(z.id).length]).sort());
  assert.deepEqual(imp, [['KR-AT 123', 'Mercedes-Benz', 'Weiß', 1], ['RS-FK 4204', 'Mercedes-Benz', '', 1]]);
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
  await s.locator('[data-bereich=postfach]').waitFor();
  assert.ok(await s.evaluate(() => window.__aufrufe.some(([n, a]) => n === 'mail:einrichten' && a[1] === 'geheim' && a[2] === 'SCHADEN')));

  // CSV-Download landet über die Brücke im Download-Ordner
  await s.locator('[data-bereich=fuhrpark]').click();
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
