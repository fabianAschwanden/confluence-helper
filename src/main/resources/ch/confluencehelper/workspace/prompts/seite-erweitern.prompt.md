---
description: 'Abschnitt einer Confluence-Seite erweitern und prüfen'
agent: 'agent'
argument-hint: 'Abschnitt und was ergänzt werden soll'
---
Erweitere eine Confluence-Seite in diesem Arbeitsordner.

- Seite: ${input:seite:Seitenordner, leer = die im Editor geöffnete page.xhtml}
- Abschnitt: ${input:abschnitt:Überschrift des Abschnitts oder «neuer Abschnitt am Ende»}
- Inhalt: ${input:inhalt:Was soll ergänzt werden?}

Ist keine Seite angegeben und keine `page.xhtml` im Editor offen, frag nach. Halte dich an die [Projektanweisungen](../copilot-instructions.md):

1. Lies die `page.xhtml` und finde den Abschnitt. Neue Inhalte im Stil der Seite schreiben (gleiche Überschriftenebenen, gleicher Aufbau von Listen und Tabellen).
2. Füge nur den neuen Inhalt ein; der Rest der Datei bleibt Zeichen für Zeichen gleich. Hat die Seite ein Seitenlayout (`<ac:layout>`), kommt ein neuer Abschnitt am Ende in die letzte `<ac:layout-cell>` der letzten Section, nie nach `</ac:layout>`.
3. Führe `{{CFH_SHELL}} validate <seitenordner>` aus und behebe alle FEHLER.
4. Führe `{{CFH_SHELL}} diff <seitenordner>` aus und fasse die Änderung kurz zusammen. Nicht zurückschreiben.
