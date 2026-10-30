# confluence-helper – Anweisungen für die Weiterentwicklung

Java-CLI `cfh` mit lokaler Web-UI für **Confluence Data Center / Server** (REST API v1). Seiten aus
PDF/Excel/CSV anlegen, als Storage-XHTML exportieren, extern bearbeiten (Claude Desktop, Copilot) und mit
Versionsprüfung zurückschreiben. Harte Anforderung: **nur normale Lese-/Schreibrechte** – keine Admin-Endpunkte,
keine App-Installation.

> Diese Datei gilt für die Arbeit am **Quellcode** dieses Repos. Die Copilot-Anweisungen für den
> *Arbeitsordner mit Confluence-Seiten* sind Vorlagen unter `src/main/resources/ch/confluencehelper/workspace/`.

## Verbindliche Spezifikationen
Vor jeder Änderung lesen; bei Verhaltensänderungen im selben Commit nachführen:
- `docs/SPEC.md` – CLI, REST-Endpunkte, Dateilayout, Exit-Codes, Konverter
- `docs/UI-SPEC.md` – Web-UI: API, Sicherheitsregeln, Frontend, Starter
- `docs/copilot.md`, `docs/claude-desktop.md` – Arbeitsordner für Copilot bzw. Claude Desktop
- `README.md` – Benutzer-Doku (Befehle und Optionen müssen mit `--help` übereinstimmen)

## Aufbau (`src/main/java/ch/confluencehelper/`)
| Package | Inhalt |
|---|---|
| `client` | `ConfluenceClient` (java.net.http + Jackson), `PageRefs` (IDs/URLs parsen), Records `Page`, `PageRef`, `Attachment` |
| `config` | `Config` (Datei → Env → CLI-Option, Profile), `ConfigFile` (Schreiben, Rechte 600) |
| `storage` | `StorageXml`: Escaping, XXE-sicherer Validator, Entities in `resources/.../storage/xhtml-entities.ent` |
| `convert` | `PdfConverter` (PDFBox), `ExcelConverter` (POI), `CsvConverter`, `Converters.forFile` |
| `sync` | Fachlogik für CLI **und** UI: `Exporter`, `Pusher`, `Creator`, `Differ`, `PageStore`, `PageApi` |
| `cli` | picocli-Befehle; nur Ein-/Ausgabe, keine Fachlogik |
| `ui` | `UiServer` (com.sun.net.httpserver, nur 127.0.0.1), `UiApi`; Frontend in `resources/.../ui/static/` |
| `workspace` | `WorkspaceSetup`: erzeugt `.github/`, `.vscode/`, `.run/` usw. im Arbeitsordner aus Vorlagen |

## Bauen und testen
```sh
mvn -B verify                          # alle Tests (JUnit 5 + AssertJ), muss grün bleiben
mvn -q -B package -DskipTests          # Fat-JAR target/cfh.jar
bin/cfh --help                         # CLI (Windows: bin\cfh.cmd)
bin/cfh-ui                             # Web-UI
```
- **Mehrere Builds parallel:** `-Dbuild.dir=$PWD/target-x` verwenden.
- **Ohne echtes Confluence:** Tests laufen gegen `src/test/java/ch/confluencehelper/testing/FakeConfluenceServer`. Neue REST-Aufrufe zuerst dort nachbilden, so wie Data Center sich verhält.
- **UI manuell:** `UiDevServer` (Testklasse) startet die UI mit Demo-Space `DEMO` und druckt `UI_URL=…`. Mit `edit <id>` über stdin simulierst du eine fremde Änderung.
- **Browser-Test:** `src/test/ui-e2e/ui-e2e.mjs` (Playwright, Aufruf siehe README). Nach Änderungen an `static/*` ausführen.

## Regeln für Änderungen
- **Java 21** als Ziel (`maven.compiler.release`), keine Preview-Features.
- **Neue Dependencies** nur mit gutem Grund und fester Version. Die UI kommt bewusst ohne Framework und CDN aus.
- **Sprache:**
  - Benutzertexte (CLI, UI, Fehlermeldungen, Doku) auf Deutsch in Schweizer Rechtschreibung: «ss», nie «ß».
  - Code-Kommentare englisch und sparsam, im Stil des umgebenden Codes.
- **Fachlogik nur in `sync/`** (bzw. `convert/`, `storage/`). CLI und UI rufen dieselben Services auf, damit sich beide gleich verhalten. Keine Logik in beiden Schichten doppelt pflegen.
- **Exit-Codes** (CLI): 0 ok, 1 Fehler, 2 Validierung, 3 Versionskonflikt, 4 Anmeldung/Rechte. Die UI meldet dieselben Fälle über `kind`.
- **`page.xhtml` byte-genau** lesen und schreiben (UTF-8). Keine Umformatierung, keine Normalisierung beim Speichern.
- **Jede Änderung mit Test.** Fehlerbehebungen bekommen einen Regressionstest.
- **Storage-Regeln an einer Stelle:**
  - Die Regeln stehen in `resources/.../workspace/storage-rules.md`.
  - `docs/claude-desktop.md` enthält eine Kopie davon, die `WorkspaceSetupTest` prüft. Beim Ändern beide anpassen.
  - XML-Beispiele in den Vorlagen müssen `StorageXml.validate` bestehen.

## Sicherheit (nicht aufweichen)
- **Zugangsdaten** (Token, Passwort) nie loggen, nie in Exceptions, `toString()` oder API-Antworten der UI, nie ins Repo. Tests nur mit Fake-Werten.
- **HTTP-Client:** keine Redirects folgen. TLS-Prüfung nie abschalten. Gespeicherte Zugangsdaten nur für denselben Host verwenden.
- **XML:** keine externen Entities oder DTDs laden (XXE).
- **Dateien:** Pfade aus Server-Daten oder UI-Anfragen absichern. Kein Path Traversal, keine Symlinks folgen, Windows-reservierte Namen meiden. UI-Pfade müssen im Arbeitsordner liegen.
- **UI-Server:**
  - Bindet nur an `127.0.0.1`. Jede `/api`-Anfrage braucht den Header `X-CFH-Token` (konstantzeitiger Vergleich); dazu Host- und Origin-Prüfung und die CSP aus `docs/UI-SPEC.md`.
  - Im Frontend Server- und Benutzerdaten nur über `textContent` einfügen, kein `innerHTML`. Keine Inline-Skripte oder -Styles.
- **Prozesse starten** (Browser, Finder/Explorer, IDE) nur über `ProcessBuilder` mit Argumentliste, nie über eine Shell.
- **Erzeugte IDE-Dateien:**
  - Ohne Rückfrage dürfen nur `cfh validate` und `cfh diff` laufen.
  - `push`, `export`, `--force`, `--url`, `--profile`, `--other-server` und `CONFLUENCE_*` brauchen immer eine Bestätigung.

## Grenzen
- Kein OCR. Die PDF-Struktur wird heuristisch erkannt.
- Confluence normalisiert den Storage beim Speichern. `push` übernimmt deshalb den Server-Stand in `page.xhtml`.
- Getestet nur gegen den Fake-Server. Verhalten echter Data-Center-Versionen bei Änderungen an `ConfluenceClient` gegen die Atlassian-Doku prüfen.
