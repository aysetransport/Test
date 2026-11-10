package de.aysetransport.schaden.data.model

/**
 * Bearbeitungsstand eines Schadensfalls.
 *
 * [offen] entscheidet, ob der Fall noch in der Wiedervorlage auftaucht. Die Namen der
 * Konstanten werden in der Datenbank und im Backup gespeichert und dürfen deshalb nicht
 * umbenannt werden.
 */
enum class FallStatus(val label: String, val offen: Boolean) {
    NEU("Neu", true),
    IN_PRUEFUNG("In Prüfung", true),
    WARTET_AUF_RUECKMELDUNG("Wartet auf Rückmeldung", true),
    BEI_VERSICHERUNG("Bei Versicherung", true),
    VERGLEICH_ANGEBOTEN("Vergleich angeboten", true),
    ZAHLUNG_OFFEN("Zahlung offen", true),
    ERLEDIGT("Erledigt", false),
    ABGELEHNT("Abgelehnt", false),
}

enum class Schadensart(val label: String) {
    WARE("Beschädigung der Ware"),
    GEBAEUDE("Schaden an Gebäude / Einrichtung"),
    VERLUST("Verlust / Fehlmenge"),
    VERZUG("Lieferverzug"),
    FAHRZEUG("Fahrzeug- / Verkehrsschaden"),
    SONSTIGES("Sonstiges"),
}

/** Von wem die nächste Rückmeldung erwartet wird – also bei wem nachgehakt werden muss. */
enum class Ansprechpartner(val label: String) {
    ANSPRUCHSTELLER("Geschädigter"), // Name bleibt: steht so in Datenbank und Cloud
    AUFTRAGGEBER("Auftraggeber"),
    VERSICHERUNG("Versicherung"),
    FAHRER("Fahrer"),
    GUTACHTER("Gutachter / Werkstatt"),
    INTERN("Intern"),
}

enum class VerlaufTyp(val label: String) {
    ANGELEGT("Fall angelegt"),
    NOTIZ("Notiz"),
    ANRUF("Telefonat"),
    EMAIL("E-Mail"),
    NACHRICHT("Nachricht"),
    BRIEF("Schreiben"),
    STATUS("Statusänderung"),
    WIEDERVORLAGE("Wiedervorlage"),
    ANLAGE("Anlage"),
}
