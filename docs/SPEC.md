# confluence-helper — Spezifikation

CLI (`cfh`) für Confluence **Data Center / Server** (REST API v1). Braucht nur normale
Lese- und Schreibrechte auf die betroffenen Seiten/Spaces — keine Admin-, Space-Admin- oder
App-Installation. Austauschformat ist das **Confluence Storage-Format (XHTML)**, verlustfrei.

Java 21 (Bytecode-Ziel), Maven, picocli, Jackson, PDFBox 3, Apache POI, `java.net.http`.
Basis-Package `ch.confluencehelper`. Fat-JAR via Shade, Startskript `bin/cfh`.
Weboberfläche (`cfh ui`) und Starter: siehe [UI-SPEC.md](UI-SPEC.md).

## Konfiguration (`config.Config`)
Quellen, absteigende Priorität: CLI-Optionen (`--url`, `--profile`) → Umgebungsvariablen →
Datei `~/.config/confluence-helper/config.properties` (oder `$CFH_CONFIG`).

| Key (Datei)  | Env                    | Bedeutung |
|--------------|------------------------|-----------|
| `url`        | `CONFLUENCE_URL`       | Basis-URL inkl. Kontextpfad, z.B. `https://wiki.firma.ch` oder `https://firma.ch/confluence` |
| `token`      | `CONFLUENCE_TOKEN`     | Personal Access Token → `Authorization: Bearer …` (bevorzugt) |
| `username`   | `CONFLUENCE_USER`      | Fallback Basic Auth, falls PAT deaktiviert |
| `password`   | `CONFLUENCE_PASSWORD`  | Fallback Basic Auth |

- Profile in der Datei: `<profil>.url`, `<profil>.token`, … ; ohne Profil gelten Keys ohne Präfix.
- Datei mit Gruppen-/Welt-Leserechten → Warnung auf stderr (POSIX, sonst ignorieren).
- Tokens/Passwörter erscheinen nie in Logs, Fehlermeldungen oder `toString()`.
- Werte werden um Leerraum inkl. unsichtbarer Zeichen gekürzt (NBSP U+00A0/U+202F, Zero-Width U+200B,
  BOM), die beim Kopieren anhaften. Ein Token mit anderen Zeichen als druckbarem ASCII (0x21–0x7E) bzw.
  Benutzername/Passwort mit Steuer-/Formatzeichen → `ConfigException` ohne den Wert in der Meldung.
  Zusätzlich fängt der Client einen ungültigen `Authorization`-Header ab (die JDK-Meldung enthielte den Token).
- Proxy: `ProxySelector.getDefault()`; Firmen-CAs über JVM-Truststore (README dokumentiert `JAVA_TOOL_OPTIONS`).

## REST-Client (`client.ConfluenceClient`)
Endpunkte (alle relativ zu `url`):
- `GET  /rest/api/user/current` — Verbindungstest
- `GET  /rest/api/content/{id}?expand=body.storage,version,space,ancestors`
- `GET  /rest/api/content?type=page&spaceKey=K&title=T&expand=version,space,ancestors` — Server/DC vergleicht
  Titel ohne Gross-/Kleinschreibung; `findPage` nimmt einen exakten Treffer (NFC-normalisiert), sonst den
  einzigen Treffer ohne Beachtung der Gross-/Kleinschreibung.
- `GET  /rest/api/content/{id}/child/page?limit=…&start=…` (Paging über `start`/`size`/`_links.next`)
- `POST /rest/api/content` — `{type:"page",title,space:{key},ancestors:[{id}],body:{storage:{value,representation:"storage"}}}`
- `PUT  /rest/api/content/{id}` — wie POST plus `version:{number:n+1,message}`; `ancestors` nur mitsenden, wenn Parent geändert werden soll
- `GET  /rest/api/content/{id}/child/attachment?limit=…` ; Download über `_links.download` (relativ zur Basis-URL, Kontextpfad beachten!)
- `POST /rest/api/content/{id}/child/attachment` (neu) bzw. `POST …/child/attachment/{attId}/data` (neue Version), multipart `file` + `comment`, Header `X-Atlassian-Token: no-check`.
  Beim Push wird die bekannte Anhang-ID und der Original-Anhangname (multipart `filename`) verwendet, nicht der lokale Dateiname.

Fehler: HTTP-Status → `ConfluenceException` mit Status und der `message` aus dem JSON-Fehlerkörper.
401/403 mit verständlichem Hinweis (Token/Rechte), 404 „Seite nicht gefunden oder keine Leserechte“,
409 beim PUT = Versionskonflikt. Timeouts (connect 15 s, request 60 s). Kein Retry bei POST/PUT.

`client.PageRefs.resolvePageId(String)` akzeptiert: reine ID, `…/pages/viewpage.action?pageId=123`,
`…/pages/123/…`, `…/display/SPACE/Title+Mit+Plus` (→ `findPage`, URL-decodiert, `+` = Leerzeichen),
`…/spaces/SPACE/pages/123/…`. Gibt bei display-URLs `PageRef` mit spaceKey+title zurück.
`PageRefs.parse(eingabe, basisUrl)` (Export, `--parent`) lehnt URLs ab, deren Host, Port (bei gleichem Schema) oder
Kontextpfad nicht zur verbundenen Basis-URL passen: Seiten-IDs sind nur pro Server eindeutig.

## Lokales Layout (Export / Push)
```
<out>/<id>-<slug>/
  page.xhtml        Storage-Format, unverändert wie vom Server (keine Umformatierung!)
  page.json         PageMeta: id, title, spaceKey, version, parentId, baseUrl, webUrl, exportedAt,
                    contentHash, attachments
  page.xhtml.bak    nur nach «export --force» über lokale Änderungen: die überschriebene Fassung
  attachments/      nur mit --attachments
```
`page.xhtml` wird **byte-genau** gespeichert (UTF-8), damit `diff` und Versionsprüfung sauber sind.
Nach `push`/`create` enthält `page.xhtml` den Storage, den der Server tatsächlich gespeichert hat
(Confluence normalisiert beim Speichern, z.B. `<br/>` → `<br />`, ergänzt `ac:macro-id`); fehlt er in der
Antwort, wird die Seite nachgeladen. Sonst würde jeder weitere Push eine neue Version erzeugen.

- `contentHash`: SHA-256 des zuletzt mit dem Server ausgetauschten Storage (nur Zeilenenden normalisiert).
  Daran erkennt `export`, ob `page.xhtml` lokal bearbeitet wurde.
- `attachments`: pro lokaler Datei `{id, title, size, sha256}` des heruntergeladenen bzw. hochgeladenen
  Anhangs. Lokale Namen können vom Anhangnamen abweichen (`Slugs.safeFileName`: Windows-verbotene Zeichen
  → `_`, NFC; Kollisionen → `<id>-name`); die Zuordnung läuft über diese Tabelle.

## Befehle
- `cfh whoami` — Verbindung + Benutzer prüfen.
- `cfh export <seite> [-o DIR] [--attachments] [--children [--depth N]] [--force]`
  - Überschreibt `page.xhtml` nur, wenn es keine lokalen Änderungen enthält (gleich Server-Inhalt oder
    gleich `contentHash`) — unabhängig davon, ob der Server inzwischen eine neuere Version hat. Ohne
    `page.json`/`contentHash` gilt jede Abweichung als Änderung. Sonst Abbruch (Exit 1) mit Hinweis
    (`push`, anderer Ordner `-o`, `--force`); mit `--force` wird die lokale Fassung als `page.xhtml.bak` gesichert.
  - `--attachments`: alle Anhänge erst in einen versteckten Staging-Ordner laden; lokal seit dem letzten
    Export geänderte Dateien (Hash ≠ `page.json`) blockieren ohne `--force`; auf dem Server gelöschte,
    lokal unveränderte Dateien werden entfernt; eigene neue Dateien bleiben. Dateinamen früherer Exporte bleiben stabil.
  - Kein Export in einen Seiten- oder `attachments/`-Ordner, der ein symbolischer Link ist.
- `cfh push <ordner|page.xhtml> [--title T] [-m MSG] [--dry-run] [--force] [--attachments] [--other-server]`
  - versteckt `--require-message` (für IDE-Aufgaben): ohne bzw. mit leerem `-m` Abbruch (Exit 1) vor jedem Serverzugriff,
    ausser beim Probelauf. IntelliJ macht aus «Abbrechen» bei `$Prompt$` einen leeren Wert.
  - Warnung (Probelauf und Push) pro `ri:attachment` der Seite (ohne verschachteltes `ri:page`/`ri:blog-post` …,
    `StorageXml.attachmentRefs`), das weder auf dem Server liegt noch mit diesem Push hochgeladen wird; liegt die
    Datei in `attachments/` und fehlt `--attachments`, nennt die Warnung die Option. Ist die Anhangliste nicht
    lesbar, keine Warnung.
  - `page.json.baseUrl` ≠ aktuelle Basis-URL (normalisiert: Schema/Host klein, Standard-Port, `/` am Ende)
    → Abbruch (Exit 1) mit beiden URLs; nur `--other-server` erlaubt das (nicht `--force`) und verknüpft neu.
    `page.json.spaceKey` ≠ Space der Seite → Abbruch (überschreibbar mit `--force`).
  - `--title T` mit einem Titel, den im Space schon eine andere Seite hat → Abbruch vor dem PUT (Exit 1).
  - liest `page.json`, holt aktuelle Version; Server-Version ≠ `page.json.version` → Abbruch
    (Exit 3) mit Hinweis auf `cfh diff`/`--force`/Export in anderen Ordner. Hat der Server bereits genau den
    lokalen Inhalt und Titel (z.B. eigener Push, dessen `page.json`-Update fehlschlug), wird seine Version übernommen.
  - validiert XHTML vorher (`StorageXml.validate`), Fehler → Abbruch (Exit 2).
  - Inhalt und Titel unverändert → „keine Änderung“, kein PUT.
  - direkt nach dem PUT `page.xhtml` (Server-Storage) und `page.json` schreiben, erst danach Anhänge.
    Schlägt danach etwas fehl, nennt die Meldung die erstellte Version und den Schritt. Bricht die Verbindung
    beim PUT ab (Timeout), wird nachgeladen und die Version übernommen, falls sie gespeichert wurde.
  - `--attachments`: exportierte Dateien über `page.json.attachments` zuordnen und nur bei geändertem Inhalt
    (SHA-256) als neue Version des Original-Anhangs hochladen; auf dem Server gelöschte, unveränderte Dateien
    nicht erneut hochladen. Andere Dateien über den Namen (auch `safeFileName` des Anhangs) zuordnen und nur
    hochladen, wenn neu oder inhaltlich verschieden (gleich grosse Dateien werden zum Vergleich geladen).
    Symbolische Links, versteckte Dateien und `*.bak` werden nie hochgeladen.
- `cfh create <page.xhtml|ordner> --space K --title T [--parent SEITE] [-m MSG]`
  - Titel existiert im Space (auch in anderer Gross-/Kleinschreibung) → Abbruch (Confluence-Titel sind pro Space eindeutig).
  - schreibt `page.json` neben die Datei und den gespeicherten Storage in die Datei → danach funktioniert `push`.
- `cfh convert <datei.pdf|.xlsx|.xls|.csv> [-o page.xhtml] [--sheet NAME]…` → Storage-XHTML lokal.
  Umgeleitete Standardausgabe ist immer UTF-8 (nur ein interaktives Terminal erhält dessen Kodierung).
  Nicht-UTF-8-Dateien werden beim Lesen mit Hinweis «im Editor als UTF-8 speichern» abgelehnt.
- `cfh import <datei> --space K --title T [--parent SEITE] [--attach-source]` = convert + create (+ Original als Anhang)
- `cfh diff <ordner|page.xhtml> [--other-server]` — unified diff lokal ↔ Server (normalisiert: nur Zeilenumbrüche),
  meldet Versionsabstand; gleiche Basis-URL-Prüfung wie `push`
- `cfh validate <page.xhtml|ordner>` — Wohlgeformtheit + bekannte Storage-Regeln; Ausgabe pro Problem
  `<absoluter Pfad der page.xhtml>:<zeile>:<spalte>: FEHLER|WARNUNG: <meldung>` (Problem-Matcher der VS-Code-Aufgabe)
- `cfh workspace init [DIR] [--force]` — Arbeitsordner für VS Code/IntelliJ mit GitHub Copilot einrichten, siehe unten
- `cfh ui [--port N] [--no-browser] [--workspace DIR]` — lokale Weboberfläche (nur `127.0.0.1`) mit derselben
  Fachlogik und demselben Ordner-Layout; Spezifikation inkl. Starter und API: [UI-SPEC.md](UI-SPEC.md)

Globale Optionen: `--url`, `--profile`, `-v/--verbose` (HTTP-Methoden+Pfade, nie Header).
Exit-Codes: 0 ok, 1 Fehler, 2 Validierung, 3 Versionskonflikt, 4 Auth/Rechte.

## Arbeitsordner für VS Code/IntelliJ (`workspace.WorkspaceSetup`)
Vorlagen als Ressourcen unter `ch/confluencehelper/workspace/`, Platzhalter `{{…}}` je nach Dateityp maskiert (JSON, XML-Attribut,
in Markdown der Aufruf als Shell-Befehl: POSIX-quotiert bzw. PowerShell `& "…"`). Schreibt in den Ordner (nie in Seitenordner):
`.github/copilot-instructions.md` (Aufbau, Arbeitsregeln, Storage-Regeln aus `storage-rules.md`),
`.github/instructions/confluence-storage.instructions.md` (`applyTo: '**/page.xhtml'`), `.github/prompts/*.prompt.md`
(Frontmatter nur `description`, `agent`, `argument-hint`; keine `${file}`-Variablen), `AGENTS.md`, `.vscode/settings.json|tasks.json|extensions.json`,
`.run/*.run.xml` (IntelliJ, Typ `JarApplication`, nur wenn cfh aus einem JAR läuft), `.editorconfig`.
- `.vscode/settings.json`, `chat.tools.terminal.autoApprove`: VS Code verankert `/…/`-Regeln nicht und prüft sie pro Teilbefehl.
  Freigabe daher nur per generiertem `^…$`-Regex (`WorkspaceSetup.autoApprovePattern`): exakt der Launcher (POSIX/Windows,
  roh oder in `'…'`/`"…"`, optional PowerShell `& `), `validate|diff`, genau ein Seitenargument ohne führendes `-` und ohne
  Shell-Zeichen. `false`-Regeln (gewinnen immer) für `--url|--profile|--other-server|--force`, `CONFLUENCE_*=`/`CFH_CONFIG=`
  und schreibende Unterbefehle.
- `.vscode/tasks.json` auf Windows: mit JAR `java.exe -jar <cfh.jar> …` direkt, sonst `cfh.cmd` (cmd.exe wertet `& | % ^` aus).
  Push-Aufgaben und -Run-Konfigurationen mit `--attachments`; der echte Push zusätzlich mit `--require-message`.
- `workspace.Installation`: aus dem Code-Source-JAR `<repo>/target/cfh.jar` → `<repo>/bin/cfh`, `bin\cfh.cmd`, JAR und `java.home`;
  sonst (Klassenordner, Tests) `cfh`/`cfh.cmd` aus dem PATH und keine Run-Konfigurationen.
- `.cfh/workspace.json`: `{templateVersion, files:{pfad: sha256}}`. Pro Datei: fehlt → anlegen (fehlt eine schon erfasste Datei,
  nur bei ausdrücklichem Aufruf); gleich → nichts; Hash = erfasster Hash → aktualisieren; sonst (vom Benutzer geändert oder fremd)
  überspringen, mit `--force` ersetzen und alte Fassung als `<datei>.bak` (bzw. `.2.bak` …) sichern. Symbolische Links im Pfad → überspringen.
- Die Einfügeblock-Vorlage für Claude Desktop (`claude-desktop-intro.md` + `storage-rules.md`) steht wörtlich in
  `docs/claude-desktop.md`; `WorkspaceSetupTest` prüft Gleichheit und dass jeder `xml`-Codeblock `StorageXml.validate` besteht.

## Storage-XHTML (`storage.StorageXml`)
- `escapeText`, `escapeAttr` — für Konverter.
- `validate(String) → List<Problem>`: Fragment in Wrapper mit Namespaces `ac`, `ri`, `at`
  einbetten, HTML-Entities (`&nbsp;`, `&auml;`, … — volle HTML4-Liste) per DTD-Deklaration
  bekannt machen, XXE/externe Entities deaktivieren, mit StAX/SAX parsen; Problem = Zeile/Spalte/Text.
  Zusätzlich Warnungen: `ac:structured-macro` ohne `ac:name`, CDATA in `ac:plain-text-body` fehlt
  bei `code`-Makro, `<script>`, Inhalt (Element oder Text) neben einem `ac:layout` auf oberster Ebene.
- `attachmentRefs(String)`: `ri:attachment`-Verweise auf Anhänge dieser Seite mit Zeile/Spalte, je Dateiname einmal.
- Konverter liefern immer valides Storage-XHTML (Test: `validate(...)` leer).

## Konverter (`convert.DocumentConverter`)
`String toStorage(Path input, ConvertOptions options) throws IOException`
- **PDF** (PDFBox 3): Text pro Seite mit Positions-/Fontinfo; Überschriften über Schriftgrösse
  relativ zum häufigsten Body-Font (h1–h3; mit `--title` um eins verschoben, h2–h4), Absätze über
  Zeilenabstand zusammenfügen, Silbentrennung am Zeilenende (`-`) zusammenziehen — ausser vor
  Konjunktionen (`Ein- und Ausgaben`, `und/oder/bis/sowie/bzw./…`). Aufzählungen (`•`, `-`, `1.`, `a)`)
  → `ul`/`ol`; Nummern/Striche am Zeilenanfang nur, wenn die vorige Zeile einen Block beendet (Abstand,
  Satzende, Einzug) — `z. B.`, `d. h.`, umbrochenes `1. Januar` bleiben im Absatz; Buchstaben mit `.` nur
  als Folge `a.`, `b.`, …. Office-Bullets aus Symbol/Wingdings (Private Use Area, z.B. U+F0B7/U+F0A7) → `•`,
  übrige PUA-Zeichen entfernt. Zwei Textspalten (Zeilen mit zwei mehrwortigen Teilen, durch eine Lücke
  ≥ 1 em getrennt, an derselben x-Position) werden Spalte für Spalte gelesen; Tabellenzeilen (≥3 Teile)
  bleiben Zeilen. Kopf-/Fusszeilen entfernen: nur Zeilen im obersten/untersten Block (max. 3 Zeilen,
  äussere 15 %), der durch eine deutliche Lücke (> 1,8 × Zeilenabstand) vom Text getrennt ist, und nur
  wenn der Text wörtlich auf beiden Seiten (2 Seiten) bzw. ≥50 % der Seiten wiederkehrt, sich nur in
  Seitenzahl/Seitenanzahl unterscheidet, bei kleinerer Schrift als der Text bis auf Ziffern gleich ist,
  oder eine reine Seitenzahl ist (nicht vierstellig, z.B. Jahreszahl). Bilder optional ignorieren.
  Keine OCR — gescannte PDFs → Hinweis auf stderr, leeres Ergebnis mit Info-Makro.
- **Excel** (POI, `.xlsx`/`.xls`) und **CSV**: pro Blatt `<h2>Blattname</h2>` + `<table>`;
  erste nicht-leere Zeile = Header (`<th>`), formatierte Zellwerte via `DataFormatter`
  (Datumsformate, Prozent), Formeln → zwischengespeichertes Ergebnis, leere Randzeilen/-spalten
  trimmen, verbundene Zellen → `colspan`/`rowspan` (auf den Bereich mit Inhalt begrenzt), Hyperlinks und
  `HYPERLINK()`-Formeln (Literal oder Zellbezug als erstes Argument) → `<a>` (nur http/https/mailto/ftp),
  ausgeblendete Blätter, Zeilen und Spalten standardmässig überspringen (`includeHidden` = alle).
  Systemformate wie Excel mit der Locale: Format 14 (kurzes Datum), 22 (Datum + `HH:mm`), `[$-F800]`
  (langes Datum), `[$-F400]` (Zeit). Tabellen über 1 Mio. Zellen → Fehler statt Speicherüberlauf.
  CSV: Trennzeichen erkennen (`,` `;` Tab), UTF-8 mit/ohne BOM.
- `ConvertOptions`: `sheets` (Filter), `includeHidden`, `headerRow` (bool), `title` (optional h1).

## Sicherheit
- XML-Parser: DTD nur intern, keine externen Entities.
- Keine Secrets im Repo; `.gitignore` ignoriert `config.properties`, `*.local.*`, `export/`.
- HTTPS erwartet; `http://` nur mit Warnung.
- `bin/cfh.cmd` startet nie ein nacktes `java` (cmd.exe sucht zuerst im aktuellen Ordner): `JAVA_HOME`,
  sonst `java.exe` ausschliesslich über `PATH`.
