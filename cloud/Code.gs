/**
 * Schadensfälle – Cloud-Speicher (Google Apps Script)
 *
 * Dieses Skript ist die gemeinsame Datenablage für die Android-App und die Web-App.
 * Alle Daten liegen im Google Drive des Kontos, das das Skript bereitstellt:
 *
 *   Schadensfaelle/
 *     daten.json        alle Fälle, Verlaufseinträge, Anlagen-Verzeichnis und das CRM (Firmen,
 *                       Ansprechpartner, Adressen, Preise, Aktivitäten, Wiedervorlagen, Dokumente)
 *     anlagen/          Fotos und Belege (Dateiname = Kennung der Anlage)
 *     sicherungen/      tägliche Kopie von daten.json (die letzten 14 Tage)
 *
 * Bereitstellen: Bereitstellen → Neue Bereitstellung → Typ „Web-App“,
 * „Ausführen als: Ich“, „Zugriff: Jeder“. Die Adresse (…/exec) in den Apps eintragen.
 * Die PIN wird beim ersten Verbinden in der App festgelegt.
 *
 * Aufruf (wie bei Tagesdispo): POST mit Text-Inhalt {"fn": "<name>", "args": [PIN, …]}
 * Antwort: {"ok": …} oder {"error": "<Meldung>"}.
 */

var ORDNER_NAME = 'Schadensfaelle';
var DATEN_DATEI = 'daten.json';
var ANLAGEN_ORDNER = 'anlagen';
var SICHERUNG_ORDNER = 'sicherungen';
var SICHERUNGEN_BEHALTEN = 14;

var MIN_PIN_LAENGE = 4;
var MAX_FEHLVERSUCHE = 8;
var SPERRE_MINUTEN = 60;

var MAX_ANLAGE_BYTES = 25 * 1024 * 1024;
var MAX_TEXT = 20000;
var API_VERSION = 4; // 2: Kontaktdaten des Auftraggebers und Auftraggeber-Liste; 3: Zeichen des Auftraggebers; 4: CRM

/**
 * CRM-Tabellen: alle hängen über firmaId an einem Auftraggeber (Tabelle „auftraggeber“).
 * Feldarten: text, wort:<Vorgabe> (Aufzählung in Großbuchstaben), datum (JJJJ-MM-TT), cent (ganze Zahl), bool.
 */
var CRM_TABELLEN = {
  adressen: { art: 'wort:SONSTIGE', strasse: 'text', plz: 'text', ort: 'text', notiz: 'text' },
  ansprechpartner: { name: 'text', thema: 'text', telefon: 'text', email: 'text', notiz: 'text' },
  preise: { bezeichnung: 'text', betragCent: 'cent', einheit: 'text', gueltigAb: 'datum', notiz: 'text' },
  aktivitaeten: { datum: 'datum', art: 'wort:SONSTIGES', text: 'text', kontakt: 'text' },
  aufgaben: { faelligAm: 'datum', text: 'text', kontakt: 'text', erledigt: 'bool' },
  dokumente: { titel: 'text', link: 'text', notiz: 'text' }
};
var CRM_NAMEN = Object.keys(CRM_TABELLEN);

// ============================== Einstieg ==============================

function doPost(e) {
  var antwort;
  try {
    var anfrage = JSON.parse(e.postData.contents);
    antwort = { ok: aufruf_(String(anfrage.fn), anfrage.args || []) };
  } catch (fehler) {
    antwort = { error: String((fehler && fehler.message) || fehler) };
  }
  return ContentService.createTextOutput(JSON.stringify(antwort)).setMimeType(ContentService.MimeType.JSON);
}

function doGet() {
  return ContentService.createTextOutput('Schadensfälle-Cloud läuft. Diese Adresse in der App eintragen.');
}

/** Ohne PIN erreichbar: verrät nur, ob schon eine PIN festgelegt wurde. */
var OFFEN = { status: status, pinFestlegen: pinFestlegen };

/** Nur mit gültiger PIN. Das erste Argument jedes Aufrufs ist die PIN. */
var GESCHUETZT = {
  anmelden: anmelden,
  stand: stand,
  sync: sync,
  anlageHochladen: anlageHochladen,
  anlageLaden: anlageLaden
};

function aufruf_(fn, args) {
  if (Object.prototype.hasOwnProperty.call(OFFEN, fn)) return OFFEN[fn].apply(null, args);
  if (!Object.prototype.hasOwnProperty.call(GESCHUETZT, fn)) throw new Error('Unbekannte Funktion: ' + fn);
  pruefePin_(args[0]);
  return GESCHUETZT[fn].apply(null, args.slice(1));
}

// ============================== PIN ==============================

function status() {
  return {
    name: 'Schadensfälle',
    api: API_VERSION,
    eingerichtet: !!PropertiesService.getScriptProperties().getProperty('PIN_HASH')
  };
}

/** Legt die PIN fest – nur möglich, solange noch keine vergeben ist. */
function pinFestlegen(pin) {
  pin = String(pin == null ? '' : pin);
  if (pin.length < MIN_PIN_LAENGE) throw new Error('Die PIN braucht mindestens ' + MIN_PIN_LAENGE + ' Zeichen.');
  var sperre = LockService.getScriptLock();
  sperre.waitLock(20000);
  try {
    var props = PropertiesService.getScriptProperties();
    if (props.getProperty('PIN_HASH')) throw new Error('Es ist bereits eine PIN festgelegt.');
    var salz = Utilities.getUuid();
    props.setProperty('PIN_SALZ', salz);
    props.setProperty('PIN_HASH', hash_(pin, salz));
    return { eingerichtet: true };
  } finally {
    sperre.releaseLock();
  }
}

/**
 * Im Skript-Editor ausführen, wenn die PIN vergessen wurde. Danach kann in der App eine neue
 * festgelegt werden. Die Daten bleiben erhalten.
 */
function pinZuruecksetzen() {
  var props = PropertiesService.getScriptProperties();
  props.deleteProperty('PIN_HASH');
  props.deleteProperty('PIN_SALZ');
  props.deleteProperty('FEHLVERSUCHE');
}

function pruefePin_(pin) {
  var props = PropertiesService.getScriptProperties();
  var jetzt = Date.now();
  var zaehler = JSON.parse(props.getProperty('FEHLVERSUCHE') || '{"n":0,"bis":0}');
  if (zaehler.bis > jetzt) {
    throw new Error('GESPERRT: Zu viele falsche PIN-Eingaben. Bitte in ' +
      Math.ceil((zaehler.bis - jetzt) / 60000) + ' Minuten erneut versuchen.');
  }
  var gespeichert = props.getProperty('PIN_HASH');
  if (!gespeichert) throw new Error('PIN noch nicht festgelegt');
  if (hash_(String(pin == null ? '' : pin), props.getProperty('PIN_SALZ')) === gespeichert) {
    if (zaehler.n) props.deleteProperty('FEHLVERSUCHE');
    return;
  }
  zaehler.n = (zaehler.bis && zaehler.bis <= jetzt ? 0 : zaehler.n) + 1;
  zaehler.bis = zaehler.n >= MAX_FEHLVERSUCHE ? jetzt + SPERRE_MINUTEN * 60000 : 0;
  props.setProperty('FEHLVERSUCHE', JSON.stringify(zaehler));
  throw new Error('PIN falsch');
}

function hash_(pin, salz) {
  var bytes = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, salz + '|' + pin, Utilities.Charset.UTF_8);
  return Utilities.base64Encode(bytes);
}

// ============================== Abgleich ==============================

function anmelden() {
  var d = ladeDaten_();
  return { name: 'Schadensfälle', api: API_VERSION, kennung: d.kennung, rev: d.rev };
}

/** Schnelle Abfrage, ob es Neues gibt. */
function stand() {
  var d = ladeDaten_();
  return { kennung: d.kennung, rev: d.rev };
}

/**
 * Nimmt die lokalen Änderungen eines Geräts entgegen und liefert alles zurück, was sich seit
 * dem letzten Abgleich dieses Geräts geändert hat.
 *
 * anfrage: { seit, kennung, faelle: […], verlauf: […], anlagen: […], auftraggeber: […], adressen: […], … }
 * antwort: dieselben Listen mit allem, was sich seit „seit“ geändert hat, dazu kennung und rev
 *
 * Regeln: Bei einem Fall gewinnt der Stand mit dem späteren Änderungszeitpunkt. Gelöscht
 * bleibt gelöscht. Verlaufseinträge und Anlagen werden nur hinzugefügt oder gelöscht.
 */
function sync(anfrage) {
  anfrage = anfrage || {};
  var sperre = LockService.getScriptLock();
  sperre.waitLock(25000);
  try {
    var d = ladeDaten_();
    // Kennt das Gerät einen anderen Datenbestand (z. B. nach neu angelegtem Ordner), bekommt es alles.
    var seit = anfrage.kennung === d.kennung ? Math.max(0, Number(anfrage.seit) || 0) : 0;
    var vorher = d.rev;
    var zurueck = { faelle: {}, verlauf: {}, anlagen: {}, auftraggeber: {} };

    liste_(anfrage.faelle).forEach(function (roh) { uebernehmeFall_(d, roh, zurueck.faelle); });
    liste_(anfrage.verlauf).forEach(function (roh) { uebernehmeVerlauf_(d, roh); });
    liste_(anfrage.anlagen).forEach(function (roh) { uebernehmeAnlage_(d, roh); });
    // Gelöschte Firmen zuletzt: Führt ein Gerät zwei gleichnamige Firmen zusammen, schickt es die
    // umgehängten Einträge zusammen mit der Löschung der zweiten Firma – sie dürfen nicht mitgelöscht werden.
    var firmen = liste_(anfrage.auftraggeber);
    firmen.filter(function (roh) { return !(roh && roh.geloescht === true); })
      .forEach(function (roh) { uebernehmeAuftraggeber_(d, roh, zurueck.auftraggeber); });
    CRM_NAMEN.forEach(function (t) {
      zurueck[t] = {};
      liste_(anfrage[t]).forEach(function (roh) { uebernehmeCrm_(d, t, roh, zurueck[t]); });
    });
    firmen.filter(function (roh) { return roh && roh.geloescht === true; })
      .forEach(function (roh) { uebernehmeAuftraggeber_(d, roh, zurueck.auftraggeber); });

    if (d.rev !== vorher) speichereDaten_(d);
    var antwort = {
      kennung: d.kennung,
      rev: d.rev,
      faelle: auswahl_(d.faelle, seit, zurueck.faelle),
      verlauf: auswahl_(d.verlauf, seit, zurueck.verlauf),
      anlagen: auswahl_(d.anlagen, seit, zurueck.anlagen).map(ohneInterna_),
      auftraggeber: auswahl_(d.auftraggeber, seit, zurueck.auftraggeber)
    };
    CRM_NAMEN.forEach(function (t) { antwort[t] = auswahl_(d[t], seit, zurueck[t]); });
    return antwort;
  } finally {
    sperre.releaseLock();
  }
}

function uebernehmeFall_(d, roh, zurueck) {
  var neu = bereinigeFall_(roh);
  var alt = d.faelle[neu.id];
  if (alt && alt.geloescht) {
    if (!neu.geloescht) zurueck[neu.id] = true; // dem Gerät mitteilen, dass der Fall gelöscht ist
    return;
  }
  if (neu.geloescht) {
    loescheFall_(d, neu.id, neu.geaendertAm);
    return;
  }
  if (alt && zeit_(neu.geaendertAm) <= zeit_(alt.geaendertAm)) {
    if (!gleicherInhalt_(alt, neu)) zurueck[neu.id] = true; // Server ist neuer: dessen Stand zurückgeben
    return;
  }
  if (alt) neu.erstelltAm = alt.erstelltAm;
  neu.fallnummer = eindeutigeFallnummer_(d, neu);
  neu.rev = ++d.rev;
  d.faelle[neu.id] = neu;
}

/**
 * Firmen (Auftraggeber): wie bei Fällen gewinnt die spätere Änderung, Gelöschtes bleibt gelöscht.
 * Mit einer Firma wird alles gelöscht, was an ihr hängt (Ansprechpartner, Adressen …).
 */
function uebernehmeAuftraggeber_(d, roh, zurueck) {
  roh = roh || {};
  var neu = { id: kennung_(roh.id), geaendertAm: zeitpunkt_(roh.geaendertAm) };
  var alt = d.auftraggeber[neu.id];
  if (alt && alt.geloescht) { if (roh.geloescht !== true) zurueck[neu.id] = true; return; }
  if (roh.geloescht === true) {
    d.auftraggeber[neu.id] = { id: neu.id, geloescht: true, geaendertAm: neu.geaendertAm, rev: ++d.rev };
    CRM_NAMEN.forEach(function (t) {
      Object.keys(d[t]).forEach(function (k) {
        var e = d[t][k];
        if (e.firmaId === neu.id && !e.geloescht) d[t][k] = { id: k, firmaId: neu.id, geloescht: true, geaendertAm: neu.geaendertAm, rev: ++d.rev };
      });
    });
    return;
  }
  neu.name = text_(roh.name).slice(0, 200);
  neu.rechtsform = text_(roh.rechtsform).slice(0, 100);
  neu.status = wort_(roh.status, 'AKTIV');
  neu.telefon = text_(roh.telefon).slice(0, 100);
  neu.email = text_(roh.email).slice(0, 200);
  neu.website = text_(roh.website).slice(0, 300);
  neu.notizen = text_(roh.notizen);
  if (alt && zeit_(neu.geaendertAm) <= zeit_(alt.geaendertAm)) {
    if (!gleicherInhalt_(alt, neu)) zurueck[neu.id] = true;
    return;
  }
  neu.rev = ++d.rev;
  d.auftraggeber[neu.id] = neu;
}

/** CRM-Einträge: die spätere Änderung gewinnt; was zu einer gelöschten Firma kommt, gilt als gelöscht. */
function uebernehmeCrm_(d, tabelle, roh, zurueck) {
  roh = roh || {};
  var neu = { id: kennung_(roh.id), firmaId: kennung_(roh.firmaId), geaendertAm: zeitpunkt_(roh.geaendertAm) };
  var alt = d[tabelle][neu.id];
  var firma = d.auftraggeber[neu.firmaId];
  if (alt && alt.geloescht) { if (roh.geloescht !== true) zurueck[neu.id] = true; return; }
  if (roh.geloescht === true || (firma && firma.geloescht)) {
    if (roh.geloescht !== true) zurueck[neu.id] = true;
    d[tabelle][neu.id] = { id: neu.id, firmaId: neu.firmaId, geloescht: true, geaendertAm: neu.geaendertAm, rev: ++d.rev };
    return;
  }
  var felder = CRM_TABELLEN[tabelle];
  Object.keys(felder).forEach(function (k) {
    var art = felder[k];
    if (art === 'text') neu[k] = text_(roh[k]);
    else if (art.indexOf('wort:') === 0) neu[k] = wort_(roh[k], art.slice(5));
    else if (art === 'datum') neu[k] = datum_(roh[k]);
    else if (art === 'cent') neu[k] = cent_(roh[k]);
    else if (art === 'bool') neu[k] = roh[k] === true;
  });
  if (tabelle === 'aktivitaeten' && !neu.datum) neu.datum = neu.geaendertAm.slice(0, 10); // ein Kontakt hat immer ein Datum
  if (alt && zeit_(neu.geaendertAm) <= zeit_(alt.geaendertAm)) {
    if (!gleicherInhalt_(alt, neu)) zurueck[neu.id] = true;
    return;
  }
  neu.rev = ++d.rev;
  d[tabelle][neu.id] = neu;
}

/** Löscht einen Fall samt Verlauf und Anlagen. Zurück bleibt nur ein Vermerk ohne Inhalt. */
function loescheFall_(d, id, zeitpunkt) {
  d.faelle[id] = { id: id, geloescht: true, geaendertAm: zeitpunkt, rev: ++d.rev };
  Object.keys(d.verlauf).forEach(function (k) {
    var v = d.verlauf[k];
    if (v.fallId === id && !v.geloescht) d.verlauf[k] = { id: k, fallId: id, geloescht: true, rev: ++d.rev };
  });
  Object.keys(d.anlagen).forEach(function (k) {
    if (d.anlagen[k].fallId === id && !d.anlagen[k].geloescht) loescheAnlage_(d, k);
  });
}

function uebernehmeVerlauf_(d, roh) {
  var neu = bereinigeVerlauf_(roh);
  var alt = d.verlauf[neu.id];
  if (alt) {
    if (neu.geloescht && !alt.geloescht) d.verlauf[neu.id] = { id: neu.id, fallId: alt.fallId, geloescht: true, rev: ++d.rev };
    return;
  }
  var fall = d.faelle[neu.fallId];
  if (neu.geloescht || (fall && fall.geloescht)) neu = { id: neu.id, fallId: neu.fallId, geloescht: true };
  neu.rev = ++d.rev;
  d.verlauf[neu.id] = neu;
}

function uebernehmeAnlage_(d, roh) {
  var neu = bereinigeAnlage_(roh);
  var alt = d.anlagen[neu.id];
  if (alt) {
    if (neu.geloescht && !alt.geloescht) loescheAnlage_(d, neu.id);
    return;
  }
  var fall = d.faelle[neu.fallId];
  if (neu.geloescht || (fall && fall.geloescht)) neu = { id: neu.id, fallId: neu.fallId, geloescht: true };
  else neu.vorhanden = false; // die Datei selbst kommt mit anlageHochladen
  neu.rev = ++d.rev;
  d.anlagen[neu.id] = neu;
}

function loescheAnlage_(d, id) {
  var alt = d.anlagen[id];
  if (alt.dateiId) {
    try { DriveApp.getFileById(alt.dateiId).setTrashed(true); } catch (fehler) { /* Datei schon weg */ }
  }
  d.anlagen[id] = { id: id, fallId: alt.fallId, geloescht: true, rev: ++d.rev };
}

/**
 * Zwei Geräte können ohne Netz dieselbe Fallnummer vergeben. Wer zuerst abgleicht, behält sie;
 * der spätere Fall bekommt die nächste freie Nummer seines Jahres.
 */
function eindeutigeFallnummer_(d, fall) {
  var belegt = {};
  Object.keys(d.faelle).forEach(function (k) {
    var f = d.faelle[k];
    if (k !== fall.id && !f.geloescht && f.fallnummer) belegt[f.fallnummer] = true;
  });
  if (fall.fallnummer && !belegt[fall.fallnummer]) return fall.fallnummer;
  var treffer = /^(\d{4})-\d+$/.exec(fall.fallnummer || '');
  var jahr = treffer ? treffer[1] : String(new Date(zeit_(fall.erstelltAm) || Date.now()).getFullYear());
  var hoechste = 0;
  Object.keys(belegt).forEach(function (nummer) {
    var m = /^(\d{4})-(\d+)$/.exec(nummer);
    if (m && m[1] === jahr) hoechste = Math.max(hoechste, Number(m[2]));
  });
  var laufend = String(hoechste + 1);
  while (laufend.length < 3) laufend = '0' + laufend;
  return jahr + '-' + laufend;
}

// ============================== Anlagen ==============================

/** Speichert den Inhalt einer Anlage, deren Eintrag bereits per sync bekannt ist. */
function anlageHochladen(id, base64) {
  id = kennung_(id);
  var bytes = Utilities.base64Decode(String(base64 || ''));
  if (!bytes.length) throw new Error('Die Anlage ist leer.');
  if (bytes.length > MAX_ANLAGE_BYTES) throw new Error('Die Anlage ist größer als 25 MB.');

  var eintrag = ladeDaten_().anlagen[id];
  if (!eintrag || eintrag.geloescht) throw new Error('Anlage unbekannt');
  if (eintrag.vorhanden) return { vorhanden: true };
  // Die Datei wird vor der Sperre angelegt, damit andere Geräte währenddessen abgleichen können.
  var datei = unterordner_(ANLAGEN_ORDNER).createFile(
    Utilities.newBlob(bytes, eintrag.mimeType || 'application/octet-stream', id));

  var sperre = LockService.getScriptLock();
  sperre.waitLock(25000);
  try {
    var d = ladeDaten_();
    var a = d.anlagen[id];
    if (!a || a.geloescht || a.vorhanden) {
      datei.setTrashed(true); // inzwischen gelöscht oder von einem anderen Gerät hochgeladen
      return { vorhanden: !!(a && a.vorhanden) };
    }
    a.dateiId = datei.getId();
    a.vorhanden = true;
    a.groesseBytes = bytes.length;
    a.rev = ++d.rev;
    speichereDaten_(d);
    return { vorhanden: true, rev: d.rev };
  } finally {
    sperre.releaseLock();
  }
}

function anlageLaden(id) {
  var a = ladeDaten_().anlagen[kennung_(id)];
  if (!a || a.geloescht) throw new Error('Anlage unbekannt');
  if (!a.vorhanden || !a.dateiId) throw new Error('Die Anlage wurde noch nicht hochgeladen.');
  var blob = DriveApp.getFileById(a.dateiId).getBlob();
  return { base64: Utilities.base64Encode(blob.getBytes()), mimeType: a.mimeType, anzeigename: a.anzeigename };
}

function ohneInterna_(anlage) {
  var kopie = {};
  Object.keys(anlage).forEach(function (k) { if (k !== 'dateiId') kopie[k] = anlage[k]; });
  return kopie;
}

// ============================== Eingaben prüfen ==============================
// Es wird nur übernommen, was die Apps kennen – unbekannte Felder und übergroße Texte fallen weg.

var FALL_TEXTE = ['fallnummer', 'kundeName', 'kundeFirma', 'kundeTelefon', 'kundeEmail', 'kundeAnschrift',
  'kundenAktenzeichen', 'auftraggeber', 'auftraggeberUuid', 'auftraggeberKontakt', 'auftraggeberTelefon', 'auftraggeberEmail', 'auftraggeberZeichen', 'auftragsReferenz', 'tour', 'fahrzeug', 'fahrer', 'beifahrer1', 'beifahrer2', 'schadensort',
  'beschreibung', 'wiedervorlageNotiz', 'versicherungsNummer'];
var FALL_DATEN = ['schadensdatum', 'meldedatum', 'wiedervorlageAm', 'zahlungsfrist'];

function bereinigeFall_(roh) {
  roh = roh || {};
  var fall = { id: kennung_(roh.id), geloescht: roh.geloescht === true, geaendertAm: zeitpunkt_(roh.geaendertAm) };
  if (fall.geloescht) return fall;
  FALL_TEXTE.forEach(function (k) { fall[k] = text_(roh[k]); });
  FALL_DATEN.forEach(function (k) { fall[k] = datum_(roh[k]); });
  fall.schadensart = wort_(roh.schadensart, 'WARE');
  fall.status = wort_(roh.status, 'NEU');
  fall.wartetAuf = roh.wartetAuf == null ? null : wort_(roh.wartetAuf, null);
  fall.forderungCent = cent_(roh.forderungCent);
  fall.vergleichCent = cent_(roh.vergleichCent);
  fall.erstelltAm = zeitpunkt_(roh.erstelltAm);
  return fall;
}

function bereinigeVerlauf_(roh) {
  roh = roh || {};
  return {
    id: kennung_(roh.id),
    fallId: kennung_(roh.fallId),
    geloescht: roh.geloescht === true,
    zeitpunkt: zeitpunkt_(roh.zeitpunkt),
    typ: wort_(roh.typ, 'NOTIZ'),
    text: text_(roh.text)
  };
}

function bereinigeAnlage_(roh) {
  roh = roh || {};
  return {
    id: kennung_(roh.id),
    fallId: kennung_(roh.fallId),
    geloescht: roh.geloescht === true,
    anzeigename: text_(roh.anzeigename).slice(0, 200),
    mimeType: /^[\w.+-]+\/[\w.+-]+$/.test(String(roh.mimeType)) ? String(roh.mimeType) : 'application/octet-stream',
    groesseBytes: Math.max(0, Math.floor(Number(roh.groesseBytes) || 0)),
    hinzugefuegtAm: zeitpunkt_(roh.hinzugefuegtAm)
  };
}

function kennung_(wert) {
  wert = String(wert == null ? '' : wert).toLowerCase();
  if (!/^[0-9a-f-]{8,64}$/.test(wert)) throw new Error('Ungültige Kennung');
  return wert;
}
function text_(wert) { return String(wert == null ? '' : wert).slice(0, MAX_TEXT); }
function datum_(wert) { return /^\d{4}-\d{2}-\d{2}$/.test(String(wert)) ? String(wert) : null; }
function wort_(wert, standard) { return /^[A-Z_]{1,40}$/.test(String(wert)) ? String(wert) : standard; }
function cent_(wert) {
  if (wert == null || wert === '') return null;
  var zahl = Math.floor(Number(wert));
  return isFinite(zahl) && zahl >= 0 ? zahl : null;
}
/** Zeitpunkte einheitlich als ISO-Text in UTC; Unlesbares wird zu „jetzt“. */
function zeitpunkt_(wert) {
  var ms = Date.parse(String(wert));
  return new Date(isNaN(ms) ? Date.now() : ms).toISOString();
}
function zeit_(iso) { var ms = Date.parse(String(iso)); return isNaN(ms) ? 0 : ms; }
function liste_(wert) { return Array.isArray(wert) ? wert : []; }

function gleicherInhalt_(a, b) {
  return Object.keys(b).every(function (k) { return k === 'erstelltAm' || a[k] === b[k]; });
}

function auswahl_(tabelle, seit, zusaetzlich) {
  return Object.keys(tabelle)
    .filter(function (k) { return tabelle[k].rev > seit || zusaetzlich[k]; })
    .map(function (k) { return tabelle[k]; });
}

// ============================== Ablage in Google Drive ==============================

function ordner_() {
  var props = PropertiesService.getScriptProperties();
  var id = props.getProperty('ORDNER_ID');
  if (id) {
    try {
      var vorhanden = DriveApp.getFolderById(id);
      if (!vorhanden.isTrashed()) return vorhanden;
    } catch (fehler) { /* Ordner gelöscht – neu anlegen */ }
  }
  var treffer = DriveApp.getFoldersByName(ORDNER_NAME);
  var ordner = treffer.hasNext() ? treffer.next() : DriveApp.createFolder(ORDNER_NAME);
  props.setProperty('ORDNER_ID', ordner.getId());
  return ordner;
}

function unterordner_(name) {
  var treffer = ordner_().getFoldersByName(name);
  return treffer.hasNext() ? treffer.next() : ordner_().createFolder(name);
}

/**
 * daten.json – über die gemerkte Datei-ID (schnell); nur wenn sie fehlt, per Suche im Ordner
 * (die Suche in Google Drive dauert spürbar länger).
 */
function datenDatei_() {
  var props = PropertiesService.getScriptProperties();
  var ordner = ordner_();
  // gemerkt als „Ordner-ID|Datei-ID“: Wurde der Ordner gelöscht und neu angelegt, gilt die Datei nicht mehr.
  var gemerkt = String(props.getProperty('DATEN_ID') || '').split('|');
  if (gemerkt[0] === ordner.getId() && gemerkt[1]) {
    try {
      var datei = DriveApp.getFileById(gemerkt[1]);
      if (!datei.isTrashed()) return datei;
    } catch (fehler) { /* Datei gelöscht – neu suchen */ }
  }
  props.deleteProperty('DATEN_ID');
  var treffer = ordner.getFilesByName(DATEN_DATEI);
  if (!treffer.hasNext()) return null;
  var gefunden = treffer.next();
  props.setProperty('DATEN_ID', ordner.getId() + '|' + gefunden.getId());
  return gefunden;
}

/** Legt daten.json an und merkt sich die ID. */
function neueDatenDatei_(text) {
  var ordner = ordner_();
  var datei = ordner.createFile(DATEN_DATEI, text, 'application/json');
  PropertiesService.getScriptProperties().setProperty('DATEN_ID', ordner.getId() + '|' + datei.getId());
  return datei;
}

function ladeDaten_() {
  var datei = datenDatei_();
  if (datei) {
    var d = JSON.parse(datei.getBlob().getDataAsString('UTF-8'));
    d.faelle = d.faelle || {};
    d.verlauf = d.verlauf || {};
    d.anlagen = d.anlagen || {};
    d.auftraggeber = d.auftraggeber || {};
    CRM_NAMEN.forEach(function (t) { d[t] = d[t] || {}; });
    return d;
  }
  // Neuer Bestand: sofort anlegen, damit die Kennung bei jedem weiteren Aufruf gleich bleibt.
  // Ändert sie sich, erkennen die Geräte daran, dass sie alles neu hochladen müssen.
  var sperre = LockService.getScriptLock();
  sperre.waitLock(25000);
  try {
    if (datenDatei_()) return ladeDaten_(); // inzwischen von einem anderen Aufruf angelegt
    var neu = { kennung: Utilities.getUuid(), rev: 0, faelle: {}, verlauf: {}, anlagen: {}, auftraggeber: {} };
    CRM_NAMEN.forEach(function (t) { neu[t] = {}; });
    neueDatenDatei_(JSON.stringify(neu));
    return neu;
  } finally {
    sperre.releaseLock();
  }
}

function speichereDaten_(d) {
  var text = JSON.stringify(d);
  var datei = datenDatei_();
  if (!datei) {
    neueDatenDatei_(text);
    return;
  }
  sichereTaeglich_(datei);
  datei.setContent(text);
}

/** Hebt vor der ersten Änderung des Tages den bisherigen Stand auf. */
function sichereTaeglich_(datei) {
  var props = PropertiesService.getScriptProperties();
  var heute = new Date().toISOString().slice(0, 10);
  if (props.getProperty('LETZTE_SICHERUNG') === heute) return;
  var bisher = datei.getBlob().getDataAsString('UTF-8');
  if (JSON.parse(bisher).rev === 0) return; // ein leerer Bestand braucht keine Sicherung
  var ziel = unterordner_(SICHERUNG_ORDNER);
  ziel.createFile('daten-' + heute + '.json', bisher, 'application/json');
  props.setProperty('LETZTE_SICHERUNG', heute);

  var namen = [];
  var dateien = ziel.getFiles();
  var nachName = {};
  while (dateien.hasNext()) {
    var f = dateien.next();
    namen.push(f.getName());
    nachName[f.getName()] = f;
  }
  namen.sort().slice(0, Math.max(0, namen.length - SICHERUNGEN_BEHALTEN)).forEach(function (name) {
    nachName[name].setTrashed(true);
  });
}
