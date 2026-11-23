'use strict';
/**
 * Scannen (Windows-Scanner-Schnittstelle WIA, auch mit Einzug für Stapel) und Texterkennung (offline, tesseract.js).
 * Gescannt wird über PowerShell und das WIA-COM-Objekt – dafür braucht es keinen Treiber außer dem des Scanners.
 * Tests: SCHADEN_SCAN_ORDNER statt eines Scanners (die Bilder darin gelten als gescannte Seiten).
 */
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFile } = require('node:child_process');

const JPEG = '{B96B3CAE-0728-11D3-9D7B-0000F81EF32E}';
const GERAETE_PS = `$ErrorActionPreference = 'Stop'
$dm = New-Object -ComObject WIA.DeviceManager
$l = @($dm.DeviceInfos | Where-Object { $_.Type -eq 1 } | ForEach-Object { [pscustomobject]@{ id = $_.DeviceID; name = $_.Properties.Item('Name').Value } })
ConvertTo-Json -InputObject $l -Compress`;
// Einzug (3088 = 1), Graustufen (6146 = 2), 300 dpi (6147/6148). STAPEL: bis „Papier leer“ (0x80210003) eine Seite nach der anderen.
// EINZELN: genau ein Beleg mit größter Seitenlänge (6152 = Höhe in Pixeln, 3096 = Seiten) – lange Kassenbons, per Hand geführt.
const STAPEL_PS = `$ErrorActionPreference = 'Stop'
$dm = New-Object -ComObject WIA.DeviceManager
$info = $dm.DeviceInfos | Where-Object { $_.Type -eq 1 -and ($env:SCAN_ID -eq '' -or $_.DeviceID -eq $env:SCAN_ID) } | Select-Object -First 1
if (-not $info) { throw 'Kein Scanner gefunden' }
# „Gerät ausgelastet“ (0x80210006): kurz warten und erneut versuchen – z. B. wenn ein HP-Programm den Scanner gerade freigibt
# Windows-Code eines Fehlers – PowerShell verpackt COM-Fehler, der Code steckt oft in einer inneren Ausnahme
function Code($fehler) { $x = $fehler.Exception; while ($x) { if (@(-2145320957, -2145320954) -contains $x.HResult) { return $x.HResult }; $x = $x.InnerException }; return 0 }
function Versuche([scriptblock]$was) { for ($i = 0; ; $i++) { try { return & $was } catch { if ((Code $_) -eq -2145320954 -and $i -lt 8) { Start-Sleep -Seconds 2 } else { throw } } } }
$dev = Versuche { $info.Connect() }
function Setze($props, $id, $wert) { foreach ($p in $props) { if ($p.PropertyID -eq $id) { try { $p.Value = $wert } catch {} } } }
function SetzeMax($props, $id) { foreach ($p in $props) { if ($p.PropertyID -eq $id) { try { $p.Value = $p.SubTypeMax } catch {} } } }
$stapel = $env:SCAN_MODUS -ne 'EINZELN'
Setze $dev.Properties 3088 1
if (-not $stapel) { Setze $dev.Properties 3096 1 }
$item = $dev.Items.Item(1)
Setze $item.Properties 3088 1
Setze $item.Properties 6146 2; Setze $item.Properties 6147 300; Setze $item.Properties 6148 300
if (-not $stapel) { SetzeMax $item.Properties 6152 }
$n = 0
while ($true) {
  try { $img = Versuche { $item.Transfer('${JPEG}') } }
  catch { if ((Code $_) -eq -2145320957) { if ($n -eq 0) { throw 'Kein Papier im Einzug' } else { break } } else { throw } }
  $n++; $img.SaveFile((Join-Path $env:SCAN_ZIEL ('seite{0:D3}.jpg' -f $n)))
  if (-not $stapel) { break }
}
Write-Output $n`;

// Windows-Fehlercodes des Scanners → was zu tun ist
const WIA_FEHLER = [[/80210006|ausgelastet|busy/i, 'Der Scanner ist gerade belegt – bitte HP-Programme schließen (HP Scan, HP Smart, „Scan to Computer“, auch unten rechts in der Taskleiste) und erneut scannen. Hilft das nicht: Scanner aus- und wieder einschalten.'],
  [/80210005|8021000A|offline|nicht verbunden/i, 'Der Scanner antwortet nicht – ist er eingeschaltet und per USB bzw. im Netzwerk verbunden?'],
  [/80210003|Kein Papier/i, 'Kein Papier im Einzug – Belege einlegen und erneut scannen.'],
  [/80210002|Papierstau|paper jam/i, 'Papierstau im Scanner – Beleg entnehmen und erneut scannen.'],
  [/80210064|abgebrochen|cancel/i, 'Scan abgebrochen.']];
let laufend = null; // ein noch laufender Scan-Versuch belegt den Scanner – vor dem nächsten beenden
function powershell(skript, env = {}) {
  if (process.platform !== 'win32') return Promise.reject(new Error('Scannen geht nur in der Windows-App'));
  if (laufend) { try { laufend.kill(); } catch {} laufend = null; }
  const datei = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'ayse-scan-')), 'skript.ps1');
  fs.writeFileSync(datei, '﻿' + skript); // mit BOM, damit PowerShell 5 Umlaute richtig liest
  return new Promise((ja, nein) => {
    const p = execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-STA', '-File', datei],
      { env: { ...process.env, ...env }, timeout: 5 * 60000, windowsHide: true, maxBuffer: 1 << 20 },
      (fehler, aus, err) => {
        if (laufend === p) laufend = null;
        if (!fehler) { ja(String(aus).trim()); return; }
        const roh = String(err || fehler.message), bekannt = WIA_FEHLER.find(([r]) => r.test(roh));
        nein(new Error(bekannt ? bekannt[1] : roh.split('\n').find(z => z.trim()) || 'Scanner-Fehler'));
      });
    laufend = p;
  });
}

/** Gefundene Scanner: [{ id, name }]. */
async function geraete() {
  if (process.env.SCHADEN_SCAN_ORDNER) return [{ id: 'test', name: 'Test-Scanner' }];
  const roh = await powershell(GERAETE_PS);
  return roh ? JSON.parse(roh) : [];
}

/** Scannt einen Stapel (STAPEL) oder genau einen langen Beleg (EINZELN) und liefert die Seiten als JPEG in Base64. */
async function stapel(id, modus) {
  let ordner = process.env.SCHADEN_SCAN_ORDNER;
  if (!ordner) {
    ordner = fs.mkdtempSync(path.join(os.tmpdir(), 'ayse-seiten-'));
    await powershell(STAPEL_PS, { SCAN_ID: String(id || ''), SCAN_MODUS: modus === 'EINZELN' ? 'EINZELN' : 'STAPEL', SCAN_ZIEL: ordner });
  }
  return fs.readdirSync(ordner).filter(n => /\.(jpe?g|png)$/i.test(n)).sort()
    .map(n => ({ name: n, mimeType: /png$/i.test(n) ? 'image/png' : 'image/jpeg', base64: fs.readFileSync(path.join(ordner, n)).toString('base64') }));
}

// Texterkennung: ein Arbeiter für die ganze Sitzung (das Laden der deutschen Sprachdaten dauert ein paar Sekunden).
let arbeiter = null;
async function lesen(base64) {
  if (!arbeiter) {
    const { createWorker } = require('tesseract.js');
    const daten = path.dirname(require.resolve('@tesseract.js-data/deu/4.0.0/deu.traineddata.gz')).replace('app.asar', 'app.asar.unpacked');
    arbeiter = createWorker('deu', 1, { langPath: daten, gzip: true, cacheMethod: 'none' });
  }
  const r = await (await arbeiter).recognize(Buffer.from(String(base64), 'base64'));
  return { text: r.data.text, sicherheit: Math.round(r.data.confidence) };
}
async function beenden() { if (arbeiter) { const w = await arbeiter; arbeiter = null; await w.terminate(); } }

module.exports = { geraete, stapel, lesen, beenden };
