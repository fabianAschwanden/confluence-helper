---
description: 'Regeln für Confluence-Seiten (page.xhtml im Storage-Format)'
applyTo: '**/page.xhtml'
---
Diese Datei ist eine Confluence-Seite im Storage-Format (XHTML-Fragment). Die vollständigen Regeln und Bausteine stehen in [copilot-instructions.md](../copilot-instructions.md).

- Nur die betroffenen Stellen ändern, die Datei nie neu formatieren oder einrücken.
- `ac:`- und `ri:`-Elemente samt Attributen (`ac:macro-id`, `ac:local-id`, `ri:filename`, `ac:task-id` …) unverändert lassen; neue Makros ohne `ac:macro-id`.
- Wohlgeformtes XML: `<br />`, `&amp;` statt `&`, Code im code-Makro mit `<![CDATA[ ... ]]>`.
- Danach `{{CFH_SHELL}} validate <seitenordner>` ausführen und alle FEHLER beheben.
- Beginnt die Seite mit `<ac:layout>`, neuen Inhalt nur innerhalb einer `<ac:layout-cell>` einfügen, nie ausserhalb von `<ac:layout>`.
- `page.json` nie ändern; `push` sowie `--force`, `--other-server`, `--url` und `--profile` nur nach ausdrücklicher Bestätigung des Benutzers.
