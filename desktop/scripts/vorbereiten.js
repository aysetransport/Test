'use strict';
// Übernimmt die Web-App (../web) als Oberfläche der Windows-App – so gibt es nur eine Fassung.
const fs = require('node:fs');
const path = require('node:path');
const quelle = path.join(__dirname, '..', '..', 'web');
const ziel = path.join(__dirname, '..', 'app');
fs.rmSync(ziel, { recursive: true, force: true });
fs.mkdirSync(ziel, { recursive: true });
for (const datei of ['index.html', 'manifest.webmanifest', 'icon-192.png', 'icon-512.png', 'pdfjs/pdf.min.js', 'pdfjs/pdf.worker.min.js']) {
  fs.mkdirSync(path.dirname(path.join(ziel, datei)), { recursive: true });
  fs.copyFileSync(path.join(quelle, datei), path.join(ziel, datei));
}
console.log('Web-App übernommen nach', ziel);
