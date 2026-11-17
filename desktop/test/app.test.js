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

  // Cloud: Test-Cloud eintragen (statt der fest hinterlegten echten Adresse) und PIN festlegen.
  await s.evaluate(url => { localStorage.setItem('schadensfaelle-v1', JSON.stringify({ apiUrl: url })); localStorage.setItem('crm-bereich', 'faelle'); }, cloud.url);
  await s.reload(); await s.waitForFunction(() => window.__appBereit);
  await s.locator('#pin1').fill(PIN); await s.locator('#pin2').fill(PIN); await s.locator('#pin-form button').click();
  await aktuell(s);

  // Ohne Konto erscheint in den Einstellungen die Einrichtung; ein unerreichbarer Server wird gemeldet.
  await s.locator('#einstellungen-knopf').click();
  await s.locator('#mail-einrichten').click();
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
  await s.evaluate(async k => { MAIL.konto = await window.desktop.mail.einrichten(k.konfig, k.passwort); zeichne(); }, { konfig: gm.konfig, passwort: gm.passwort });
  await s.locator('#mail-abrufen').waitFor();

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
