'use strict';
// Erzeugt die App-Symbole aus web/logo.svg: icon-192/512.png randlos (iPhone, Android-Web, maskierbar) und icon-rund.png mit runden Ecken (Browser-Tab, Windows).
// Aufruf: node tools/icons.js (braucht Playwright mit Chromium).
const fs = require('fs'), path = require('path'), { chromium } = require('playwright');
const web = path.join(__dirname, '..', 'web'), svg = fs.readFileSync(path.join(web, 'logo.svg'), 'utf8');
// Windows maskiert nicht selbst: abgerundetes Quadrat mit etwas Rand, außen durchsichtig
const rund = svg.replace('<defs>', '<defs><clipPath id="form"><rect x="48" y="48" width="928" height="928" rx="208"/></clipPath>').replace(/(<rect width="1024"[^>]*\/>)([\s\S]*)<\/svg>/, '<g clip-path="url(#form)">$1$2</g></svg>');
(async () => {
  const b = await chromium.launch(), s = await b.newPage();
  for (const [datei, quelle, px] of [['icon-512.png', svg, 512], ['icon-192.png', svg, 192], ['icon-rund.png', rund, 512]]) {
    await s.setViewportSize({ width: px, height: px });
    await s.setContent(`<style>html,body{margin:0;background:transparent}svg{display:block;width:${px}px;height:${px}px}</style>${quelle}`);
    await s.screenshot({ path: path.join(web, datei), omitBackground: true });
    console.log(datei, px);
  }
  await b.close();
})();
