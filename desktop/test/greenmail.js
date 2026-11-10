'use strict';
/**
 * Startet GreenMail (echter IMAP-/SMTP-Testserver, Java) mit dem Postfach der Firma.
 * Das Programm wird beim ersten Lauf aus Maven Central geladen und in test/.cache abgelegt.
 */
const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const https = require('node:https');
const { spawn } = require('node:child_process');

const VERSION = '2.1.15';
const JAR = path.join(__dirname, '.cache', `greenmail-standalone-${VERSION}.jar`);
const QUELLEN = [
  `https://repo1.maven.org/maven2/com/icegreen/greenmail-standalone/${VERSION}/greenmail-standalone-${VERSION}.jar`,
  `https://maven-central.storage-download.googleapis.com/maven2/com/icegreen/greenmail-standalone/${VERSION}/greenmail-standalone-${VERSION}.jar`,
];

function lade(url, ziel) {
  return new Promise((ok, fehler) => {
    https.get(url, r => {
      if (r.statusCode >= 300 && r.statusCode < 400 && r.headers.location) { r.resume(); return lade(r.headers.location, ziel).then(ok, fehler); }
      if (r.statusCode !== 200) { r.resume(); return fehler(new Error(`${url}: ${r.statusCode}`)); }
      const tmp = ziel + '.teil'; const aus = fs.createWriteStream(tmp);
      r.pipe(aus); aus.on('finish', () => { aus.close(); fs.renameSync(tmp, ziel); ok(); }); aus.on('error', fehler);
    }).on('error', fehler);
  });
}

async function jar() {
  if (fs.existsSync(JAR)) return JAR;
  fs.mkdirSync(path.dirname(JAR), { recursive: true });
  let letzter;
  for (const url of QUELLEN) { try { await lade(url, JAR); return JAR; } catch (e) { letzter = e; } }
  throw letzter;
}

const freierPort = () => new Promise(ok => { const s = net.createServer().listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => ok(p)); }); });
const wartePort = async port => {
  for (let i = 0; i < 150; i++) {
    const offen = await new Promise(ok => { const c = net.connect(port, '127.0.0.1', () => { c.end(); ok(true); }); c.on('error', () => ok(false)); });
    if (offen) return;
    await new Promise(r => setTimeout(r, 200));
  }
  throw new Error('GreenMail startet nicht');
};

async function starteGreenMail() {
  const [smtp, imap] = [await freierPort(), await freierPort()];
  const adresse = 'schaden@firma.example', passwort = 'geheim-123';
  const prozess = spawn('java', [
    `-Dgreenmail.smtp.hostname=127.0.0.1`, `-Dgreenmail.smtp.port=${smtp}`,
    `-Dgreenmail.imap.hostname=127.0.0.1`, `-Dgreenmail.imap.port=${imap}`,
    `-Dgreenmail.users=${adresse}:${passwort}`, '-Dgreenmail.users.login=email', '-Dgreenmail.verbose=false',
    '-jar', await jar(),
  ], { stdio: 'ignore' });
  await wartePort(smtp); await wartePort(imap);
  return {
    adresse, passwort,
    konfig: { adresse, benutzer: adresse, name: 'Muster Transport GmbH', imapHost: '127.0.0.1', imapPort: imap, imapSicherheit: 'KEINE', smtpHost: '127.0.0.1', smtpPort: smtp, smtpSicherheit: 'KEINE' },
    smtpPort: smtp, imapPort: imap,
    beenden: () => new Promise(ok => { prozess.once('exit', ok); prozess.kill(); }),
  };
}

module.exports = { starteGreenMail };
