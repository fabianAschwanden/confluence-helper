# Confluence-Seiten bearbeiten

Dieser Ordner ist ein Arbeitsordner von confluence-helper (`cfh`). Er enthält lokale Kopien von Seiten aus Confluence Data Center, die du im Auftrag des Benutzers bearbeitest. Auf den Server kommt eine Änderung erst, wenn der Benutzer sie ausdrücklich zurückschreibt.

## Aufbau

- Jeder Ordner `<ID>-<titel>/` ist eine Seite; Unterseiten liegen als Unterordner darin.
  - `page.xhtml`: der Seiteninhalt im Confluence-Storage-Format. Nur diese Datei bearbeitest du.
  - `page.json`: Sync-Metadaten (Seiten-ID, Version, Prüfsumme, Zuordnung der Anhänge). Nie ändern.
  - `attachments/`: Anhänge der Seite, falls exportiert. Bestehende Dateien nicht umbenennen oder löschen.
  - `page.xhtml.bak`: Sicherung einer früheren Fassung. Nicht bearbeiten.
- `.github/`, `.vscode/`, `.run/`, `.cfh/`, `.editorconfig` und `AGENTS.md` gehören zur Einrichtung, nicht zu den Seiten.

## Werkzeug cfh

Aufruf im Terminal, Arbeitsverzeichnis ist dieser Ordner (Beispielordner `123456-projekthandbuch`):

```sh
{{CFH_SHELL}} validate 123456-projekthandbuch            # page.xhtml prüfen
{{CFH_SHELL}} diff 123456-projekthandbuch                # Unterschiede zum Server
{{CFH_SHELL}} push 123456-projekthandbuch --dry-run      # Probelauf, lädt nichts hoch
{{CFH_SHELL}} convert quelle.pdf                         # PDF, Excel oder CSV als Storage-XHTML ausgeben
```

`validate` meldet «OK – …» oder pro Problem eine Zeile `<pfad>:<zeile>:<spalte>: FEHLER: <meldung>` (bzw. `WARNUNG`) und endet dann mit Exit-Code 2.

## Arbeitsweise (verbindlich)

1. Bearbeite nur die `page.xhtml` der Seite, um die es geht. Andere Seitenordner nur auf ausdrücklichen Auftrag.
2. Ändere gezielt die betroffenen Stellen. Formatiere, rücke ein oder sortiere die Datei nie neu: Der Diff soll nur die gewünschte Änderung zeigen.
3. `page.json`, `page.xhtml.bak` und die Dateien in `attachments/` nie ändern, umbenennen oder löschen. Neue Anhänge legt der Benutzer selbst in `attachments/`; auf den Server kommen sie nur mit `push --attachments` (die Aufgabe «cfh: Zurückschreiben» setzt das). Verweist die Seite auf einen neuen Anhang, nenn den Dateinamen und weise darauf hin.
4. Nach jeder Änderung `validate` ausführen und jede FEHLER-Meldung beheben, bis «OK» erscheint. WARNUNG-Meldungen prüfen und in der Antwort erwähnen.
5. Zum Schluss `diff` ausführen und kurz zusammenfassen, was sich geändert hat. Klappt `diff` nicht (z.B. keine Verbindung), den Befehl nennen.
6. Führe `push`, `export --force`, `create` oder `import` nie aus und verwende nie `--force`, `--other-server`, `--url` oder `--profile` (und setze keine `CONFLUENCE_*`-Variablen), ohne dass der Benutzer genau diesen Schritt ausdrücklich bestätigt hat. Erlaubt sind `validate`, `diff`, `convert` und `push --dry-run`. Zurückschreiben macht normalerweise der Benutzer selbst (Aufgabe «cfh: Zurückschreiben» oder die Oberfläche von cfh).
7. Meldet `push` einen Versionskonflikt (Exit-Code 3), nicht mit `--force` wiederholen, sondern dem Benutzer melden.

{{STORAGE_RULES}}