'use strict';
/**
 * E-Mail für die Windows-App: IMAP zum Lesen, SMTP zum Senden – direkt zum Postfach, wie die
 * Android-App. Dieses Modul läuft im Hauptprozess; das Passwort erreicht die Oberfläche nie.
 */
const crypto = require('node:crypto');
const { ImapFlow } = require('imapflow');
const nodemailer = require('nodemailer');
const MailComposer = require('nodemailer/lib/mail-composer');
const { simpleParser } = require('mailparser');

class MailFehler extends Error {
  constructor(message, anmeldung = false) { super(message); this.anmeldung = anmeldung; }
}

/** Voreinstellungen gängiger Anbieter (identisch zur Android-App). */
const ANBIETER = {
  IONOS: { label: 'IONOS (1&1)', imapHost: 'imap.ionos.de', imapPort: 993, imapSicherheit: 'SSL', smtpHost: 'smtp.ionos.de', smtpPort: 465, smtpSicherheit: 'SSL' },
  STRATO: { label: 'STRATO', imapHost: 'imap.strato.de', imapPort: 993, imapSicherheit: 'SSL', smtpHost: 'smtp.strato.de', smtpPort: 465, smtpSicherheit: 'SSL' },
  GMX: { label: 'GMX', imapHost: 'imap.gmx.net', imapPort: 993, imapSicherheit: 'SSL', smtpHost: 'mail.gmx.net', smtpPort: 465, smtpSicherheit: 'SSL' },
  WEBDE: { label: 'WEB.DE', imapHost: 'imap.web.de', imapPort: 993, imapSicherheit: 'SSL', smtpHost: 'smtp.web.de', smtpPort: 587, smtpSicherheit: 'STARTTLS' },
  TONLINE: { label: 'Telekom (T-Online)', imapHost: 'secureimap.t-online.de', imapPort: 993, imapSicherheit: 'SSL', smtpHost: 'securesmtp.t-online.de', smtpPort: 465, smtpSicherheit: 'SSL' },
};

function imap(k, passwort) {
  return new ImapFlow({
    host: k.imapHost, port: k.imapPort, secure: k.imapSicherheit === 'SSL',
    doSTARTTLS: k.imapSicherheit === 'STARTTLS' ? true : k.imapSicherheit === 'KEINE' ? false : undefined,
    auth: { user: k.benutzer || k.adresse, pass: passwort }, logger: false, connectionTimeout: 20000, greetingTimeout: 20000,
  });
}

function smtp(k, passwort) {
  return nodemailer.createTransport({
    host: k.smtpHost, port: k.smtpPort, secure: k.smtpSicherheit === 'SSL',
    requireTLS: k.smtpSicherheit === 'STARTTLS', ignoreTLS: k.smtpSicherheit === 'KEINE',
    auth: { user: k.benutzer || k.adresse, pass: passwort }, connectionTimeout: 20000, greetingTimeout: 20000,
  });
}

function uebersetze(e) {
  if (e instanceof MailFehler) return e;
  const auth = e && (e.authenticationFailed || e.responseCode === 535 || e.code === 'EAUTH' || /auth/i.test(e.serverResponseCode || '') || /AUTHENTICATIONFAILED|Invalid credentials|LOGIN failed/i.test(e.responseText || e.response || e.message || ''));
  if (auth) return new MailFehler('Das Postfach hat die Anmeldung abgelehnt. Bitte Adresse und Passwort prüfen.', true);
  return new MailFehler('Keine Verbindung zum Postfach: ' + ((e && (e.responseText || e.message)) || e));
}

async function mitImap(k, passwort, arbeit) {
  const client = imap(k, passwort);
  client.on('error', () => {}); // Verbindungsfehler kommen über die Promises
  try {
    await client.connect();
    return await arbeit(client);
  } catch (e) {
    throw uebersetze(e);
  } finally {
    try { await client.logout(); } catch { client.close(); }
  }
}

/** Meldet sich bei IMAP und SMTP an – so fallen falsche Einstellungen sofort auf. */
async function pruefe(k, passwort) {
  await mitImap(k, passwort, async () => {});
  try { await smtp(k, passwort).verify(); } catch (e) { throw uebersetze(e); }
}

async function gesendetOrdner(client) {
  const ordner = await client.list();
  const sent = ordner.find(f => f.specialUse === '\\Sent');
  if (sent) return sent.path;
  const namen = ['sent', 'gesendet', 'gesendete objekte', 'gesendete elemente', 'inbox.sent', 'sent items', 'sent messages'];
  const treffer = ordner.find(f => namen.includes(f.path.toLowerCase()));
  return treffer ? treffer.path : null;
}

/**
 * Sendet die Mail und legt eine Kopie im Ordner „Gesendet“ ab.
 * entwurf: { an, betreff, text, antwortAuf, anhaenge: [{ name, mimeType, base64 }] }
 * @returns Message-ID ohne spitze Klammern
 */
async function sende(k, passwort, entwurf) {
  const domain = (k.adresse.split('@')[1] || 'schadensfaelle.local').toLowerCase();
  const messageId = `${crypto.randomUUID()}@${domain}`;
  const nachricht = {
    from: { name: k.name || '', address: k.adresse },
    to: entwurf.an,
    subject: entwurf.betreff,
    text: entwurf.text,
    messageId: `<${messageId}>`,
    date: new Date(),
    attachments: (entwurf.anhaenge || []).map(a => ({ filename: a.name, contentType: a.mimeType, content: Buffer.from(a.base64, 'base64') })),
  };
  if (entwurf.antwortAuf) { nachricht.inReplyTo = `<${entwurf.antwortAuf}>`; nachricht.references = [`<${entwurf.antwortAuf}>`]; }
  try { await smtp(k, passwort).sendMail(nachricht); } catch (e) { throw uebersetze(e); }
  // Viele Anbieter legen per SMTP Gesendetes nicht selbst ab – dann hier nachholen.
  try {
    const roh = await new MailComposer(nachricht).compile().build();
    await mitImap(k, passwort, async client => {
      const ordner = await gesendetOrdner(client);
      if (ordner) await client.append(ordner, roh, ['\\Seen']);
    });
  } catch { /* Ablage ist ein Zusatz – die Mail ist verschickt */ }
  return messageId;
}

const ohneKlammern = id => String(id || '').trim().replace(/^</, '').replace(/>$/, '');

/**
 * Holt neue Mails aus dem Posteingang, ohne dort etwas zu verändern.
 * Beim ersten Abruf (oder wenn sich die UIDVALIDITY geändert hat) nur die letzten 30 Tage.
 */
async function hole(k, passwort, { letzteUid = 0, uidValidity = 0 } = {}, ersteTage = 30) {
  return mitImap(k, passwort, async client => {
    const sperre = await client.getMailboxLock('INBOX', { readOnly: true });
    try {
      const box = client.mailbox;
      const gueltig = Number(box.uidValidity);
      const neuBeginnen = gueltig !== Number(uidValidity) || !(letzteUid > 0);
      let uids = [];
      if (box.exists > 0) {
        uids = neuBeginnen
          ? await client.search({ since: new Date(Date.now() - ersteTage * 86400000) }, { uid: true })
          : (await client.search({ uid: `${letzteUid + 1}:*` }, { uid: true })).filter(u => u > letzteUid);
      }
      const mails = [];
      for (const uid of uids || []) {
        const nachricht = await client.fetchOne(String(uid), { source: true }, { uid: true });
        if (!nachricht || !nachricht.source) continue;
        const m = await simpleParser(nachricht.source);
        const bezug = [m.inReplyTo, ...(Array.isArray(m.references) ? m.references : m.references ? [m.references] : [])]
          .filter(Boolean).flatMap(z => String(z).match(/<[^>]+>/g) || [z]).map(ohneKlammern);
        const von = (m.from && m.from.value && m.from.value[0]) || {};
        mails.push({
          uid,
          messageId: ohneKlammern(m.messageId) || `uid-${gueltig}-${uid}@${k.imapHost}`,
          antwortAuf: [...new Set(bezug)],
          von: String(von.address || '').toLowerCase(),
          vonName: von.name || '',
          an: (m.to && m.to.text) || '',
          betreff: m.subject || '',
          text: (m.text || (m.html ? htmlAlsText(m.html) : '')).replace(/\u00A0/g, ' ').trim(),
          zeitpunkt: (m.date || new Date()).toISOString(),
          anhaenge: (m.attachments || []).filter(a => a.content && a.content.length)
            .map(a => ({ name: a.filename || 'Anhang', mimeType: (a.contentType || 'application/octet-stream').toLowerCase(), base64: a.content.toString('base64') })),
        });
      }
      return { mails, letzteUid: Math.max(neuBeginnen ? 0 : letzteUid, Number(box.uidNext) - 1, 0), uidValidity: gueltig };
    } finally {
      sperre.release();
    }
  });
}

function htmlAlsText(html) {
  return String(html)
    .replace(/<(script|style)[^>]*>[\s\S]*?<\/\1>/gi, '')
    .replace(/<br\s*\/?>|<\/p>|<\/div>|<\/tr>|<\/li>/gi, '\n')
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'")
    .split('\n').map(z => z.trim()).join('\n').replace(/\n{3,}/g, '\n\n').trim();
}

module.exports = { ANBIETER, MailFehler, pruefe, sende, hole, htmlAlsText, ohneKlammern };
