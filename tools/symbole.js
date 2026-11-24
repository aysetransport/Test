'use strict';
// Baut die Symbol-Sammlung (#symbole in web/index.html) aus den Listen SYMBOL_BEREICH und SYMBOL_EMOJI neu.
// Quelle: Lucide (ISC-Lizenz), Paket „lucide-static“. Aufruf: node tools/symbole.js <Pfad zu lucide-static/icons>
const fs = require('fs'), path = require('path');
const ordner = process.argv[2]; if (!ordner) { console.error('Pfad zu lucide-static/icons fehlt'); process.exit(1); }
const datei = path.join(__dirname, '..', 'web', 'index.html'); let html = fs.readFileSync(datei, 'utf8');
const listen = html.slice(html.indexOf('// SYMBOLE-ANFANG'), html.indexOf('// SYMBOLE-ENDE'));
const namen = [...new Set([...listen.matchAll(/:\s*'([a-z0-9-]+)'/g)].map(m => m[1]))].sort();
const symbole = namen.map(n => {
  const svg = fs.readFileSync(path.join(ordner, n + '.svg'), 'utf8');
  const innen = svg.slice(svg.indexOf('>', svg.indexOf('<svg')) + 1, svg.lastIndexOf('</svg>')).replace(/\s+/g, ' ').replace(/> </g, '><').trim();
  return `<symbol id="i-${n}" viewBox="0 0 24 24">${innen}</symbol>`;
}).join('');
html = html.replace(/(<svg id="symbole"[^>]*>)[\s\S]*?(<\/svg>)/, `$1<!-- Lucide, ISC-Lizenz, https://lucide.dev -->${symbole}$2`);
fs.writeFileSync(datei, html);
console.log(namen.length, 'Symbole');
