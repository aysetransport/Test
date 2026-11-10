'use strict';
/**
 * Lokaler Ersatz für die bereitgestellte Web-App des Cloud-Skripts.
 *
 * Verhält sich an den für die Apps wichtigen Stellen wie Google: POST auf /exec wird mit einer
 * Weiterleitung beantwortet, das Ergebnis liegt hinter der Zieladresse; eine CORS-Vorabfrage
 * (OPTIONS) wird nicht unterstützt – die Apps müssen deshalb als text/plain senden.
 *
 * Start von Hand:  node cloud/test/mock-server.js [port]
 */
const http = require('node:http');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { erstelleUmgebung } = require('./gas-umgebung');

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.json': 'application/json',
  '.webmanifest': 'application/manifest+json', '.png': 'image/png', '.svg': 'image/svg+xml', '.css': 'text/css' };

function lausche(server, port) {
  return new Promise((ok, fehler) => {
    server.once('error', fehler);
    server.listen(port, '127.0.0.1', () => ok(server.address().port));
  });
}

async function starteCloud({ port = 0, umgebung = erstelleUmgebung() } = {}) {
  const ergebnisse = new Map();
  const zustand = { gestoert: false, anfragen: [] };
  const server = http.createServer((req, res) => {
    const adresse = new URL(req.url, 'http://x');
    const cors = { 'Access-Control-Allow-Origin': '*' };
    if (zustand.gestoert) { req.socket.destroy(); return; } // wie ein Netzausfall
    if (req.method === 'OPTIONS') { res.writeHead(405).end(); return; }
    if (req.method === 'GET' && adresse.pathname === '/echo') {
      const inhalt = ergebnisse.get(adresse.searchParams.get('schluessel'));
      ergebnisse.delete(adresse.searchParams.get('schluessel'));
      if (inhalt == null) { res.writeHead(404, cors).end(); return; }
      res.writeHead(200, { ...cors, 'Content-Type': 'application/json; charset=utf-8' }).end(inhalt);
      return;
    }
    if (req.method === 'GET' && adresse.pathname === '/exec') {
      res.writeHead(200, { ...cors, 'Content-Type': 'text/plain; charset=utf-8' }).end(umgebung.kontext.doGet().getContent());
      return;
    }
    if (req.method === 'POST' && adresse.pathname === '/exec') {
      const teile = [];
      req.on('data', t => teile.push(t));
      req.on('end', () => {
        const text = Buffer.concat(teile).toString('utf8');
        try { zustand.anfragen.push(JSON.parse(text).fn); } catch { zustand.anfragen.push('?'); }
        const schluessel = crypto.randomUUID();
        ergebnisse.set(schluessel, JSON.stringify(umgebung.post(text)));
        res.writeHead(302, { ...cors, Location: `/echo?schluessel=${schluessel}` }).end();
      });
      return;
    }
    res.writeHead(404, cors).end();
  });
  const benutzterPort = await lausche(server, port);
  return {
    url: `http://127.0.0.1:${benutzterPort}/exec`,
    umgebung, zustand,
    schliessen: () => new Promise(ok => { server.closeAllConnections(); server.close(ok); }),
  };
}

/** Liefert die Dateien der Web-App aus (eigener Port, damit der Zugriff auf die Cloud wie im Betrieb fremd-origin ist). */
async function starteWebseite(ordner, { port = 0 } = {}) {
  const server = http.createServer((req, res) => {
    let pfad = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    if (pfad.endsWith('/')) pfad += 'index.html';
    const datei = path.join(ordner, path.normalize(pfad));
    if (!datei.startsWith(ordner) || !fs.existsSync(datei) || fs.statSync(datei).isDirectory()) { res.writeHead(404).end(); return; }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(datei)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
    fs.createReadStream(datei).pipe(res);
  });
  const benutzterPort = await lausche(server, port);
  return { url: `http://127.0.0.1:${benutzterPort}/`, schliessen: () => new Promise(ok => { server.closeAllConnections(); server.close(ok); }) };
}

module.exports = { starteCloud, starteWebseite };

if (require.main === module) {
  starteCloud({ port: Number(process.argv[2]) || 0 }).then(cloud => {
    console.log('CLOUD ' + cloud.url); // wird von den Android-Tests ausgewertet
  });
}
