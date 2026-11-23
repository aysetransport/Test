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

test('Abrufen: Inhalt, Anhänge und Bezug, im Postfach bleibt alles ungelesen', async () => {
  await empfange({ from: '"Carl Demir" <Carl@Demir.example>', subject: 'Re: Ihre Meldung [Fall 2026-002]', text: 'Hier die Fotos.', inReplyTo: '<abc@firma.example>',
    references: ['<abc@firma.example>'], attachments: [{ filename: 'Armlehne.jpg', content: Buffer.alloc(4000, 7), contentType: 'image/jpeg' }] });
  await empfange({ from: 'news@shop.example', subject: 'Angebot', html: '<p>Hallo&nbsp;Welt</p><p>Zweite <b>Zeile</b></p>' });

  const st = await mail.ruf(gm.konfig, gm.passwort, 'stand', {});
  const erst = await mail.ruf(gm.konfig, gm.passwort, 'lade', { uids: st.eintraege.map(e => e[0]) });
  const demir = erst.find(m => m.von === 'carl@demir.example');
  assert.equal(demir.vonName, 'Carl Demir');
  assert.equal(demir.betreff, 'Re: Ihre Meldung [Fall 2026-002]');
  assert.equal(demir.text, 'Hier die Fotos.');
  assert.deepEqual(demir.antwortAuf, ['abc@firma.example']);
  assert.deepEqual(demir.anhaenge.map(a => [a.name, a.mimeType, Buffer.from(a.base64, 'base64').length]), [['Armlehne.jpg', 'image/jpeg', 4000]]);
  assert.equal(erst.find(m => m.von === 'news@shop.example').text, 'Hallo Welt\n\nZweite Zeile', 'HTML-Mail als lesbarer Text');
  assert.ok(st.uidNext > 2 && st.uidValidity > 0 && st.eintraege.every(e => e[1] === 0), 'Stand: alles ungelesen');

  const ungelesen = await imapFuer(gm.adresse, gm.passwort, async c => (await c.status('INBOX', { unseen: true })).unseen);
  assert.ok(ungelesen >= 2, 'Laden markiert nichts als gelesen');
});

test('Schnittstelle 2: Ordner, Stand, Laden mit HTML/CC/Wichtigkeit, Kennzeichnen, Verschieben, Suche, Anhang, Quelle, Löschen', async () => {
  const k = gm.konfig, pw = gm.passwort;
  await mail.ruf(k, pw, 'ordnerAktion', { aktion: 'anlegen', pfad: 'Archiv' });
  await mail.ruf(k, pw, 'ordnerAktion', { aktion: 'anlegen', pfad: 'Trash' });
  const ordner = await mail.ruf(k, pw, 'ordner');
  assert.equal(ordner.find(o => o.pfad === 'INBOX').spezial, 'INBOX');
  assert.equal(ordner.find(o => o.pfad === 'Archiv').spezial, 'ARCHIVE');
  assert.equal(ordner.find(o => o.pfad === 'Trash').spezial, 'TRASH');

  await empfange({ from: '"Eva Fink" <eva@fink.example>', cc: 'chef@fink.example', replyTo: 'buchhaltung@fink.example', subject: 'Rechnung Oktober',
    html: '<p>Hallo <b>Team</b>,</p><img src="cid:logo@fink"><p>anbei die Rechnung.</p>', priority: 'high',
    headers: { 'Disposition-Notification-To': 'eva@fink.example', 'List-Unsubscribe': '<mailto:ab@fink.example>, <https://fink.example/ab>' },
    attachments: [{ filename: 'logo.png', content: Buffer.alloc(300, 1), contentType: 'image/png', cid: 'logo@fink' },
      { filename: 'Rechnung.pdf', content: Buffer.alloc(5000, 2), contentType: 'application/pdf' }] });
  const st = await mail.ruf(k, pw, 'stand', { ordner: 'INBOX', seit: new Date(Date.now() - 86400000).toISOString() });
  assert.ok(st.uidValidity > 0 && st.eintraege.length >= 1);
  const uid = st.eintraege[st.eintraege.length - 1][0];
  const [m] = await mail.ruf(k, pw, 'lade', { ordner: 'INBOX', uids: [uid], maxAnhang: 1000 });
  assert.equal(m.betreff, 'Rechnung Oktober'); assert.equal(m.vonName, 'Eva Fink');
  assert.match(m.cc, /chef@fink\.example/); assert.match(m.antwortAn, /buchhaltung@fink\.example/);
  assert.match(m.html, /<b>Team<\/b>/); assert.match(m.text, /anbei die Rechnung/);
  assert.equal(m.wichtig, 'HOCH'); assert.equal(m.lesebestaetigung, 'eva@fink.example'); assert.equal(m.abmelden, 'https://fink.example/ab');
  assert.equal(m.gelesen, false);
  const logo = m.anhaenge.find(a => a.cid === 'logo@fink'), pdf = m.anhaenge.find(a => a.name === 'Rechnung.pdf');
  assert.ok(logo && logo.base64 && logo.inline, 'kleines Inline-Bild direkt dabei');
  assert.ok(pdf && !pdf.base64 && pdf.groesse === 5000, 'großer Anhang nur angekündigt');
  const nr = m.anhaenge.indexOf(pdf);
  const geladen = await mail.ruf(k, pw, 'anhang', { ordner: 'INBOX', uid, nr });
  assert.equal(Buffer.from(geladen.base64, 'base64').length, 5000);
  assert.match(Buffer.from(await mail.ruf(k, pw, 'quelle', { ordner: 'INBOX', uid }), 'base64').toString(), /Subject: Rechnung Oktober/);

  await mail.ruf(k, pw, 'aktion', { ordner: 'INBOX', uids: [uid], aktion: 'gelesen' });
  await mail.ruf(k, pw, 'aktion', { ordner: 'INBOX', uids: [uid], aktion: 'markieren' });
  const nachher = (await mail.ruf(k, pw, 'stand', { ordner: 'INBOX' })).eintraege.find(e => e[0] === uid);
  assert.deepEqual(nachher.slice(1, 3), [1, 1], 'gelesen und gekennzeichnet auf dem Server');

  assert.deepEqual(await mail.ruf(k, pw, 'suche', { ordner: 'INBOX', betreff: 'Rechnung Oktober' }), [uid]);
  assert.deepEqual(await mail.ruf(k, pw, 'suche', { ordner: 'INBOX', text: 'xyz-gibt-es-nicht' }), []);

  const r = await mail.ruf(k, pw, 'aktion', { ordner: 'INBOX', uids: [uid], aktion: 'verschieben', ziel: 'Archiv' });
  assert.ok(!(await mail.ruf(k, pw, 'stand', { ordner: 'INBOX' })).eintraege.some(e => e[0] === uid), 'aus dem Posteingang weg');
  const imArchiv = (await mail.ruf(k, pw, 'stand', { ordner: 'Archiv' })).eintraege;
  assert.equal(imArchiv.length, 1);
  if (r.neueUids[uid]) assert.equal(Number(r.neueUids[uid]), imArchiv[0][0], 'neue UID gemeldet');
  await mail.ruf(k, pw, 'aktion', { ordner: 'Archiv', uids: [imArchiv[0][0]], aktion: 'loeschen' });
  assert.equal((await mail.ruf(k, pw, 'stand', { ordner: 'Archiv' })).eintraege.length, 0);
  await mail.ruf(k, pw, 'ordnerAktion', { aktion: 'umbenennen', pfad: 'Archiv', neu: 'Archiv2' });
  assert.ok((await mail.ruf(k, pw, 'ordner')).some(o => o.pfad === 'Archiv2'));
  await assert.rejects(mail.ruf(k, pw, 'gibtsnicht', {}), /Unbekannter Befehl/);
});

test('Senden erweitert: HTML mit Inline-Bild, CC, Wichtigkeit, Lesebestätigung, Bezugskette', async () => {
  const id = await mail.ruf(gm.konfig, gm.passwort, 'sende', {
    an: 'ben@kunde.example', cc: 'cora@kunde.example', betreff: 'Angebot', text: 'Hallo Ben', html: '<p>Hallo <b>Ben</b></p><img src="cid:sig1">',
    wichtig: 'HOCH', lesebestaetigung: true, antwortAuf: 'b@x', referenzen: ['a@x', 'b@x'], kopfzeilen: { 'Auto-Submitted': 'auto-replied', 'X-Test': 'ja', 'Bcc': 'boese@x' },
    anhaenge: [{ name: 'sig.png', mimeType: 'image/png', base64: Buffer.alloc(50, 3).toString('base64'), cid: 'sig1' }],
  });
  const roh = await imapFuer('cora@kunde.example', 'cora@kunde.example', async c => {
    const sperre = await c.getMailboxLock('INBOX');
    try { return (await c.fetchOne('*', { source: true })).source.toString(); } finally { sperre.release(); }
  });
  assert.match(roh, new RegExp(`Message-ID: <${id.replace(/[.]/g, '\\.')}>`, 'i'));
  assert.match(roh, /Cc: cora@kunde\.example/i); assert.match(roh, /X-Priority: 1/i); assert.match(roh, /Disposition-Notification-To: schaden@firma\.example/i);
  assert.match(roh, /References: <a@x> <b@x>/i); assert.match(roh, /In-Reply-To: <b@x>/i);
  assert.match(roh, /text\/html/); assert.match(roh, /Content-ID: <sig1>/i);
  assert.match(roh, /Auto-Submitted: auto-replied/i); assert.match(roh, /X-Test: ja/i); assert.doesNotMatch(roh, /boese@x/, 'nur erlaubte Kopfzeilen');
});
