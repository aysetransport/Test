'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const nodemailer = require('nodemailer');
const { ImapFlow } = require('imapflow');
const mail = require('../mail');
const { starteGreenMail } = require('./greenmail');

let gm;
test.before(async () => { gm = await starteGreenMail(); });
test.after(async () => { await gm.beenden(); });

/** Jemand schreibt an das Postfach der Firma. */
const empfange = nachricht => nodemailer.createTransport({ host: '127.0.0.1', port: gm.smtpPort, secure: false, ignoreTLS: true })
  .sendMail({ to: gm.adresse, ...nachricht });

async function imapFuer(adresse, passwort, arbeit) {
  const c = new ImapFlow({ host: '127.0.0.1', port: gm.imapPort, secure: false, doSTARTTLS: false, auth: { user: adresse, pass: passwort }, logger: false });
  await c.connect();
  try { return await arbeit(c); } finally { await c.logout(); }
}

test('Anmeldung: richtige Daten klappen, falsches Passwort wird verständlich abgelehnt', async () => {
  await mail.pruefe(gm.konfig, gm.passwort);
  await assert.rejects(mail.pruefe(gm.konfig, 'falsch'), e => e.anmeldung === true && /Anmeldung abgelehnt/.test(e.message));
  await assert.rejects(mail.pruefe({ ...gm.konfig, imapPort: 1 }, gm.passwort), e => !e.anmeldung && /Keine Verbindung/.test(e.message));
});

test('Senden: Anhang, Antwortbezug und Kopie unter Gesendet', async () => {
  await imapFuer(gm.adresse, gm.passwort, c => c.mailboxCreate('Sent').catch(() => {}));
  const id = await mail.sende(gm.konfig, gm.passwort, {
    an: 'anna@kunde.example', betreff: 'Unterlagen [Fall 2026-001]', text: 'Guten Tag Anna Becker,\n\nbitte Fotos.',
    antwortAuf: 'frage-1@kunde.example', anhaenge: [{ name: 'Lieferschein.pdf', mimeType: 'application/pdf', base64: Buffer.from('PDF-Inhalt').toString('base64') }],
  });
  assert.match(id, /^[0-9a-f-]{36}@firma\.example$/);

  // Die Kundin (Postfach legt GreenMail beim Empfang an, Passwort = Adresse) bekommt genau diese Mail.
  const beiKundin = await imapFuer('anna@kunde.example', 'anna@kunde.example', async c => {
    const sperre = await c.getMailboxLock('INBOX');
    try { return await c.fetchOne('*', { envelope: true, source: true }); } finally { sperre.release(); }
  });
  assert.equal(beiKundin.envelope.subject, 'Unterlagen [Fall 2026-001]');
  assert.equal(beiKundin.envelope.messageId, `<${id}>`);
  assert.equal(beiKundin.envelope.inReplyTo, '<frage-1@kunde.example>');
  assert.match(beiKundin.source.toString(), /filename="?Lieferschein\.pdf/);

  const gesendet = await imapFuer(gm.adresse, gm.passwort, async c => (await c.status('Sent', { messages: true })).messages);
  assert.equal(gesendet, 1);
});

test('Abrufen: Inhalt, Anhänge und Bezug; danach nur noch Neues, im Postfach bleibt alles ungelesen', async () => {
  await empfange({ from: '"Carl Demir" <Carl@Demir.example>', subject: 'Re: Ihre Meldung [Fall 2026-002]', text: 'Hier die Fotos.', inReplyTo: '<abc@firma.example>',
    references: ['<abc@firma.example>'], attachments: [{ filename: 'Armlehne.jpg', content: Buffer.alloc(4000, 7), contentType: 'image/jpeg' }] });
  await empfange({ from: 'news@shop.example', subject: 'Angebot', html: '<p>Hallo&nbsp;Welt</p><p>Zweite <b>Zeile</b></p>' });

  const erst = await mail.hole(gm.konfig, gm.passwort, {});
  const demir = erst.mails.find(m => m.von === 'carl@demir.example');
  assert.equal(demir.vonName, 'Carl Demir');
  assert.equal(demir.betreff, 'Re: Ihre Meldung [Fall 2026-002]');
  assert.equal(demir.text, 'Hier die Fotos.');
  assert.deepEqual(demir.antwortAuf, ['abc@firma.example']);
  assert.deepEqual(demir.anhaenge.map(a => [a.name, a.mimeType, Buffer.from(a.base64, 'base64').length]), [['Armlehne.jpg', 'image/jpeg', 4000]]);
  assert.equal(erst.mails.find(m => m.von === 'news@shop.example').text, 'Hallo Welt\n\nZweite Zeile', 'HTML-Mail als lesbarer Text');
  assert.ok(erst.letzteUid >= 2 && erst.uidValidity > 0);

  assert.equal((await mail.hole(gm.konfig, gm.passwort, erst)).mails.length, 0, 'nichts doppelt');
  await empfange({ from: 'dora@engel.example', subject: 'Neu', text: 'Neue Nachricht' });
  const danach = await mail.hole(gm.konfig, gm.passwort, erst);
  assert.deepEqual(danach.mails.map(m => m.betreff), ['Neu']);

  const ungelesen = await imapFuer(gm.adresse, gm.passwort, async c => (await c.status('INBOX', { unseen: true })).unseen);
  assert.ok(ungelesen >= 3, 'Abrufen markiert nichts als gelesen');
});
