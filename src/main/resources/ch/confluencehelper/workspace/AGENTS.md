# Hinweise für KI-Agenten

Dieser Ordner enthält lokale Kopien von Confluence-Seiten. Die vollständigen Regeln stehen in [.github/copilot-instructions.md](.github/copilot-instructions.md); lies sie vor der ersten Änderung.

Das Wichtigste:

- Jede Seite ist ein Ordner `<ID>-<titel>/`. Bearbeitet wird nur `page.xhtml` (Confluence-Storage-Format), `page.json` nie.
- Gezielt ändern statt die Datei neu zu schreiben; `ac:`/`ri:`-Elemente und Makro-IDs bleiben unverändert.
- Nach jeder Änderung `{{CFH_SHELL}} validate <seitenordner>` ausführen und alle FEHLER beheben, danach `diff`.
- `push`, `export --force`, `create` und `import` sowie die Optionen `--force`, `--other-server`, `--url` und `--profile` nie ohne ausdrückliche Bestätigung des Benutzers; die Optionen lenken das Token auf einen anderen Server oder überschreiben fremde Änderungen.
- Hat die Seite ein Seitenlayout (`<ac:layout>` zuoberst), gehört neuer Inhalt in eine `<ac:layout-cell>`, nie davor oder danach.
- Neue Anhänge legt der Benutzer in `attachments/`; hochgeladen werden sie nur mit `push --attachments`.
