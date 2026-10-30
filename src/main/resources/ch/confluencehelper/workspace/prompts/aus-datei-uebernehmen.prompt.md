---
description: 'Inhalt aus einer Datei (PDF, Excel, CSV, Text) in eine Seite übernehmen'
agent: 'agent'
argument-hint: 'Quelldatei und Zielabschnitt'
---
Übernimm Inhalte aus einer Datei in eine Confluence-Seite in diesem Arbeitsordner.

- Seite: ${input:seite:Seitenordner, leer = die im Editor geöffnete page.xhtml}
- Quelle: ${input:quelle:Pfad der Quelldatei (PDF, Excel, CSV, Text oder Markdown)}
- Ziel und Umfang: ${input:ziel:Was soll wohin? z.B. «Tabelle Preise als neuen Abschnitt am Ende»}

Halte dich an die [Projektanweisungen](../copilot-instructions.md):

1. PDF, Excel und CSV zuerst lokal umwandeln: `{{CFH_SHELL}} convert <quelle>` gibt Storage-XHTML auf der Konsole aus (mit `--sheet NAME` nur ein Excel-Blatt). Es wird nichts hochgeladen. Text und Markdown direkt lesen.
2. Übernimm nur die gewünschten Teile und passe sie an die Seite an (Überschriftenebenen, Tabellenaufbau). Die übrige Seite bleibt unverändert.
3. Führe `{{CFH_SHELL}} validate <seitenordner>` aus und behebe alle FEHLER.
4. Führe `{{CFH_SHELL}} diff <seitenordner>` aus und fasse zusammen, was übernommen wurde und was nicht. Nicht zurückschreiben.
