---
description: 'Seite prüfen und Unterschiede zum Server zeigen, ohne hochzuladen'
agent: 'agent'
argument-hint: 'optional: Seitenordner'
---
Prüfe eine Confluence-Seite in diesem Arbeitsordner, ohne etwas hochzuladen.

- Seite: ${input:seite:Seitenordner, leer = die im Editor geöffnete page.xhtml}

1. Führe `{{CFH_SHELL}} validate <seitenordner>` aus. Bei FEHLER-Meldungen die Ursache erklären und erst nach Rückfrage korrigieren.
2. Führe `{{CFH_SHELL}} diff <seitenordner>` aus und fasse die Unterschiede zum Server in wenigen Punkten zusammen. Meldet `diff` eine neuere Server-Version, darauf hinweisen.
3. Optional `{{CFH_SHELL}} push <seitenordner> --dry-run` als Probelauf. Nie ohne `--dry-run` zurückschreiben; das macht der Benutzer selbst.
