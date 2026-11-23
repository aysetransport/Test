'use strict';
/**
 * Startet die echte Windows-App (Electron) und bedient sie: Cloud verbinden, Fall anlegen,
 * E-Mail aus einer Vorlage senden, Antwort abrufen und beantworten, Neustart.
 * Unter Linux ohne Bildschirm:  xvfb-run node --test test/app.test.js
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const nodemailer = require('nodemailer');
const { _electron: electron } = require('playwright');
const { starteCloud } = require('../../cloud/test/mock-server');
const { starteGreenMail } = require('./greenmail');

const PIN = 'geheim-2026';
const jahr = new Date().getFullYear();
let cloud, gm, daten;
test.before(async () => {
  cloud = await starteCloud();
  gm = await starteGreenMail();
  daten = fs.mkdtempSync(path.join(os.tmpdir(), 'schaden-app-'));
});
test.after(async () => { await cloud.schliessen(); await gm.beenden(); fs.rmSync(daten, { recursive: true, force: true }); });

async function starte() {
  const app = await electron.launch({
    args: [path.join(__dirname, '..'), ...(process.platform === 'linux' ? ['--no-sandbox'] : [])],
    env: { ...process.env, SCHADEN_DATEN: daten, SCHADEN_TEST: '1' },
  });
  const s = await app.firstWindow();
  s.on('pageerror', e => { throw e; });
  await s.waitForFunction(() => window.__appBereit);
  return { app, s };
}
const aktuell = s => s.waitForFunction(() => document.querySelector('#sync').dataset.zustand === 'aktuell', null, { timeout: 30000 });
const verlauf = (s, typ) => s.evaluate(t => Object.values(S.verlauf).filter(v => v.typ === t && !v.geloescht).map(v => v.text), typ);

test('Windows-App: Cloud, Fall, E-Mail senden, Antwort abrufen und beantworten, Neustart', async t => {
  let { app, s } = await starte();
  t.after(() => app.close().catch(() => {}));
  assert.equal(await s.title(), 'Ayse CRM');
  assert.equal(await s.evaluate(() => location.protocol), 'app:');
  assert.equal(await s.evaluate(() => typeof window.require), 'undefined', 'kein Node-Zugriff aus der Oberfläche');
  // Programme aus Mail-Anhängen werden nie direkt gestartet
  const exe = await s.evaluate(() => window.desktop.oeffneDatei('Rechnung.pdf.exe', new Uint8Array([77, 90])).then(() => 'geöffnet', e => e.message));
  assert.match(exe, /Sicherheitsgründen nicht geöffnet/);
  const lnk = await s.evaluate(() => window.desktop.oeffneDatei('Lieferschein.LNK. ', new Uint8Array([1])).then(() => 'geöffnet', e => e.message));
  assert.match(lnk, /Sicherheitsgründen/, 'auch mit Großbuchstaben und Punkt/Leerzeichen am Ende');

  // Cloud: Test-Cloud eintragen (statt der fest hinterlegten echten Adresse) und PIN festlegen.
  await s.evaluate(url => { localStorage.setItem('schadensfaelle-v1', JSON.stringify({ apiUrl: url })); localStorage.setItem('crm-bereich', 'faelle'); }, cloud.url);
  await s.reload(); await s.waitForFunction(() => window.__appBereit);
  await s.locator('#pin1').fill(PIN); await s.locator('#pin2').fill(PIN); await s.locator('#pin-form button.voll').click();
  await aktuell(s);

  // Ohne Konto erscheint in den Einstellungen die Einrichtung; ein unerreichbarer Server wird gemeldet.
  await s.locator('#einstellungen-knopf').click();
  await s.locator('#mail-einrichten-SCHADEN').click();
  await s.getByRole('button', { name: 'Eigener Server' }).click();
  await s.locator('#konto-form [name=adresse]').fill(gm.adresse);
  await s.locator('#konto-form [name=passwort]').fill(gm.passwort);
  await s.locator('#konto-form [name=imapHost]').fill('127.0.0.1');
  await s.locator('#konto-form [name=imapPort]').fill('1');
  await s.locator('#konto-form [name=smtpHost]').fill('127.0.0.1');
  await s.locator('#konto-verbinden').click();
  await s.locator('#konto-fehler', { hasText: 'Keine Verbindung zum Postfach' }).waitFor({ timeout: 30000 });
  await s.getByRole('button', { name: 'Abbrechen' }).click();

  // Konto mit dem unverschlüsselten Testserver einrichten (in der Oberfläche nur SSL/STARTTLS wählbar).
  await s.evaluate(async k => { mailKontoGesetzt('SCHADEN', await window.desktop.mail.einrichten(k.konfig, k.passwort, 'SCHADEN')); }, { konfig: gm.konfig, passwort: gm.passwort });
  await s.locator('#mail-abrufen-SCHADEN').waitFor();

  // Fall anlegen.
  await s.locator('[data-neu]').first().click();
  await s.locator('[name=kundeName]').fill('Anna Becker');
  await s.locator('[name=kundeEmail]').fill('anna@kunde.example');
  await s.locator('[name=schadensdatum]').fill(`${jahr}-11-03`);
  await s.locator('#fall-form button.voll').click();
  await s.locator('#wiedervorlage').waitFor();

  // Vorlage wählen, im Schreibfenster senden.
  await s.getByRole('button', { name: '✉ E-Mail' }).click();
  await s.getByRole('heading', { name: 'E-Mail schreiben' }).waitFor();
  await s.locator('[data-vorlage=ERINNERUNG]').click();
  assert.equal(await s.locator('#mail-form [name=an]').inputValue(), 'anna@kunde.example');
  // Der Betreff an den Geschädigten nennt das Meldedatum (beim Anlegen: heute).
  const meldedatum = await s.evaluate(() => datumText(heute()));
  assert.equal(await s.locator('#mail-form [name=betreff]').inputValue(), `Ihre Schadensmeldung vom ${meldedatum} – Erinnerung [Fall ${jahr}-001]`);
  await s.locator('#mail-senden').click();
  await s.getByText('E-Mail gesendet').first().waitFor({ timeout: 30000 });
  assert.equal((await verlauf(s, 'EMAIL_AUSGANG')).length, 1);

  // Die Kundin antwortet mit Foto; abrufen über das Fall-Menü.
  await nodemailer.createTransport({ host: '127.0.0.1', port: gm.smtpPort, secure: false, ignoreTLS: true }).sendMail({
    from: '"Anna Becker" <anna@kunde.example>', to: gm.adresse, subject: `Re: Ihre Schadensmeldung [Fall ${jahr}-001]`, text: 'Die Fotos anbei.',
    attachments: [{ filename: 'Sofa.jpg', content: Buffer.alloc(3000, 5), contentType: 'image/jpeg' }],
  });
  await s.getByRole('button', { name: 'Weitere Aktionen' }).click();
  await s.locator('#abrufen').click();
  await s.locator('.verlauf').getByText('Die Fotos anbei.').waitFor({ timeout: 30000 });
  await s.getByText('Anlagen (1)').waitFor();
  assert.equal(await s.evaluate(() => Object.values(S.faelle)[0].wiedervorlageAm), await s.evaluate(() => heute()));

  // Antworten: Bezug und Empfänger sind vorbelegt.
  await s.locator('[data-antworten]').click();
  assert.equal(await s.locator('#mail-form [name=an]').inputValue(), 'anna@kunde.example');
  assert.match(await s.locator('#mail-form [name=betreff]').inputValue(), /^Re: Ihre Schadensmeldung \[Fall \d{4}-001]$/);
  assert.match(await s.locator('#mail-form [name=text]').inputValue(), /> Die Fotos anbei\./);
  await s.locator('#mail-senden').click();
  await s.waitForFunction(() => Object.values(S.verlauf).filter(v => v.typ === 'EMAIL_AUSGANG').length === 2, null, { timeout: 30000 });

  // Alles ist in der Cloud angekommen – auch für Handy und Browser.
  await aktuell(s);
  const inCloud = Object.values(JSON.parse(cloud.umgebung.datei('Schadensfaelle/daten.json').bytes.toString()).verlauf).map(v => v.typ);
  assert.deepEqual(['EMAIL_AUSGANG', 'EMAIL_EINGANG'].map(t => inCloud.filter(x => x === t).length), [2, 1]);

  // Neustart: Konto und Fälle bleiben, das Passwort liegt nicht im Klartext in der Kontodatei, falls Windows-Verschlüsselung verfügbar ist.
  await app.close();
  ({ app, s } = await starte());
  t.after(() => app.close().catch(() => {}));
  await s.locator('#liste').getByText('Anna Becker').waitFor();
  assert.equal(await s.evaluate(() => MAIL.konto && MAIL.konto.adresse), gm.adresse);
  assert.equal(await s.evaluate(() => 'passwort' in MAIL.konto), false, 'Passwort erreicht die Oberfläche nicht');
  const konto = JSON.parse(fs.readFileSync(path.join(daten, 'mailkonto.json'), 'utf8'));
  if (konto.verschluesselt) assert.ok(!fs.readFileSync(path.join(daten, 'mailkonto.json'), 'utf8').includes(gm.passwort));
  await app.close();
});

/** Liest ein Postfach des Testservers (automatisch angelegte Konten: Passwort = Adresse). */
async function postfach(adresse) {
  const { ImapFlow } = require('imapflow');
  const c = new ImapFlow({ host: '127.0.0.1', port: gm.imapPort, secure: false, auth: { user: adresse, pass: adresse }, logger: false });
  await c.connect();
  const mails = [];
  const sperre = await c.getMailboxLock('INBOX');
  try { for await (const m of c.fetch('1:*', { source: true })) mails.push(m.source.toString()); } catch { /* leer */ } finally { sperre.release(); }
  await c.logout();
  return mails;
}

test('Windows-App: Zeichen des Auftraggebers, Bearbeitungsschritte, Dateianhang und Auftraggeber in BCC', async t => {
  const { app, s } = await starte();
  t.after(() => app.close().catch(() => {}));
  await s.locator('#liste').getByText('Anna Becker').click();
  await s.locator('#wiedervorlage').waitFor();

  // Auftraggeber mit Zeichen nachtragen.
  await s.getByRole('button', { name: 'Bearbeiten' }).click();
  await s.locator('[name=auftraggeber]').fill('Möbelhaus Nordpark');
  await s.locator('[name=auftraggeberEmail]').fill('dispo@moebel.example');
  await s.locator('[name=auftraggeberZeichen]').fill('RK-2291');
  await s.locator('#fall-form button.voll').click();
  await s.locator('#wiedervorlage').waitFor();

  // „An Auftraggeber melden“: Sachstand mit den bisherigen Schritten, Zeichen im Betreff.
  await s.locator('#an-auftraggeber').click();
  assert.equal(await s.locator('#mail-form [name=an]').inputValue(), 'dispo@moebel.example');
  assert.match(await s.locator('#mail-form [name=betreff]').inputValue(), /– Ihr Zeichen RK-2291 \[Fall \d{4}-001]$/);
  const text = await s.locator('#mail-form [name=text]').inputValue();
  // (Reihenfolge nach Zeitpunkt; der Date-Kopf der Antwort hat nur Sekunden, deshalb hier nicht geprüft)
  assert.match(text, /Stand der Bearbeitung: Neu\.\n\nBisherige Bearbeitung:\n– \d\d\.\d\d\.\d{4}: /);
  assert.equal((text.match(/: E-Mail an den Geschädigten gesendet\n/g) || []).length, 2);
  assert.match(text, /E-Mail des Geschädigten erhalten \(mit Anlagen\)/);
  assert.ok(!text.includes('anna@kunde.example'), 'keine Adressen der Kundin');
  assert.equal(await s.locator('#mail-bcc').count(), 0, 'keine BCC-Option, wenn der Auftraggeber Empfänger ist');

  // Eine Datei vom Rechner anhängen – sie landet auch beim Fall.
  const datei = path.join(daten, 'Lieferschein.pdf');
  fs.writeFileSync(datei, '%PDF-1.4 Test');
  await s.locator('#mail-datei').setInputFiles(datei);
  await s.locator('#mail-anlagen input[type=checkbox]:checked').first().waitFor();
  await s.locator('#mail-senden').click();
  await s.waitForFunction(() => Object.values(S.verlauf).filter(v => v.typ === 'EMAIL_AUSGANG').length === 3, null, { timeout: 30000 });
  const anAg = await postfach('dispo@moebel.example');
  assert.equal(anAg.length, 1);
  assert.match(anAg[0], /Lieferschein\.pdf/);
  assert.ok(await s.evaluate(() => Object.values(S.anlagen).some(a => a.anzeigename === 'Lieferschein.pdf' && !a.geloescht)));

  // An die Kundin, Auftraggeber in BCC; Bezug steht im Betreff.
  await s.getByRole('button', { name: '✉ E-Mail' }).click();
  await s.locator('[data-vorlage=ZWISCHENBESCHEID]').click();
  assert.match(await s.locator('#mail-form [name=betreff]').inputValue(), /– Bezug RK-2291 \[Fall/);
  await s.locator('#mail-bcc').check();
  await s.locator('#mail-senden').click();
  await s.waitForFunction(() => Object.values(S.verlauf).some(v => v.typ === 'EMAIL_AUSGANG' && v.text.includes('\nBcc: dispo@moebel.example\n')), null, { timeout: 30000 });
  const beiAg = await postfach('dispo@moebel.example');
  assert.equal(beiAg.length, 2, 'Blindkopie beim Auftraggeber');
  const beiKundin = (await postfach('anna@kunde.example')).filter(m => m.includes('Zwischenbescheid'));
  assert.equal(beiKundin.length, 1);
  assert.ok(!/^bcc:/im.test(beiKundin[0]) && !beiKundin[0].includes('dispo@moebel.example'), 'die Kundin sieht den Auftraggeber nicht');
  await app.close();
});

test('Windows-App: zweites Postfach, Zuordnung zur Firma, Eingang „Nicht zugeordnet“, Umhängen und Mail an die Firma', async () => {
  const { app, s } = await starte();
  try {
    await s.locator('#liste').getByText('Anna Becker').waitFor();
    const info = 'info@firma.example';
    const senden = (von, betreff, text, extra = {}) => nodemailer.createTransport({ host: '127.0.0.1', port: gm.smtpPort, secure: false, ignoreTLS: true })
      .sendMail({ from: von, to: info, subject: betreff, text, ...extra });
    // GreenMail legt das Postfach beim ersten Empfang an (Passwort = Adresse).
    await senden('einkauf@seats-sofas.example', 'Touren nächste Woche', 'Bitte drei Touren einplanen.');
    await senden('fremd@gmail.com', 'Anfrage Umzug', 'Was kostet ein Umzug?', { attachments: [{ filename: 'Grundriss.pdf', content: Buffer.from('%PDF-1.4'), contentType: 'application/pdf' }] });

    await s.evaluate(() => {
      const id = Aktionen.firmaSpeichern({ name: 'Seats and Sofas', status: 'AKTIV', website: 'https://www.seats-sofas.example' });
      Aktionen.crmSpeichern('ansprechpartner', { firmaId: id, name: 'Jana Krüger', thema: 'Disposition', email: 'jana@seats-sofas.example' });
    });
    await s.evaluate(async k => { mailKontoGesetzt('ALLGEMEIN', await window.desktop.mail.einrichten(k, k.benutzer, 'ALLGEMEIN')); },
      { ...gm.konfig, adresse: info, benutzer: info });
    await s.evaluate(() => postfachAbrufen(true));

    const ergebnis = await s.evaluate(() => {
      const firma = auftraggeberListe().find(e => e.name === 'Seats and Sofas');
      const liste = mailListe().filter(m => m.konto === 'ALLGEMEIN');
      return { firmaId: firma.id, liste: liste.map(m => [m.betreff, m.firmaId || '', istZugeordnet(m)]),
        historie: alle('aktivitaeten').filter(a => a.firmaId === firma.id).map(a => a.art) };
    });
    assert.deepEqual(ergebnis.liste.sort(), [['Anfrage Umzug', '', false], ['Touren nächste Woche', ergebnis.firmaId, true]]);
    assert.deepEqual(ergebnis.historie, ['EMAIL'], 'die Mail steht in der Kontakthistorie der Firma');

    // Postfach: „Nicht zugeordnet“ und von Hand dem Fall zuordnen – der Anhang wird Anlage des Falls.
    await s.locator('#navi [data-bereich=postfach]:visible').click();
    await s.locator('.pf-o[data-pf-ordner="V:OFFEN"]', { hasText: 'Nicht zugeordnet' }).locator('.pf-n', { hasText: '1' }).waitFor();
    await s.locator('.pf-o[data-pf-ordner="V:OFFEN"]').click();
    await s.locator('#postfach').getByText('Anfrage Umzug').click();
    await s.locator('[data-mail-zuordnen]').click();
    await s.locator('#zuordnen-form [name=fallId]').selectOption({ index: 1 });
    await s.locator('#zuordnen-form [name=kundeRef]').fill('Familie Weber');
    await s.locator('#zuordnen-form button.voll').click();
    await s.locator('#mail-zuordnung', { hasText: 'Kunde: Familie Weber' }).waitFor();
    await s.waitForFunction(() => Object.values(S.anlagen).some(a => a.anzeigename === 'Grundriss.pdf' && !a.geloescht));
    assert.ok(await s.evaluate(() => Object.values(S.verlauf).some(v => v.typ === 'EMAIL_EINGANG' && v.text.includes('Was kostet ein Umzug?'))));

    // Mail an die Firma geht über das allgemeine Postfach und landet in der Kontakthistorie.
    await s.evaluate(id => gehe('#/firma/' + id), ergebnis.firmaId);
    await s.locator('[data-oeffne="mailto:jana@seats-sofas.example"]').click();
    assert.equal(await s.locator('#mail-form [name=konto]').inputValue(), 'ALLGEMEIN');
    await s.locator('#mail-form [data-adressen=an] .vf-chip').first().waitFor();
    assert.deepEqual(await s.locator('#mail-form [data-adressen=an] .vf-chip').evaluateAll(l => l.map(c => c.dataset.wert)), ['jana@seats-sofas.example']);
    assert.match(await s.locator('#vf-editor').innerText(), /^Guten Tag Jana Krüger,/);
    await s.locator('#mail-form [name=betreff]').fill('Angebot 2027');
    await s.locator('#mail-senden').click();
    await s.getByText('E-Mail gesendet').first().waitFor({ timeout: 30000 });
    const angekommen = (await postfach('jana@seats-sofas.example')).find(m => m.includes('Angebot 2027'));
    assert.match(angekommen, /^From: .*info@firma\.example/m);
    assert.equal(await s.evaluate(id => alle('aktivitaeten').filter(a => a.firmaId === id && a.kontakt === 'Jana Krüger').length, ergebnis.firmaId), 1);
  } finally {
    await app.close();
  }
});

test('Windows-App: Rechnung als PDF speichern und per E-Mail mit PDF-Anhang senden', async () => {
  const { app, s } = await starte();
  try {
    await s.locator('#liste').getByText('Anna Becker').waitFor();
    const id = await s.evaluate(() => {
      const firma = auftraggeberListe().find(e => e.name === 'Seats and Sofas');
      Aktionen.crmSpeichern('ansprechpartner', { firmaId: firma.id, name: 'Petra Lang', thema: 'Abrechnung', email: 'buchhaltung@seats-sofas.example' });
      return BelegAktionen.neu('RECHNUNG', firma.id, { positionen: JSON.stringify([{ bez: 'Tourpauschale', menge: 3, einzelCent: 18950 }]) });
    });
    await s.evaluate(i => gehe('#/beleg/' + i), id);
    await s.locator('#beleg-form').waitFor();

    // PDF erzeugen und (im Test ohne Dialog) speichern
    const pdf = await s.evaluate(i => belegPdf(S.belege[i]), id);
    assert.ok(Buffer.from(pdf, 'base64').subarray(0, 5).toString() === '%PDF-', 'echtes PDF');
    await s.locator('[data-beleg-akt=pdf]').click();
    await s.getByText('Gespeichert:').waitFor({ timeout: 30000 });
    assert.ok(fs.readdirSync(daten).some(n => n.startsWith('Rechnung_R') && n.endsWith('.pdf')));
    assert.equal(await s.evaluate(i => S.belege[i].status, id), 'ENTWURF', 'PDF allein gilt nicht als Versand');

    // Per Mail: an die Abrechnung, mit PDF, danach gilt die Rechnung als versendet
    await s.locator('[data-beleg-akt=mail]').click();
    await s.locator('#mail-form [data-adressen=an] .vf-chip').first().waitFor();
    assert.deepEqual(await s.locator('#mail-form [data-adressen=an] .vf-chip').evaluateAll(l => l.map(c => c.dataset.wert)), ['buchhaltung@seats-sofas.example']);
    assert.match(await s.locator('#mail-form [name=betreff]').inputValue(), /^Rechnung R\d{4}-\d{4}$/);
    await s.locator('#mail-senden').click();
    await s.getByText('E-Mail gesendet').first().waitFor({ timeout: 30000 });
    const angekommen = await postfach('buchhaltung@seats-sofas.example');
    assert.equal(angekommen.length, 1);
    assert.match(angekommen[0], /application\/pdf/);
    assert.equal(await s.evaluate(i => S.belege[i].status, id), 'OFFEN');
    assert.equal(await s.evaluate(i => versandBelegt(S.belege[i]), id), true, 'Versand steht in der Kontakthistorie');
  } finally {
    await app.close();
  }
});

test('Windows-App: Unterfenster mit eigenem Bereich, gleicher Datenstand in beiden Fenstern', async () => {
  const { app, s } = await starte();
  try {
    const neu = app.waitForEvent('window');
    await s.evaluate(() => window.desktop.fenster.neu('finanzen', ''));
    const u = await neu;
    u.on('pageerror', e => { throw e; });
    await u.waitForFunction(() => window.__appBereit);
    assert.deepEqual(await u.evaluate(() => [NEBENFENSTER, ANSICHT.bereich]), [true, 'finanzen']);
    assert.equal(await s.evaluate(() => NEBENFENSTER), false, 'erstes Fenster bleibt Hauptfenster');
    // im Unterfenster anlegen → erscheint im Hauptfenster
    await u.evaluate(() => Aktionen.firmaSpeichern({ name: 'Aus dem Unterfenster GmbH', status: 'AKTIV' }));
    await s.waitForFunction(() => alle('auftraggeber').some(f => f.name === 'Aus dem Unterfenster GmbH'), null, { timeout: 10000 });
    // und umgekehrt
    await s.evaluate(() => Aktionen.firmaSpeichern({ name: 'Aus dem Hauptfenster KG', status: 'AKTIV' }));
    await u.waitForFunction(() => alle('auftraggeber').some(f => f.name === 'Aus dem Hauptfenster KG'), null, { timeout: 10000 });
    // ungültige Ziele werden nicht geladen
    const zahl = app.windows().length;
    const x = app.waitForEvent('window');
    await s.evaluate(() => window.desktop.fenster.neu('../böse', 'javascript:alert(1)'));
    const w = await x; await w.waitForLoadState();
    assert.ok(!w.url().includes('javascript') && !w.url().includes('böse'), w.url());
    assert.equal(app.windows().length, zahl + 1);
  } finally {
    await app.close();
  }
});
