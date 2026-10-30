# Confluence-Seiten mit GitHub Copilot bearbeiten (VS Code und IntelliJ)
Statt in [Claude Desktop](claude-desktop.md) kann GitHub Copilot die exportierten Seiten direkt im Editor bearbeiten. Der Arbeitsordner wird dazu ein Projekt für VS Code oder IntelliJ IDEA: Copilot kennt die Regeln des Storage-Formats, prüft seine Änderungen selbst mit `cfh validate` und zeigt den Diff. Zurückgeschrieben wird erst, wenn du es auslöst.

Beide Wege nutzen denselben Arbeitsordner und dieselben Regeln für das Storage-Format; du kannst sie mischen.

## Voraussetzungen
- **VS Code** mit der Erweiterung «GitHub Copilot Chat» (wird beim Öffnen des Ordners empfohlen).
- **IntelliJ IDEA** mit dem Plugin «GitHub Copilot», ab Plugin-Version 1.5.62 (davor wirkt `applyTo` in Anweisungsdateien nicht), für Prompt-Dateien ab 1.10.
- Ein Copilot-Abo mit Chat und Agent-Modus.
- `cfh` gebaut (`target/cfh.jar` vorhanden), siehe README.

Beim ersten Öffnen fragen beide, ob du dem Ordner vertraust: bestätigen, sonst laufen keine Aufgaben und der Agent führt keine Befehle aus.

## Einrichten
Es gibt drei Wege; alle legen dieselben Dateien an.

- **Automatisch in der Oberfläche:** `cfh ui` richtet den Standard-Arbeitsordner `~/Confluence` bei jedem Start ein bzw. bringt ihn auf den neuesten Stand. Einen anderen Ordner nur, wenn er schon einmal eingerichtet wurde.
- **Knopf in der Oberfläche:** Unter «Lokale Seiten» richtet «Für Copilot einrichten» den gewählten Arbeitsordner ein (nötig für andere Ordner als `~/Confluence`). «In VS Code öffnen» und «In IntelliJ öffnen» richten bei Bedarf ebenfalls ein und öffnen den Ordner als Projekt.
- **CLI:**
  ```sh
  cfh workspace init ~/Confluence      # ohne Ordner: aktueller Ordner
  ```
  Die CLI richtet nie von sich aus ein, auch nicht beim `export`.

## Öffnen
- **VS Code:** `code ~/Confluence` oder *Datei > Ordner öffnen …*. Den Arbeitsordner selbst öffnen, nicht einen Seitenordner oder einen Ordner darüber: Copilot liest `.github/` nur im Wurzelordner des Projekts.
- **IntelliJ:** *Datei > Öffnen …* > `~/Confluence`, «Als Projekt öffnen» bzw. «Trust Project» bestätigen.

«In VS Code öffnen» in der Oberfläche sucht `code` im `PATH` und unter `/Applications`, «In IntelliJ öffnen» sucht `idea` im `PATH` und die üblichen IntelliJ-Apps.

## Was angelegt wird
| Datei | Wofür |
| --- | --- |
| `.github/copilot-instructions.md` | Aufbau des Ordners, Arbeitsregeln für Copilot, alle Regeln und Bausteine des Storage-Formats; gilt in jedem Chat |
| `.github/instructions/confluence-storage.instructions.md` | Kurzregeln, die automatisch dazukommen, sobald Copilot eine `page.xhtml` bearbeitet (`applyTo: '**/page.xhtml'`) |
| `.github/prompts/*.prompt.md` | fertige Aufträge, im Chat mit `/name` aufrufen (siehe unten) |
| `AGENTS.md` | Kurzfassung mit Verweis, für andere Agenten (Codex, Copilot-Agent u.a.) |
| `.vscode/settings.json` | `*.xhtml` als XML, kein Formatieren, keine Leerzeichen- oder Zeilenend-Korrekturen; `page.json` und `page.xhtml.bak` schreibgeschützt; Freigaben für Terminal-Befehle des Agenten (siehe [Wie Copilot Befehle ausführt](#wie-copilot-befehle-ausführt)) |
| `.vscode/tasks.json` | Aufgaben «cfh: …» (siehe unten) |
| `.vscode/extensions.json` | empfiehlt GitHub Copilot Chat |
| `.run/*.run.xml` | dieselben Aufgaben als Run-Konfigurationen für IntelliJ |
| `.editorconfig` | `page.xhtml`/`page.json` nicht umschreiben (UTF-8, keine Leerzeichen entfernen) |
| `.cfh/workspace.json` | Prüfsummen der angelegten Dateien, für das Aktualisieren |

Die Dateien enthalten den absoluten Pfad zu deinem `bin/cfh`, damit Aufgaben und Agent ohne `PATH`-Eintrag funktionieren. Seitenordner werden nie angefasst.

## Typische Sitzung
1. **Exportieren:** in der Oberfläche, mit `cfh export https://wiki.firma.ch/display/TEAM/Projekthandbuch -o ~/Confluence` oder in der IDE mit der Aufgabe «cfh: Seite exportieren» (fragt nach URL oder ID).
2. **Öffnen:** Arbeitsordner in VS Code oder IntelliJ öffnen, die `page.xhtml` der Seite im Editor öffnen.
3. **Auftrag geben:** Copilot Chat öffnen, Modus **Agent** wählen und einen Prompt aufrufen, z.B. `/seite-erweitern`. Copilot fragt nach Abschnitt und Inhalt (leer gilt die offene `page.xhtml`). Freier Auftrag geht auch: «Ergänze ein Kapitel ‹Betrieb› mit einer Tabelle der Ansprechpersonen.»
4. **Copilot arbeitet:** Er ändert gezielt die `page.xhtml`, führt `cfh validate <seitenordner>` aus, behebt FEHLER, führt `cfh diff <seitenordner>` aus und fasst die Änderung zusammen.
5. **Prüfen:** Änderungen im Editor annehmen oder verwerfen. Selbst prüfen mit der Aufgabe «cfh: Prüfen (aktuelle Seite)» (VS Code: Cmd/Ctrl+Shift+B), Fehler stehen unter «Probleme».
6. **Diff:** Aufgabe «cfh: Unterschiede zum Server», auf Wunsch «cfh: Zurückschreiben – Probelauf».
7. **Zurückschreiben:** Aufgabe «cfh: Zurückschreiben (aktuelle Seite)». Sie fragt nach dem Versionskommentar; leer lassen oder Esc bricht ab, ohne etwas hochzuladen. Neue oder geänderte Dateien aus `attachments/` lädt sie mit hoch. Alternativ `cfh push ~/Confluence/123456-projekthandbuch -m "…"` oder in der Oberfläche.

Neue Bilder oder Dateien legst du selbst in `attachments/` der Seite; `cfh push` lädt sie nur mit `--attachments` hoch (Aufgaben und Run-Konfigurationen setzen es). Verweist die Seite auf einen Anhang, der danach auf dem Server fehlen würde, warnen Probelauf und Push. Bei einem Versionskonflikt (Exit-Code 3) siehe README, Workflow «Versionskonflikt».

### Prompts
Im Chat `/` tippen:

| Prompt | Auftrag |
| --- | --- |
| `/seite-erweitern` | Abschnitt um neuen Inhalt ergänzen |
| `/tabelle-ergaenzen` | Zeilen oder Spalten einer Tabelle ergänzen |
| `/zusammenfassung-oben` | Info-Panel «Zusammenfassung» oben einfügen |
| `/aus-datei-uebernehmen` | Inhalt aus PDF, Excel, CSV oder Text übernehmen (PDF/Excel/CSV über `cfh convert`) |
| `/pruefen-und-diff` | prüfen und Unterschiede zum Server zusammenfassen, ohne hochzuladen |

Alle laufen im Agent-Modus, fragen nach der Seite (leer gilt die offene `page.xhtml`) und schreiben nie zurück. In VS Code laufen Prompt-Dateien nur in lokalen Chat-Sitzungen («Local»).

### Aufgaben in VS Code
*Terminal > Task ausführen …* (bzw. Befehlspalette «Tasks: Run Task»); die aktuelle Seite ist die Seite der Datei im Editor:

| Aufgabe | Befehl |
| --- | --- |
| cfh: Prüfen (aktuelle Seite) | `cfh validate <datei>`; Fehler und Warnungen erscheinen unter «Probleme» (Cmd/Ctrl+Shift+M). Standard-Build-Aufgabe: Cmd/Ctrl+Shift+B |
| cfh: Unterschiede zum Server | `cfh diff <ordner>` |
| cfh: Zurückschreiben – Probelauf (aktuelle Seite) | `cfh push <ordner> --attachments --dry-run` |
| cfh: Zurückschreiben (aktuelle Seite) | `cfh push <ordner> --attachments -m <kommentar>`; ohne Kommentar bricht es ab (internes `--require-message`) |
| cfh: Seite exportieren | `cfh export <url> -o <arbeitsordner>` |

## Wie Copilot Befehle ausführt
Im Agent-Modus führt Copilot `cfh` selbst im integrierten Terminal aus, mit dem absoluten Pfad aus den Anweisungen. Vor jedem Befehl zeigt der Chat ihn an und wartet auf «Continue»/«Allow», ausser er ist freigegeben.

- **Ohne Rückfrage (nur VS Code)** laufen genau zwei Formen: `<pfad>/bin/cfh validate <seite>` und `<pfad>/bin/cfh diff <seite>`, also dein `bin/cfh`, eine Seite, keine Optionen und keine weiteren Befehle in derselben Zeile.
- **Immer mit Rückfrage:** `push` (auch `--dry-run`), `export`, `create`, `import`, `convert`, `ui`, `workspace`, die Optionen `--url`, `--profile`, `--other-server`, `--force` sowie gesetzte `CONFLUENCE_*=`/`CFH_CONFIG=`.
- Zusätzlich sind Copilot und jeder Prompt angewiesen, `push`, `export --force`, `create`, `import` und die genannten Optionen nur nach deiner ausdrücklichen Bestätigung genau dieses Schritts zu verwenden. Zurückschreiben machst normalerweise du selbst über die Aufgabe.

**Push braucht also immer deine Bestätigung.** Lehne im Chat ab, wenn Copilot `push` vorschlägt, ohne dass du es verlangt hast.

Die Freigaben stehen in `.vscode/settings.json` (`chat.tools.terminal.autoApprove`). Was VS Code tatsächlich erlaubt, hängt zusätzlich von deinen eigenen Einstellungen ab: Im Modus «Allow all» bzw. mit eigenen breiteren Freigaben fragt VS Code nicht mehr nach. Die Regeln sind eine Hilfe, keine Sicherheitsgrenze.

## IntelliJ: Unterschiede und Grenzen
- **Freigaben:** IntelliJ liest `.vscode/settings.json` nicht. Ob der Agent vor Terminal-Befehlen fragt, bestimmen allein die Einstellungen des Copilot-Plugins; ohne automatische Freigabe bestätigst du jeden Befehl, auch `validate` und `diff`.
- **Run-Konfigurationen statt Aufgaben:** Dieselben fünf Einträge erscheinen oben in der Run-Auswahl. Sie starten `target/cfh.jar` mit dem JDK, mit dem `cfh workspace init` lief, und nutzen die Makros `$FilePath$`/`$FileDir$` (Datei im Editor) und `$Prompt$` (fragt nach Kommentar bzw. Seiten-URL).
- **Abbrechen:** IntelliJ übergibt bei «Abbrechen» im `$Prompt$`-Dialog einen leeren Wert und startet trotzdem; «Zurückschreiben» und «Seite exportieren» brechen dann ab, ohne etwas hoch- oder herunterzuladen.
- **Keine «Probleme»-Liste:** Fehlermeldungen von «Prüfen» stehen in der Run-Konsole, mit absolutem Pfad, Zeile und Spalte, und sind dort anklickbar.
- **Run-Konfigurationen nur mit JAR:** Sie werden nur angelegt, wenn `cfh` aus `target/cfh.jar` läuft (normaler Aufruf über `bin/cfh`); sonst meldet `workspace init` `.run/` als «übersprungen». Ohne Run-Konfiguration geht es im Terminal von IntelliJ genauso: `cfh validate 123456-projekthandbuch`, `cfh diff …`, `cfh push …`.
- **Namespace-Meldungen:** IntelliJ zeigt bei `ac:`- und `ri:`-Elementen eventuell «Namespace nicht gebunden». Das ist bei Confluence-Storage normal; massgeblich ist `cfh validate`.
- «In IntelliJ öffnen» öffnet nur das Projekt, nicht die einzelne Seite.

## Nach Updates neu einrichten
Nach einem Update von confluence-helper oder wenn du das Repo verschoben hast:

```sh
cfh workspace init ~/Confluence
```

oder in der Oberfläche «Für Copilot einrichten» (für `~/Confluence` passiert es beim Start der Oberfläche von selbst).

- Dateien, die noch so sind, wie cfh sie angelegt hat, werden **aktualisiert**.
- Von dir geänderte Dateien und Dateien, die vorher schon da waren (z.B. eine eigene `.editorconfig`), bleiben **unverändert** und werden als «übersprungen» gemeldet.
- `cfh workspace init --force` bzw. «Trotzdem ersetzen» in der Oberfläche ersetzt auch diese und legt deine Fassung als `.bak` daneben (`AGENTS.md.bak`, bei Bedarf `AGENTS.md.2.bak` …). Eigene Anpassungen danach von Hand aus der `.bak` übernehmen.
- Von dir gelöschte Dateien legen nur `cfh workspace init` und der Knopf wieder an, nicht der Start der Oberfläche.
- Symbolische Links werden nie überschrieben.

Eigene Regeln besser in eine zusätzliche Datei schreiben, z.B. `.github/instructions/eigene.instructions.md`; die fasst cfh nie an.

## Fehlerbehebung
- **`cfh: … target/cfh.jar fehlt` oder «Befehl nicht gefunden» in Aufgaben/Agent:** Die Dateien zeigen auf den Pfad, an dem `bin/cfh` beim Einrichten lag. Repo verschoben oder noch nicht gebaut: `mvn -q package -DskipTests` im Repo, danach `cfh workspace init` erneut. Lief `cfh` beim Einrichten nicht aus dem JAR (z.B. aus der IDE), steht in den Dateien nur `cfh`; dann muss `cfh` im `PATH` sein (siehe README), oder mit `bin/cfh workspace init` neu einrichten.
- **«In VS Code öffnen»: VS Code wurde nicht gefunden:** in VS Code die Befehlspalette öffnen und «Shell Command: Install 'code' command in PATH» ausführen. **IntelliJ wurde nicht gefunden:** in IntelliJ *Tools > Create Command-line Launcher …* ausführen.
- **«Prüfen» zeigt nichts unter «Probleme»:** Die Aufgabe prüft die Datei im Editor; vorher die `page.xhtml` anklicken, nicht `page.json` oder das Terminal. Die Fehlerliste liest nur Zeilen der Form `<pfad>.xhtml:<zeile>:<spalte>: FEHLER: …`; die Ausgabe im Terminal-Panel der Aufgabe zeigt, was `cfh` gemeldet hat. Wurde `.vscode/tasks.json` von dir geändert, wird sie nicht aktualisiert (siehe oben, `--force`). Die Erweiterung «XML» von Red Hat ist nicht nötig und meldet zusätzlich Namespace-Fehler.
- **Copilot hält sich nicht an die Anweisungen:**
  - Ist der Arbeitsordner der Wurzelordner des Projekts (nicht ein Ordner darüber)? Vertraust du ihm?
  - VS Code: In der Antwort unter «References» sollte `copilot-instructions.md` stehen. Sonst in den Einstellungen `github.copilot.chat.codeGeneration.useInstructionFiles` einschalten, für die Prompts `chat.promptFiles`. Die Ordner in `chat.instructionsFilesLocations` und `chat.promptFilesLocations` müssen `.github/instructions` bzw. `.github/prompts` enthalten (Standard). Eine Organisationsrichtlinie kann das alles abschalten.
  - IntelliJ: Plugin aktualisieren (siehe Voraussetzungen) und in den Einstellungen des Copilot-Plugins die benutzerdefinierten Anweisungen (Custom Instructions) einschalten.
  - Modus prüfen: Im Modus «Ask» oder «Edit» führt Copilot keine Befehle aus; für `validate`/`diff` braucht es «Agent».
- **Prompts erscheinen nicht nach `/`:** VS Code: lokale Chat-Sitzung und `chat.promptFiles` (siehe oben). IntelliJ: Plugin ab 1.10.
- **Editor formatiert `page.xhtml` um:** `.vscode/settings.json` bzw. `.editorconfig` wurden übersprungen (eigene Fassung) oder eine Benutzereinstellung erzwingt Formatieren. Zeigt `cfh diff` danach lauter Leerzeichen-Änderungen: rückgängig machen oder die Seite neu exportieren (`cfh export … --force`, die lokale Fassung landet in `page.xhtml.bak`).
- **Windows:** Die Aufgaben starten `java.exe -jar target\cfh.jar` direkt (wie die Run-Konfigurationen), nicht `bin\cfh.cmd`: cmd.exe würde `&`, `|`, `%` oder `^` in Kommentar, Seiten-URL oder Pfad auswerten. Nur ohne JAR (Start aus dem Klassenordner) bleibt `cfh.cmd`. Nicht auf Windows getestet.
