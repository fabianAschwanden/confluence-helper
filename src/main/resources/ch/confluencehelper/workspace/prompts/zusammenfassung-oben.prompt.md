---
description: 'Zusammenfassung als Info-Panel oben auf der Seite einfügen'
agent: 'agent'
argument-hint: 'optional: Titel oder Schwerpunkt der Zusammenfassung'
---
Füge oben auf einer Confluence-Seite eine kurze Zusammenfassung ein.

- Seite: ${input:seite:Seitenordner, leer = die im Editor geöffnete page.xhtml}
- Schwerpunkt: ${input:fokus:Worauf soll die Zusammenfassung achten? (leer = allgemein)}

Halte dich an die [Projektanweisungen](../copilot-instructions.md):

1. Lies die ganze Seite und fasse sie in 3–5 Punkten zusammen, sachlich und ohne neue Fakten.
2. Füge ein Info-Panel mit dem Titel «Zusammenfassung» ganz oben ein, nach einem vorhandenen Inhaltsverzeichnis (`toc`-Makro). Gibt es schon ein solches Panel, ersetze nur dessen Inhalt. Beginnt die Seite mit `<ac:layout>`, kommt das Panel an den Anfang der ersten `<ac:layout-cell>`, nie vor `<ac:layout>`.

   ```xml
   <ac:structured-macro ac:name="info" ac:schema-version="1"><ac:parameter ac:name="title">Zusammenfassung</ac:parameter><ac:rich-text-body><ul><li><p>Punkt</p></li></ul></ac:rich-text-body></ac:structured-macro>
   ```
3. Führe `{{CFH_SHELL}} validate <seitenordner>` aus und behebe alle FEHLER.
4. Führe `{{CFH_SHELL}} diff <seitenordner>` aus und zeige die Zusammenfassung. Nicht zurückschreiben.
