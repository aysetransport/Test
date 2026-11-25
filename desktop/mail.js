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

/** IMAP-Verbindung zum Postfach (SSL, STARTTLS oder unverschlüsselt je nach Konto), mit festen Zeitgrenzen. */
function imap(k, passwort) {
  return new ImapFlow({
    host: k.imapHost, port: k.imapPort, secure: k.imapSicherheit === 'SSL',
    doSTARTTLS: k.imapSicherheit === 'STARTTLS' ? true : k.imapSicherheit === 'KEINE' ? false : undefined,
    auth: { user: k.benutzer || k.adresse, pass: passwort }, logger: false, connectionTimeout: 20000, greetingTimeout: 20000,
  });
}

/** SMTP-Versand über den Server des Kontos (SSL, STARTTLS oder unverschlüsselt). */
function smtp(k, passwort) {
  return nodemailer.createTransport({
    host: k.smtpHost, port: k.smtpPort, secure: k.smtpSicherheit === 'SSL',
    requireTLS: k.smtpSicherheit === 'STARTTLS', ignoreTLS: k.smtpSicherheit === 'KEINE',
    auth: { user: k.benutzer || k.adresse, pass: passwort }, connectionTimeout: 20000, greetingTimeout: 20000,
  });
}

/** Übersetzt Fehler von IMAP/SMTP in verständliche Meldungen; Anmeldefehler werden markiert (die Oberfläche fragt dann nach dem Passwort). */
function uebersetze(e) {
  if (e instanceof MailFehler) return e;
  const auth = e && (e.authenticationFailed || e.responseCode === 535 || e.code === 'EAUTH' || /auth/i.test(e.serverResponseCode || '') || /AUTHENTICATIONFAILED|Invalid credentials|LOGIN failed/i.test(e.responseText || e.response || e.message || ''));
  if (auth) return new MailFehler('Das Postfach hat die Anmeldung abgelehnt. Bitte Adresse und Passwort prüfen.', true);
  return new MailFehler('Keine Verbindung zum Postfach: ' + ((e && (e.responseText || e.message)) || e));
}

/** Verbindet, führt die Arbeit aus und trennt sicher wieder – auch bei Fehlern. */
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

/** Ordner für gesendete Mails: per Kennzeichnung (\\Sent), sonst über übliche Namen (Gesendet, Sent Items …). */
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
 * entwurf: { an, bcc, betreff, text, antwortAuf, anhaenge: [{ name, mimeType, base64 }] }
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
    attachments: (entwurf.anhaenge || []).map(a => ({ filename: a.name, contentType: a.mimeType, content: Buffer.from(a.base64, 'base64'), ...(a.cid ? { cid: a.cid, contentDisposition: 'inline' } : {}) })),
  };
  if (entwurf.html) nachricht.html = entwurf.html;
  if (entwurf.cc) nachricht.cc = entwurf.cc;
  if (entwurf.bcc) nachricht.bcc = entwurf.bcc; // Blindkopie, z. B. an den Auftraggeber
  if (entwurf.antwortAn) nachricht.replyTo = entwurf.antwortAn;
  if (entwurf.wichtig === 'HOCH') nachricht.priority = 'high';
  if (entwurf.wichtig === 'NIEDRIG') nachricht.priority = 'low';
  const kopfzeilen = Object.fromEntries(Object.entries(entwurf.kopfzeilen || {}).filter(([n]) => /^(x-[a-z0-9-]+|auto-submitted|precedence)$/i.test(n)).map(([n, w]) => [n, String(w).replace(/[\r\n]+/g, ' ')]));
  if (entwurf.lesebestaetigung) kopfzeilen['Disposition-Notification-To'] = k.adresse;
  if (Object.keys(kopfzeilen).length) nachricht.headers = kopfzeilen;
  const bezuege = [...new Set([...(entwurf.referenzen || []), entwurf.antwortAuf].filter(Boolean).map(ohneKlammern))];
  if (entwurf.antwortAuf) nachricht.inReplyTo = `<${ohneKlammern(entwurf.antwortAuf)}>`;
  if (bezuege.length) nachricht.references = bezuege.map(b => `<${b}>`);
  try { await smtp(k, passwort).sendMail(nachricht); } catch (e) { throw uebersetze(e); }
  // Viele Anbieter legen per SMTP Gesendetes nicht selbst ab – dann hier nachholen.
  try {
    const kopie = new MailComposer(nachricht).compile();
    kopie.keepBcc = true; // in der eigenen Ablage soll sichtbar bleiben, wer in BCC war
    const roh = await kopie.build();
    await mitImap(k, passwort, async client => {
      const ordner = await gesendetOrdner(client);
      if (ordner) await client.append(ordner, roh, ['\\Seen']);
    });
  } catch { /* Ablage ist ein Zusatz – die Mail ist verschickt */ }
  return messageId;
}

const ohneKlammern = id => String(id || '').trim().replace(/^</, '').replace(/>$/, '');

// ============================== Postfach wie Outlook (Schnittstelle 2) ==============================
// Allgemeine Bausteine, aus denen die Oberfläche alles Weitere zusammensetzt: Ordner, Stand (UIDs + Flags),
// Mails laden, Suche auf dem Server, Aktionen (gelesen, gekennzeichnet, verschieben, löschen), Ordner verwalten,
// Anhang nachladen, Quelltext. Gleiches Verhalten in der Android-App (Postfach.kt).
const SPEZIAL = { '\\Inbox': 'INBOX', '\\Sent': 'SENT', '\\Drafts': 'DRAFTS', '\\Trash': 'TRASH', '\\Junk': 'JUNK', '\\Archive': 'ARCHIVE', '\\All': 'ALLE' };
const SPEZIAL_NAMEN = [
  ['SENT', /^(inbox[./])?(sent|gesendet|gesendete (objekte|elemente|nachrichten)|sent (items|messages|mail))$/i],
  ['DRAFTS', /^(inbox[./])?(drafts|entw(ü|ue)rfe|entwurf)$/i],
  ['TRASH', /^(inbox[./])?(trash|papierkorb|gel(ö|oe)schte (objekte|elemente)|deleted (items|messages)|bin)$/i],
  ['JUNK', /^(inbox[./])?(junk|spam|junk-e-mail|unerw(ü|ue)nscht|spamverdacht|bulk)$/i],
  ['ARCHIVE', /^(inbox[./])?(archiv|archive)$/i],
];
const MAX_HTML = 1500000;

/** Art eines Ordners (INBOX, SENT, TRASH, JUNK, ARCHIVE, DRAFTS) aus Kennzeichnung oder Namen. */
function spezialVon(f) {
  if (String(f.path).toUpperCase() === 'INBOX') return 'INBOX';
  if (f.specialUse && SPEZIAL[f.specialUse]) return SPEZIAL[f.specialUse];
  const treffer = SPEZIAL_NAMEN.find(([, r]) => r.test(f.path));
  return treffer ? treffer[0] : '';
}

/** Alle Ordner des Postfachs mit Anzahl und ungelesenen Mails. */
async function ordner(k, passwort) {
  return mitImap(k, passwort, async client => {
    const liste = await client.list({ statusQuery: { messages: true, unseen: true } });
    return liste.map(f => ({
      pfad: f.path, name: f.name, trenner: f.delimiter || '/', spezial: spezialVon(f),
      gesamt: (f.status && f.status.messages) || 0, ungelesen: (f.status && f.status.unseen) || 0,
      auswaehlbar: !(f.flags && (f.flags.has('\\Noselect') || f.flags.has('\\NonExistent'))),
    }));
  });
}

/** UIDs und Flags eines Ordners (ab „seit“) – daraus erkennt die Oberfläche Neues, Geändertes und Gelöschtes. */
async function stand(k, passwort, { ordner: pfad = 'INBOX', seit = '' } = {}) {
  return mitImap(k, passwort, async client => {
    const sperre = await client.getMailboxLock(pfad, { readOnly: true });
    try {
      const box = client.mailbox, eintraege = [];
      if (box.exists > 0) {
        const uids = await client.search(seit ? { since: new Date(seit) } : { all: true }, { uid: true });
        if (uids && uids.length) {
          for await (const m of client.fetch(uids, { uid: true, flags: true }, { uid: true })) {
            const f = m.flags || new Set();
            eintraege.push([m.uid, f.has('\\Seen') ? 1 : 0, f.has('\\Flagged') ? 1 : 0, f.has('\\Answered') ? 1 : 0, f.has('\\Draft') ? 1 : 0]);
          }
        }
      }
      return { ordner: pfad, uidValidity: Number(box.uidValidity), uidNext: Number(box.uidNext), gesamt: box.exists, eintraege: eintraege.sort((a, b) => a[0] - b[0]) };
    } finally { sperre.release(); }
  });
}

const kopf = (m, name) => { const w = m.headers && m.headers.get(name); return w == null ? '' : typeof w === 'string' ? w : w.text || w.value || String(w); };
/** Wichtigkeit einer Mail (HOCH/NIEDRIG/normal) aus Priority-, Importance- und X-Priority-Kopf. */
function wichtigkeit(m) {
  const p = (m.priority || kopf(m, 'priority')).toLowerCase();
  if (p === 'high') return 'HOCH';
  if (p === 'low') return 'NIEDRIG';
  const imp = kopf(m, 'importance').toLowerCase(), prio = kopf(m, 'x-priority');
  if (imp === 'high' || /^[12]/.test(prio)) return 'HOCH';
  if (imp === 'low' || /^[45]/.test(prio)) return 'NIEDRIG';
  return '';
}
/** Abmelde-Adresse eines Newsletters (List-Unsubscribe: https oder mailto). */
function abmelden(m) {
  const liste = m.headers && m.headers.get('list');
  if (liste && liste.unsubscribe) { const u = liste.unsubscribe; return u.url && /^https:/i.test(u.url) ? u.url : u.mail ? 'mailto:' + u.mail : ''; }
  const l = m.headers && m.headers.get('list-unsubscribe');
  const roh = l ? (typeof l === 'string' ? l : [].concat(l.url || [], l.mail ? 'mailto:' + l.mail : [], l.value || []).join(',')) : '';
  const teile = String(roh).match(/<([^>]+)>/g) ? String(roh).match(/<([^>]+)>/g).map(x => x.slice(1, -1)) : String(roh).split(',').map(x => x.trim());
  return teile.find(x => /^https:/i.test(x)) || teile.find(x => /^mailto:/i.test(x)) || '';
}
const adressenText = a => (!a ? '' : (Array.isArray(a) ? a : [a]).map(x => x.text).filter(Boolean).join(', '));
const echteAnhaenge = m => (m.attachments || []).filter(a => a.content && a.content.length);

/** Eine geparste Mail im Format der Oberfläche (Schnittstelle 2). */
function alsMail(m, { uid, ordner: pfad, flags, groesse, maxAnhang = 0 }) {
  const von = (m.from && m.from.value && m.from.value[0]) || {};
  const bezug = [m.inReplyTo, ...(Array.isArray(m.references) ? m.references : m.references ? [m.references] : [])]
    .filter(Boolean).flatMap(z => String(z).match(/<[^>]+>/g) || [z]).map(ohneKlammern);
  const f = flags || new Set();
  const html = typeof m.html === 'string' ? m.html : '';
  return {
    uid, ordner: pfad,
    messageId: ohneKlammern(m.messageId) || `uid-${pfad}-${uid}@lokal`,
    antwortAuf: [...new Set(bezug)],
    von: String(von.address || '').toLowerCase(), vonName: von.name || '',
    an: adressenText(m.to), cc: adressenText(m.cc), antwortAn: adressenText(m.replyTo),
    betreff: m.subject || '',
    text: (m.text || (html ? htmlAlsText(html) : '')).replace(/ /g, ' ').trim(),
    html: html.length <= MAX_HTML ? html : '',
    zeitpunkt: (m.date || new Date()).toISOString(), groesse: groesse || 0,
    gelesen: f.has('\\Seen'), markiert: f.has('\\Flagged'), beantwortet: f.has('\\Answered'), entwurf: f.has('\\Draft'),
    wichtig: wichtigkeit(m), lesebestaetigung: ohneKlammern(kopf(m, 'disposition-notification-to').replace(/^.*</, '').replace(/>.*$/, '')), abmelden: abmelden(m),
    anhaenge: echteAnhaenge(m).map(a => ({
      name: a.filename || (a.cid ? 'Bild' : 'Anhang'), mimeType: (a.contentType || 'application/octet-stream').toLowerCase(), groesse: a.content.length,
      cid: a.cid ? ohneKlammern(a.cid) : '', inline: a.contentDisposition === 'inline' || (!!a.cid && !a.filename),
      ...(a.content.length <= maxAnhang ? { base64: a.content.toString('base64') } : {}),
    })),
  };
}

/** Vollständige Mails zu UIDs (Anhänge bis maxAnhang Bytes direkt, größere später über „anhang“). */
async function lade(k, passwort, { ordner: pfad = 'INBOX', uids = [], maxAnhang = 3 * 1024 * 1024 } = {}) {
  if (!uids.length) return [];
  return mitImap(k, passwort, async client => {
    const sperre = await client.getMailboxLock(pfad, { readOnly: true });
    try {
      const roh = [];
      for await (const m of client.fetch(uids.map(Number), { uid: true, flags: true, size: true, source: true }, { uid: true })) roh.push(m);
      const mails = [];
      for (const r of roh) {
        if (!r.source) continue;
        mails.push(alsMail(await simpleParser(r.source), { uid: r.uid, ordner: pfad, flags: r.flags, groesse: r.size, maxAnhang }));
      }
      return mails;
    } finally { sperre.release(); }
  });
}

/** Suche auf dem Server; liefert UIDs, neueste zuerst. */
async function suche(k, passwort, { ordner: pfad = 'INBOX', text = '', von = '', an = '', betreff = '', seit = '', bis = '', ungelesen = false, markiert = false, max = 200 } = {}) {
  return mitImap(k, passwort, async client => {
    const sperre = await client.getMailboxLock(pfad, { readOnly: true });
    try {
      const q = {};
      if (text) q.text = text; if (von) q.from = von; if (an) q.to = an; if (betreff) q.subject = betreff;
      if (seit) q.since = new Date(seit); if (bis) q.before = new Date(bis);
      if (ungelesen) q.seen = false; if (markiert) q.flagged = true;
      if (!Object.keys(q).length) q.all = true;
      const uids = (await client.search(q, { uid: true })) || [];
      return uids.sort((a, b) => b - a).slice(0, max);
    } finally { sperre.release(); }
  });
}

const FLAG_AKTION = { gelesen: ['add', '\\Seen'], ungelesen: ['remove', '\\Seen'], markieren: ['add', '\\Flagged'], entmarkieren: ['remove', '\\Flagged'], beantwortet: ['add', '\\Answered'] };
/** Aktion auf dem Server; bei verschieben/kopieren die neuen UIDs im Zielordner (falls der Server sie meldet). */
async function aktion(k, passwort, { ordner: pfad = 'INBOX', uids = [], aktion: was, ziel = '' } = {}) {
  if (!uids.length) return { neueUids: {} };
  return mitImap(k, passwort, async client => {
    const sperre = await client.getMailboxLock(pfad);
    try {
      const liste = uids.map(Number), o = { uid: true };
      if (FLAG_AKTION[was]) {
        const [art, flag] = FLAG_AKTION[was];
        if (art === 'add') await client.messageFlagsAdd(liste, [flag], o); else await client.messageFlagsRemove(liste, [flag], o);
        return { neueUids: {} };
      }
      if (was === 'verschieben' || was === 'kopieren') {
        if (!ziel) throw new MailFehler('Kein Zielordner angegeben');
        const r = was === 'verschieben' ? await client.messageMove(liste, ziel, o) : await client.messageCopy(liste, ziel, o);
        return { neueUids: r && r.uidMap ? Object.fromEntries([...r.uidMap.entries()]) : {} };
      }
      if (was === 'loeschen') { await client.messageDelete(liste, o); return { neueUids: {} }; }
      throw new MailFehler('Unbekannte Aktion: ' + was);
    } finally { sperre.release(); }
  });
}

/** Ordner anlegen, umbenennen oder löschen. */
async function ordnerAktion(k, passwort, { aktion: was, pfad, neu } = {}) {
  return mitImap(k, passwort, async client => {
    if (was === 'anlegen') { await client.mailboxCreate(pfad); return true; }
    if (was === 'umbenennen') { await client.mailboxRename(pfad, neu); return true; }
    if (was === 'loeschen') { await client.mailboxDelete(pfad); return true; }
    throw new MailFehler('Unbekannte Ordneraktion: ' + was);
  });
}

/** Rohe Mail (Quelltext) vom Server, nur lesend geöffnet. */
async function quelleRoh(client, pfad, uid) {
  const sperre = await client.getMailboxLock(pfad, { readOnly: true });
  try {
    const m = await client.fetchOne(String(uid), { source: true }, { uid: true });
    if (!m || !m.source) throw new MailFehler('Die E-Mail gibt es auf dem Server nicht mehr.');
    return m.source;
  } finally { sperre.release(); }
}
/** Einen Anhang einer Mail als Base64 (für Vorschau/Speichern). */
async function anhang(k, passwort, { ordner: pfad = 'INBOX', uid, nr = 0 } = {}) {
  return mitImap(k, passwort, async client => {
    const a = echteAnhaenge(await simpleParser(await quelleRoh(client, pfad, uid)))[Number(nr)];
    if (!a) throw new MailFehler('Anhang nicht gefunden');
    return { name: a.filename || 'Anhang', mimeType: (a.contentType || 'application/octet-stream').toLowerCase(), base64: a.content.toString('base64') };
  });
}
/** Quelltext einer Mail als Base64 (Mail speichern als .eml). */
async function quelle(k, passwort, { ordner: pfad = 'INBOX', uid } = {}) {
  return mitImap(k, passwort, async client => (await quelleRoh(client, pfad, uid)).toString('base64'));
}

/** Einheitlicher Einstieg für die Oberfläche: mail.ruf(befehl, daten, konto). */
const BEFEHLE = { ordner, stand, lade, suche, aktion, ordnerAktion, anhang, quelle, sende: (k, p, d) => sende(k, p, d) };
/** Führt einen Mail-Befehl der Oberfläche aus (nur bekannte Befehle aus BEFEHLE). */
async function ruf(k, passwort, befehl, daten) {
  const f = Object.prototype.hasOwnProperty.call(BEFEHLE, befehl) && BEFEHLE[befehl];
  if (!f) throw new MailFehler('Unbekannter Befehl: ' + befehl);
  return befehl === 'ordner' ? f(k, passwort) : f(k, passwort, daten || {});
}

/** Einfacher Text aus HTML (für Vorschau und Suche): Skripte/Stile weg, Umbrüche erhalten, Entitäten aufgelöst. */
function htmlAlsText(html) {
  return String(html)
    .replace(/<(script|style)[^>]*>[\s\S]*?<\/\1>/gi, '')
    .replace(/<br\s*\/?>|<\/p>|<\/div>|<\/tr>|<\/li>/gi, '\n')
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'")
    .split('\n').map(z => z.trim()).join('\n').replace(/\n{3,}/g, '\n\n').trim();
}

module.exports = { ANBIETER, MailFehler, SCHNITTSTELLE: 2, pruefe, sende, ruf, ordner, stand, lade, suche, aktion, ordnerAktion, anhang, quelle, htmlAlsText, ohneKlammern };
