'use strict';
/**
 * Nachbildung der Google-Apps-Script-Umgebung für Tests: lädt ../Code.gs unverändert und stellt
 * die benutzten Google-Dienste (Drive, Eigenschaften, Sperre, Hilfsfunktionen) im Arbeitsspeicher
 * bereit. So lässt sich das echte Cloud-Skript ohne Google-Konto prüfen.
 */
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const SKRIPT = path.join(__dirname, '..', 'Code.gs');

function iterator(liste) {
  let i = 0;
  return { hasNext: () => i < liste.length, next: () => liste[i++] };
}

function erstelleUmgebung(zusatz = {}) {
  let laufendeId = 1;
  let zeitversatz = 0;
  const nachId = new Map();

  const blob = (bytes, mime, name) => ({
    getBytes: () => Array.from(new Int8Array(bytes)), // Apps Script liefert vorzeichenbehaftete Bytes
    getDataAsString: () => bytes.toString('utf8'),
    getContentType: () => mime,
    getName: () => name,
  });

  class Datei {
    constructor(name, bytes, mime) {
      this.id = 'datei-' + laufendeId++; this.name = name; this.bytes = bytes; this.mime = mime; this.papierkorb = false;
      nachId.set(this.id, this);
    }
    getId() { return this.id; }
    getName() { return this.name; }
    getBlob() { return blob(this.bytes, this.mime, this.name); }
    setContent(text) { this.bytes = Buffer.from(text, 'utf8'); return this; }
    setTrashed(wert) { this.papierkorb = wert; return this; }
    isTrashed() { return this.papierkorb; }
  }

  class Ordner {
    constructor(name) {
      this.id = 'ordner-' + laufendeId++; this.name = name; this.ordner = []; this.dateien = []; this.papierkorb = false;
      nachId.set(this.id, this);
    }
    getId() { return this.id; }
    getName() { return this.name; }
    isTrashed() { return this.papierkorb; }
    setTrashed(wert) { this.papierkorb = wert; return this; }
    getFoldersByName(name) { return iterator(this.ordner.filter(o => o.name === name && !o.papierkorb)); }
    getFilesByName(name) { return iterator(this.dateien.filter(d => d.name === name && !d.papierkorb)); }
    getFiles() { return iterator(this.dateien.filter(d => !d.papierkorb)); }
    createFolder(name) { const o = new Ordner(name); this.ordner.push(o); return o; }
    createFile(nameOderBlob, inhalt, mime) {
      const datei = typeof nameOderBlob === 'string'
        ? new Datei(nameOderBlob, Buffer.from(String(inhalt), 'utf8'), mime)
        : new Datei(nameOderBlob.getName(), Buffer.from(nameOderBlob.getBytes()), nameOderBlob.getContentType());
      this.dateien.push(datei);
      return datei;
    }
  }

  const wurzel = new Ordner('Meine Ablage');
  const holen = (id, Klasse) => {
    const treffer = nachId.get(id);
    if (!(treffer instanceof Klasse)) throw new Error('Kein Element mit der angegebenen ID gefunden.');
    return treffer;
  };
  const eigenschaften = new Map();

  class TestDatum extends Date {
    constructor(...args) { if (args.length === 0) super(Date.now() + zeitversatz); else super(...args); }
    static now() { return Date.now() + zeitversatz; }
  }

  const kontext = vm.createContext({
    Date: TestDatum, JSON, Math, Object, Array, String, Number, Boolean, Error, RegExp, isFinite, isNaN,
    DriveApp: {
      getFoldersByName: name => wurzel.getFoldersByName(name),
      createFolder: name => wurzel.createFolder(name),
      getFolderById: id => holen(id, Ordner),
      getFileById: id => holen(id, Datei),
    },
    PropertiesService: {
      getScriptProperties: () => ({
        getProperty: k => (eigenschaften.has(k) ? eigenschaften.get(k) : null),
        setProperty: (k, v) => { eigenschaften.set(k, String(v)); },
        deleteProperty: k => { eigenschaften.delete(k); },
        getProperties: () => Object.fromEntries(eigenschaften),
      }),
    },
    LockService: { getScriptLock: () => ({ waitLock() {}, releaseLock() {} }) },
    // Selbst-Update: Tests geben UrlFetchApp/ScriptApp vor (sonst nicht vorhanden)
    ...zusatz,
    ContentService: {
      MimeType: { JSON: 'application/json' },
      createTextOutput: text => ({ text, mime: 'text/plain', setMimeType(m) { this.mime = m; return this; }, getContent() { return this.text; } }),
    },
    Utilities: {
      DigestAlgorithm: { SHA_256: 'sha256' },
      Charset: { UTF_8: 'utf8' },
      computeDigest: (algorithmus, text) => Array.from(new Int8Array(crypto.createHash(algorithmus).update(String(text), 'utf8').digest())),
      base64Encode: bytes => Buffer.from(bytes).toString('base64'),
      base64Decode: text => Array.from(new Int8Array(Buffer.from(String(text), 'base64'))),
      newBlob: (bytes, mime, name) => blob(Buffer.from(bytes), mime, name),
      getUuid: () => crypto.randomUUID(),
      formatDate: (datum, zone, format) => new Date(datum.getTime() + 3600000).toISOString().slice(0, 10), // nur 'yyyy-MM-dd' (Europe/Berlin, Winterzeit)
    },
  });
  vm.runInContext(fs.readFileSync(SKRIPT, 'utf8'), kontext, { filename: 'Code.gs' });

  /** Ruft das Skript so auf, wie es die Apps tun: über doPost mit {fn, args}. */
  const post = text => JSON.parse(kontext.doPost({ postData: { contents: text } }).getContent());
  const aufruf = (fn, ...args) => {
    const antwort = post(JSON.stringify({ fn, args }));
    if (antwort.error) throw new Error(antwort.error);
    return antwort.ok;
  };

  return {
    kontext, post, aufruf, wurzel, eigenschaften,
    /** Stellt die Uhr des Skripts um die angegebenen Millisekunden vor. */
    zeitVor(ms) { zeitversatz += ms; },
    /** Sucht eine Datei über ihren Pfad unterhalb von „Meine Ablage“, z. B. 'Schadensfaelle/daten.json'. */
    datei(pfad) {
      const teile = pfad.split('/');
      let ordner = wurzel;
      for (const name of teile.slice(0, -1)) ordner = ordner.ordner.find(o => o.name === name && !o.papierkorb) || { ordner: [], dateien: [] };
      return ordner.dateien.find(d => d.name === teile.at(-1) && !d.papierkorb) || null;
    },
    dateien(pfad) {
      let ordner = wurzel;
      for (const name of pfad.split('/')) ordner = ordner.ordner.find(o => o.name === name && !o.papierkorb) || { ordner: [], dateien: [] };
      return ordner.dateien.filter(d => !d.papierkorb);
    },
  };
}

module.exports = { erstelleUmgebung };
