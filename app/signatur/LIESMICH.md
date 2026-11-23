# Signaturschlüssel der Android-App

Bewusst im (privaten) Repository, damit jeder Build ohne GitHub-Secrets dieselbe gültige Signatur trägt und sich
über die installierte App installieren lässt (In-App-Update).

- `ayse-crm.p12`: Schlüssel (PKCS12, RSA 3072, Alias `ayse-crm`, gültig bis 2057)
- `passwort.txt`: Passwort für Schlüsselspeicher und Schlüssel

Wer dieses Repository lesen kann, kann Apps bauen, die sich als Update über Ayse CRM installieren lassen. Deshalb das
Repository privat halten. Sind die Secrets `SIGNATUR_BASE64`, `SIGNATUR_PASSWORT` und `SIGNATUR_ALIAS` gesetzt, gehen
sie vor. Den Schlüssel niemals wechseln: Installierte Apps nehmen dann keine Updates mehr an.
