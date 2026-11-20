#!/usr/bin/env python3
"""
Prüft eine XRechnung (CII) gegen
  1. das offizielle XML-Schema (UN/CEFACT CII D16B),
  2. die Geschäftsregeln der EN 16931 (CEN-Schematron, CII) und
  3. die deutschen XRechnung-Regeln (KoSIT-Schematron, BR-DE-*).
Schematron wird mit elementpath (XPath 3.1) ausgewertet; Regeln mit nicht unterstützten Funktionen werden gemeldet.

  python3 tools/erechnung-pruefen.py rechnung.xml REGELN_ORDNER
REGELN_ORDNER enthält:
  en16931/cii/schema/...  (github.com/ConnectingEurope/eInvoicing-EN16931)
  xr-sch/src/validation/schematron/cii/XRechnung-CII-validation.sch (github.com/itplr-kosit/xrechnung-schematron)
Ausgabe: JSON {xsd: [...], fehler: [...], warnungen: [...], nicht_geprueft: n}; Exit-Code 1 bei Fehlern.
"""
import json, sys, glob, os, re
from lxml import etree
import elementpath
from elementpath.xpath3 import XPath3Parser

SCH = 'http://purl.oclc.org/dsdl/schematron'


def schema(regeln):
    xsd = glob.glob(os.path.join(regeln, 'en16931/cii/schema/*/uncoupled clm/CII/uncefact/data/standard/CrossIndustryInvoice_100pD16B.xsd'))[0]
    return etree.XMLSchema(etree.parse(xsd))


def lade_sch(pfad):
    baum = etree.parse(pfad)
    # sch:include auflösen (XRechnung-Datei bindet common.sch ein)
    for inc in baum.getroot().iter('{%s}include' % SCH):
        ziel = etree.parse(os.path.join(os.path.dirname(pfad), inc.get('href'))).getroot()
        inc.getparent().replace(inc, ziel)
    return baum.getroot()


def pruefe_sch(dok, sch, ergebnis):
    ns = {n.get('prefix'): n.get('uri') for n in sch.iter('{%s}ns' % SCH)}
    globale = {}
    for let in [l for l in sch.iter('{%s}let' % SCH) if etree.QName(l.getparent()).localname != 'rule']:  # Schema- und Gruppenvariablen, auch aus eingebundenen Dateien (Namen sind eindeutig)
        try:
            globale[let.get('name')] = elementpath.select(dok, let.get('value'), namespaces=ns, parser=XPath3Parser, variables=dict(globale))
        except Exception:
            pass
    for muster in sch.iter('{%s}pattern' % SCH):
        if muster.get('abstract') == 'true':
            continue
        gesehen = set()
        mvars = globale
        for regel in muster.findall('{%s}rule' % SCH):
            if regel.get('abstract') == 'true' or not regel.get('context'):
                continue
            ctx = regel.get('context')
            try:
                knoten = elementpath.select(dok, ctx if ctx.startswith('/') else '//' + ctx, namespaces=ns, parser=XPath3Parser, variables=dict(mvars))
            except Exception as e:
                ergebnis['nicht_geprueft'] += 1
                ergebnis.setdefault('nicht_geprueft_ids', []).append(f"Kontext {ctx[:60]}: {str(e)[:120]}")
                continue
            for k in knoten if isinstance(knoten, list) else [knoten]:
                if not isinstance(k, etree._Element) or id(k) in gesehen:
                    continue
                gesehen.add(id(k))
                variablen = dict(mvars)
                for let in regel.findall('{%s}let' % SCH):
                    try:
                        variablen[let.get('name')] = elementpath.select(dok, let.get('value'), namespaces=ns, parser=XPath3Parser, variables=dict(variablen), item=k)
                    except Exception:
                        pass
                for a in regel.findall('{%s}assert' % SCH):
                    try:
                        ok = elementpath.select(dok, 'boolean(' + a.get('test') + ')', namespaces=ns, parser=XPath3Parser, variables=dict(variablen), item=k)
                    except Exception as e:
                        ergebnis['nicht_geprueft'] += 1
                        ergebnis.setdefault('nicht_geprueft_ids', []).append(f"{a.get('id')}: {str(e)[:120]}")
                        continue
                    if not ok:
                        text = re.sub(r'\s+', ' ', ''.join(a.itertext())).strip()
                        (ergebnis['warnungen'] if a.get('flag') == 'warning' else ergebnis['fehler']).append(f"{a.get('id')}: {text[:200]}")


def iban_ok(iban):
    s = re.sub(r'\s', '', iban).upper()
    if not re.fullmatch(r'[A-Z]{2}[0-9]{2}[A-Z0-9]{8,30}', s):
        return False
    return int(''.join(str(int(c, 36)) for c in s[4:] + s[:4])) % 97 == 1


def pruefe_iban(dok, ergebnis):
    # BR-DE-19 nutzt eine XSLT-Hilfsfunktion (xr:checkIBAN) – hier direkt nachgebildet
    ns = {'ram': 'urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100'}
    ergebnis['nicht_geprueft_ids'] = [x for x in ergebnis.get('nicht_geprueft_ids', []) if not x.startswith('BR-DE-19')]
    ergebnis['nicht_geprueft'] = len(ergebnis['nicht_geprueft_ids'])
    for m in dok.iterfind('.//ram:SpecifiedTradeSettlementPaymentMeans[ram:TypeCode="58"]', ns):
        for i in m.iterfind('.//ram:IBANID', ns):
            if not iban_ok(i.text or ''):
                ergebnis['warnungen'].append('BR-DE-19: IBAN ' + (i.text or '') + ' ist keine gültige IBAN (Prüfziffer)')


def main():
    datei, regeln = sys.argv[1], sys.argv[2]
    dok = etree.parse(datei)
    ergebnis = {'xsd': [], 'fehler': [], 'warnungen': [], 'nicht_geprueft': 0}
    xsd = schema(regeln)
    if not xsd.validate(dok):
        ergebnis['xsd'] = [str(e) for e in xsd.error_log][:20]
    pruefe_sch(dok, lade_sch(os.path.join(regeln, 'en16931/cii/schematron/preprocessed/EN16931-CII-validation-preprocessed.sch')), ergebnis)
    pruefe_sch(dok, lade_sch(os.path.join(regeln, 'xr-sch/src/validation/schematron/cii/XRechnung-CII-validation.sch')), ergebnis)
    pruefe_iban(dok, ergebnis)
    ergebnis['fehler'] = sorted(set(ergebnis['fehler']))
    ergebnis['warnungen'] = sorted(set(ergebnis['warnungen']))
    print(json.dumps(ergebnis, ensure_ascii=False, indent=1))
    sys.exit(1 if ergebnis['xsd'] or ergebnis['fehler'] else 0)


if __name__ == '__main__':
    main()
