'use strict';
/**
 * Android-Brücke: stellt der Web-App dieselbe Schnittstelle bereit wie die Windows-App (window.desktop),
 * nur dass die Arbeit der Kotlin-Teil der App erledigt (AndroidBruecke). Wird vor allen anderen Skripten geladen.
 */
(() => {
  const B = window.AndroidBruecke;
  if (!B) return;
  let zaehler = 0;
  const offen = new Map();
  window.__androidAntwort = (id, antwort) => {
    const p = offen.get(id); if (!p) return;
    offen.delete(id);
    if (antwort && antwort.fehler) { const e = new Error(antwort.fehler); e.anmeldung = !!antwort.anmeldung; p.nein(e); } else p.ja(antwort ? antwort.ok : undefined);
  };
  const aufruf = (name, ...args) => new Promise((ja, nein) => {
    const id = 'a' + (++zaehler);
    offen.set(id, { ja, nein });
    try { B.aufruf(id, name, JSON.stringify(args)); } catch (e) { offen.delete(id); nein(e); }
  });
  const alsBase64 = bytes => {
    const b = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    let s = '';
    for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode.apply(null, b.subarray(i, i + 0x8000));
    return btoa(s);
  };
  const ziele = [];
  window.__androidZiel = ziel => ziele.forEach(f => { try { f(ziel); } catch {} });

  window.desktop = {
    plattform: 'android',
    mail: {
      anbieter: () => aufruf('mail:anbieter'),
      konto: art => aufruf('mail:konto', art),
      einrichten: (konfig, passwort, art) => aufruf('mail:einrichten', konfig, passwort, art),
      trennen: art => aufruf('mail:trennen', art),
      sende: (entwurf, art) => aufruf('mail:sende', entwurf, art),
      hole: (stand, art) => aufruf('mail:hole', stand, art),
    },
    benachrichtige: (titel, text, ziel) => aufruf('benachrichtige', titel, text, ziel),
    oeffneDatei: (name, bytes) => aufruf('datei:oeffnen', name, alsBase64(bytes)),
    oeffneExtern: adresse => aufruf('extern:oeffnen', adresse),
    pdf: html => aufruf('pdf:erzeugen', html),
    speichere: (name, base64) => aufruf('datei:speichern', name, base64),
    drucke: html => aufruf('drucken', html),
    beiZiel: rueckruf => { ziele.push(rueckruf); aufruf('ziel:bereit').catch(() => {}); },
  };

  // Downloads (<a download> mit blob:-Adresse, z. B. CSV, Kalender, Sicherung) landen im Download-Ordner.
  const klick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (this.download && /^(blob|data):/.test(this.href)) {
      const name = this.download;
      fetch(this.href).then(r => r.arrayBuffer()).then(b => aufruf('datei:speichern', name, alsBase64(b)))
        .then(ort => { if (typeof toast === 'function') toast('Gespeichert: ' + ort, 5000); })
        .catch(e => { if (typeof toast === 'function') toast('Speichern nicht möglich: ' + (e.message || e), 6000); });
      return;
    }
    return klick.call(this);
  };

  // Zurück-Taste: erst Dialog schließen (außer PIN-Abfrage), dann in der App zurück, sonst App verlassen.
  window.__androidZurueck = () => {
    const d = document.getElementById('dialog');
    if (d && d.open) {
      const ev = new Event('cancel', { cancelable: true });
      d.dispatchEvent(ev);
      if (!ev.defaultPrevented) d.close();
      return true;
    }
    if (location.hash && location.hash !== '#/' && location.hash !== '#') { history.back(); return true; }
    return false;
  };
})();
