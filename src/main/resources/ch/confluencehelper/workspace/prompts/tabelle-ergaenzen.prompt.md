---
description: 'Tabelle einer Confluence-Seite ergänzen oder ändern'
agent: 'agent'
argument-hint: 'welche Tabelle und welche Zeilen oder Spalten'
---
Ergänze eine Tabelle auf einer Confluence-Seite in diesem Arbeitsordner.

- Seite: ${input:seite:Seitenordner, leer = die im Editor geöffnete page.xhtml}
- Tabelle: ${input:tabelle:Welche Tabelle? z.B. Überschrift davor oder Spaltennamen}
- Änderung: ${input:aenderung:Welche Zeilen oder Spalten sollen dazukommen oder sich ändern?}

Halte dich an die [Projektanweisungen](../copilot-instructions.md):

1. Neue Zeilen genau wie die bestehenden aufbauen (`<th>`/`<td>`, `<p>` in Zellen, Attribute wie `class` oder `colspan`). `<colgroup>` und Tabellenattribute bleiben. Bei einer neuen Spalte auch `<colgroup>` und die Kopfzeile ergänzen.
2. Text in Zellen maskieren (`&amp;`, `&lt;`), Datums- und Zahlenformate der Tabelle übernehmen.
3. Führe `{{CFH_SHELL}} validate <seitenordner>` aus und behebe alle FEHLER.
4. Führe `{{CFH_SHELL}} diff <seitenordner>` aus und fasse die Änderung kurz zusammen. Nicht zurückschreiben.
