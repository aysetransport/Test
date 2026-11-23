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

// Gemeinsamer Teil der Skripte: Gerät wählen, Einzug finden, Fehlercodes lesen, Bild auf mehreren Wegen holen.
const GRUNDLAGE = `$ErrorActionPreference = 'Stop'; [Console]::OutputEncoding = [Text.Encoding]::UTF8
$JPEG = '{B96B3CAE-0728-11D3-9D7B-0000F81EF32E}'; $BMP = '{B96B3CAB-0728-11D3-9D7B-0000F81EF32E}'
# WIA-Fehlercode (0x8021xxxx) – PowerShell verpackt COM-Fehler, der Code steckt oft in einer inneren Ausnahme
function Code($fehler) { $x = $fehler.Exception; while ($x) { if (((($x.HResult -shr 16) -band 0xFFFF) -eq 0x8021)) { return $x.HResult }; $x = $x.InnerException }; return 0 }
function Text($fehler) { $c = Code $fehler; $m = $fehler.Exception.Message -replace '\\s+', ' '; if ($c) { '{0} (0x{1:X8})' -f $m, $c } else { $m } }
# „Gerät ausgelastet“ (0x80210006): kurz warten und erneut versuchen
function Versuche([scriptblock]$was) { for ($i = 0; ; $i++) { try { return & $was } catch { if ((Code $_) -eq -2145320954 -and $i -lt 5) { Start-Sleep -Seconds 2 } else { throw } } } }
function Geraet() {
  $dm = New-Object -ComObject WIA.DeviceManager
  foreach ($d in $dm.DeviceInfos) { if ($d.Type -eq 1 -and ($env:SCAN_ID -eq '' -or $d.DeviceID -eq $env:SCAN_ID)) { return $d } }
  throw 'Kein Scanner gefunden'
}
function Setze($props, $id, $wert) { foreach ($p in $props) { if ($p.PropertyID -eq $id) { try { $p.Value = $wert } catch {} } } }
function SetzeMax($props, $id) { foreach ($p in $props) { if ($p.PropertyID -eq $id) { try { $p.Value = $p.SubTypeMax } catch {} } } }
# Neuere Treiber (WIA 2) führen den Einzug als eigenes Element (Kategorie FE131934…) – sonst das erste Element
function Einzug($dev) { foreach ($it in $dev.Items) { foreach ($p in $it.Properties) { if ($p.PropertyID -eq 4125 -and ([string]$p.Value) -match 'FE131934') { return $it } } }; return $dev.Items.Item(1) }
# Bild holen: JPEG direkt, sonst BMP, sonst über den Windows-Scan-Dialog (manche HP-Treiber können nur das). Der erste Weg, der klappt, bleibt.
# Immer als JPEG ablegen: BMP ist unkomprimiert (oft 25 MB+) – zu groß für Texterkennung und Ablage
function Speichere($img, $ziel) {
  if ($img.FileExtension -match '^jpe?g$') { $img.SaveFile("$ziel.jpg"); return }
  $roh = "$ziel.roh.$($img.FileExtension)"; $img.SaveFile($roh); Add-Type -AssemblyName System.Drawing
  $b = [System.Drawing.Image]::FromFile($roh); $b.Save("$ziel.jpg", [System.Drawing.Imaging.ImageFormat]::Jpeg); $b.Dispose(); Remove-Item $roh
}
$script:wege = @('JPEG', 'BMP', 'DIALOG')
function Hole($item) {
  $fehler = @()
  foreach ($weg in $script:wege) {
    try {
      if ($weg -eq 'DIALOG') { $img = (New-Object -ComObject WIA.CommonDialog).ShowTransfer($item, $BMP, $false) }
      elseif ($weg -eq 'BMP') { $img = Versuche { $item.Transfer($BMP) } }
      else { $img = Versuche { $item.Transfer($JPEG) } }
      $script:wege = @($weg); return $img
    } catch { if ((Code $_) -eq -2145320957) { throw }; $fehler += ('{0}: {1}' -f $weg, (Text $_)) }
  }
  throw ($fehler -join ' | ')
}`;
const GERAETE_PS = `$ErrorActionPreference = 'Stop'; [Console]::OutputEncoding = [Text.Encoding]::UTF8
$dm = New-Object -ComObject WIA.DeviceManager
$l = @($dm.DeviceInfos | Where-Object { $_.Type -eq 1 } | ForEach-Object { [pscustomobject]@{ id = $_.DeviceID; name = $_.Properties.Item('Name').Value } })
ConvertTo-Json -InputObject $l -Compress`;
// Einzug (3088 = 1), Graustufen (6146 = 2) – Auflösung wie vom Treiber vorgegeben. STAPEL: bis „Papier leer“ (0x80210003) eine Seite nach der anderen.
// EINZELN: genau ein Beleg mit größter Seitenlänge (6152 = Höhe in Pixeln, 3096 = Seiten) – lange Kassenbons, per Hand geführt.
const STAPEL_PS = GRUNDLAGE + `
$dev = Versuche { (Geraet).Connect() }
$stapel = $env:SCAN_MODUS -ne 'EINZELN'
Setze $dev.Properties 3088 1
if (-not $stapel) { Setze $dev.Properties 3096 1 }
$item = Einzug $dev
Setze $item.Properties 3088 1
# Auflösung bleibt beim Treiber (HP: 200 dpi) – Breite/Länge sind in Pixeln dieser Auflösung; mit 300 dpi wird die Seite abgeschnitten und riesig
Setze $item.Properties 6146 2
if (-not $stapel) { SetzeMax $item.Properties 6152 }
$n = 0
while ($true) {
  try { $img = Hole $item }
  catch { if ((Code $_) -eq -2145320957) { if ($n -eq 0) { throw 'Kein Papier im Einzug' } else { break } } else { throw } }
  $n++; Speichere $img (Join-Path $env:SCAN_ZIEL ('seite{0:D3}' -f $n))
  if (-not $stapel) { break }
}
Write-Output $n`;
// Prüfen: was Windows und der Treiber melden (zum Weitergeben an den Support); mit Testscan auch der Übertragungsweg.
const PRUEFEN_PS = GRUNDLAGE + `
$aus = @()
try { $aus += 'WIA-Dienst: ' + (Get-Service stisvc).Status } catch { $aus += 'WIA-Dienst: nicht gefunden' }
try { foreach ($d in (New-Object -ComObject WIA.DeviceManager).DeviceInfos) { $aus += ('Gerät: {0} | Typ {1} | {2}' -f $d.Properties.Item('Name').Value, $d.Type, $d.DeviceID) } } catch { $aus += 'Geräteliste: ' + (Text $_) }
try {
  $dev = Versuche { (Geraet).Connect() }; $aus += 'Verbinden: ok'
  foreach ($p in $dev.Properties) { if (@(3086, 3087, 3088, 3096) -contains $p.PropertyID) { $aus += ('  Gerät {0} ({1}) = {2}' -f $p.Name, $p.PropertyID, $p.Value) } }
  $k = 0; foreach ($it in $dev.Items) { $k++; $aus += ('Element {0}:' -f $k); foreach ($p in $it.Properties) { if (@(4098, 4125, 3088, 6146, 6147, 6151, 6152) -contains $p.PropertyID) { $max = ''; try { $max = ' (max ' + $p.SubTypeMax + ')' } catch {}; $aus += ('  {0} ({1}) = {2}{3}' -f $p.Name, $p.PropertyID, $p.Value, $max) } } }
  if ($env:SCAN_TEST -eq '1') {
    $item = Einzug $dev; Setze $item.Properties 3088 1; Setze $item.Properties 6146 2
    try { $img = Hole $item; $aus += ('Testscan: ok über {0}, {1} x {2} Pixel, {3} dpi, Format {4}' -f $script:wege[0], $img.Width, $img.Height, $img.HorizontalResolution, $img.FileExtension); Speichere $img (Join-Path $env:SCAN_ZIEL 'test') } catch { $aus += 'Testscan: ' + (Text $_) }
  }
} catch { $aus += 'Verbinden: ' + (Text $_) }
Write-Output ($aus -join [Environment]::NewLine)`;

// Windows-Fehlercodes des Scanners → was zu tun ist
const WIA_FEHLER = [[/80210006|ausgelastet|busy/i, 'Der Scanner meldet „ausgelastet“. Bitte oben den richtigen Scanner wählen (oft gibt es einen USB- und einen alten Netzwerk-Eintrag) und „🩺 Scanner prüfen“ ausführen.'],
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
        // erste aussagekräftige Zeile (PowerShell hängt Ort und Kategorie an) – mit dem Rohtext, damit der Grund sichtbar bleibt
        const roh = String(err || fehler.message), zeile = roh.split('\n').map(z => z.trim()).find(z => z && !/^(In |At |\+|Zeichen|CategoryInfo|FullyQualifiedErrorId)/.test(z)) || 'Scanner-Fehler';
        const bekannt = WIA_FEHLER.find(([r]) => r.test(roh));
        nein(new Error(bekannt ? `${bekannt[1]} (${zeile})` : zeile));
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
  const typ = n => (/png$/i.test(n) ? 'image/png' : /bmp$/i.test(n) ? 'image/bmp' : /tiff?$/i.test(n) ? 'image/tiff' : 'image/jpeg');
  return fs.readdirSync(ordner).filter(n => /\.(jpe?g|png|bmp|tiff?)$/i.test(n)).sort()
    .map(n => ({ name: n, mimeType: typ(n), base64: fs.readFileSync(path.join(ordner, n)).toString('base64') }));
}

/** Was Windows und der Treiber melden (Text zum Weitergeben); testscan = auch eine Seite übertragen. */
async function pruefen(id, testscan) {
  if (process.env.SCHADEN_SCAN_ORDNER) return 'Test-Scanner (Ordner statt Scanner)';
  const ordner = fs.mkdtempSync(path.join(os.tmpdir(), 'ayse-pruefen-'));
  let aus = await powershell(PRUEFEN_PS, { SCAN_ID: String(id || ''), SCAN_TEST: testscan ? '1' : '0', SCAN_ZIEL: ordner });
  const bild = path.join(ordner, 'test.jpg');
  if (fs.existsSync(bild)) { // Texterkennung auf dem Testscan – zeigt, ob das Bild lesbar ist
    const groesse = fs.statSync(bild).size;
    try { const r = await lesen(fs.readFileSync(bild).toString('base64')); aus += `\nTexterkennung: ${r.text.trim().length} Zeichen, Sicherheit ${r.sicherheit} %, Bild ${Math.round(groesse / 1024)} KB\nAnfang: ${r.text.trim().slice(0, 200).replace(/\s+/g, ' ')}`; }
    catch (e) { aus += `\nTexterkennung: ${e.message} (Bild ${Math.round(groesse / 1024)} KB)`; }
  }
  return aus;
}

// Texterkennung: ein Arbeiter für die ganze Sitzung (das Laden der deutschen Sprachdaten dauert ein paar Sekunden).
let arbeiter = null;
async function lesen(base64) {
  if (!arbeiter) {
    const { createWorker } = require('tesseract.js');
    const daten = path.dirname(require.resolve('@tesseract.js-data/deu/4.0.0/deu.traineddata.gz')).replace('app.asar', 'app.asar.unpacked');
    // errorHandler: sonst wirft tesseract.js Fehler des Arbeiters im Hauptprozess, und die ganze App stürzt ab
    arbeiter = createWorker('deu', 1, { langPath: daten, gzip: true, cacheMethod: 'none', errorHandler: () => {} });
  }
  try {
    const r = await (await arbeiter).recognize(Buffer.from(String(base64), 'base64'));
    return { text: r.data.text, sicherheit: Math.round(r.data.confidence) };
  } catch (e) {
    beenden().catch(() => {}); // nach einem Fehler frisch starten
    throw new Error('Texterkennung fehlgeschlagen: ' + (e && e.message || e));
  }
}
async function beenden() { if (arbeiter) { const w = await arbeiter; arbeiter = null; await w.terminate(); } }

module.exports = { geraete, stapel, pruefen, lesen, beenden };
