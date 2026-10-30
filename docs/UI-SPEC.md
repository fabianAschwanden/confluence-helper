# confluence-helper — Web-UI (Spezifikation)

Kleine lokale Oberfläche neben der CLI. Gleiche Fachlogik (`sync/*`, `convert/*`, `storage/*`),
kein zweiter Weg zu Confluence. Keine zusätzlichen Maven-Dependencies: Server mit
`com.sun.net.httpserver`, Frontend als statisches HTML/CSS/Vanilla-JS aus dem JAR
(kein CDN, kein Build-Schritt — funktioniert offline und hinter Firmen-Proxy).

## Start
- `cfh ui [--port N] [--no-browser] [--workspace DIR]` (+ globale `--url`, `--profile`, `-v`).
- Server bindet **nur** an `127.0.0.1`, Port zufällig (oder `--port`).
- Pro Start ein Zufallstoken (32 Byte, Base64url). Browser öffnet `http://127.0.0.1:PORT/#t=TOKEN`
  (Fragment → nie in Server-Logs/Referer). Das JS liest das Token, legt es in `sessionStorage`
  und entfernt es aus der Adresszeile. Die Konsole druckt die URL (falls kein Browser öffnet).
- Browser öffnen: macOS direkt `open` (AWT würde ein Dock-Symbol erzeugen); sonst `Desktop.browse`, Fallback
  `rundll32 url.dll,FileProtocolHandler` (Windows) / `xdg-open`. Ordner öffnen: macOS nur `open -R` (im Finder
  zeigen, startet nie etwas; bei Seitenordnern `page.xhtml` markieren), Windows `explorer`, sonst `Desktop.open`/`xdg-open`.
- Browser/Ordner/IDE starten nur über `Launcher` (in Tests ersetzt, nie echte Programme). IDE öffnen (`Launcher.openIde`):
  Argumentlisten mit `ProcessBuilder`, nie über eine Shell. macOS: `code` aus dem PATH bzw. die CLI im App-Bundle
  (`Visual Studio Code.app/Contents/Resources/app/bin/code`), sonst `open -a <App> <ordner>`; IntelliJ: `idea` aus dem PATH, sonst
  `open -na "<App>.app" --args <ordner>` (gesucht: «IntelliJ IDEA», «… Ultimate», «… CE», «… Community Edition» in `/Applications`
  und `~/Applications`). Windows: immer die `.exe` (`Code.exe` unter `%LOCALAPPDATA%\Programs`/`%ProgramFiles%` bzw. neben einem
  `code.cmd` im PATH; `idea64.exe` aus dem PATH oder neueste Installation unter `%ProgramFiles%\JetBrains`/`%LOCALAPPDATA%\Programs`),
  nie eine `.cmd`, die cmd.exe auswerten würde. Linux: `code` bzw. `idea`, `idea.sh`, `intellij-idea-ultimate|community` aus dem PATH.
  Relative PATH-Einträge werden ignoriert. VS Code bekommt bei einer Seite zusätzlich deren `page.xhtml`, IntelliJ nur den Ordner.
- Ist der in `ui.properties` gespeicherte Arbeitsordner nicht verfügbar (z.B. Netzlaufwerk nicht verbunden), startet die
  UI mit `~/Confluence` und meldet das in `workspaceWarning`; ein mit `--workspace` angegebener, unbrauchbarer Ordner
  bricht den Start mit Pfad und Hinweis auf `--workspace` ab.
- Beenden: Button «Beenden» (`POST /api/shutdown`), Ctrl+C, oder automatisch nach 10 Minuten ohne
  Anfrage (Frontend sendet alle 30 s `GET /api/ping`; Tab zu → Server endet von selbst).

## Sicherheit (lokaler Server)
- Jede `/api/*`-Anfrage braucht Header `X-CFH-Token` = Token (konstantzeitiger Vergleich), sonst 401 mit
  `kind:"session"` (≠ `auth`, das Anmeldeprobleme bei Confluence meldet).
  Custom-Header ⇒ keine CSRF über Formulare/`fetch` fremder Seiten (kein CORS, keine Preflight-Antwort).
- `Host`-Header muss `127.0.0.1:PORT` oder `localhost:PORT` sein, sonst 403 (DNS-Rebinding); ein vorhandener
  `Origin`-Header muss `http://127.0.0.1:PORT` bzw. `http://localhost:PORT` sein. Antwort `kind:"session"`.
- Statische Dateien ohne Token, aber mit `Content-Security-Policy: default-src 'self'; frame-src 'self' data: blob:;
  img-src 'self' data:; style-src 'self'; script-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'`,
  `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store`.
- Dateipfade aus Anfragen (`dir`) müssen nach `toRealPath` innerhalb des Arbeitsordners liegen.
- `POST /api/open` verweigert Ordner, die macOS als Paket behandelt (Endung `.app`, `.pkg`, `.bundle` …, oder
  `Contents/Info.plist` bzw. `Contents/MacOS` vorhanden) – der Arbeitsordner kann ein geteilter Ordner sein.
- Gespeicherter Token bzw. gespeichertes Passwort werden nur weiterverwendet (leeres Feld), solange die URL auf
  denselben Server zeigt (Schema, Host, Port wie die gespeicherte URL des Profils); sonst `config`-Fehler
  «Bei geänderter Adresse bitte … neu eingeben». Gilt für `POST /api/config` und `POST /api/whoami` mit Formularwerten.
- Uploads: max. 100 MB, in privatem Temp-Ordner (Rechte 700), beim Beenden gelöscht; Dateiname nur zur Anzeige
  und Endungserkennung (sanitized).
- Token/Passwort werden nie an den Browser zurückgegeben (nur «gesetzt: ja/nein»).
- Vorschau von Storage-XHTML im Browser nur in `<iframe sandbox>` (ohne `allow-scripts`) via `srcdoc`.

## Arbeitsordner
Standard `~/Confluence` (anlegen bei Bedarf), änderbar in der UI; gespeichert in
`~/.config/confluence-helper/ui.properties` (`workspace=`). Exporte und mit der UI angelegte Seiten landen
dort in `<id>-<slug>/` (gleiches Layout wie CLI) → CLI und UI sind austauschbar.

Einrichtung für VS Code/IntelliJ mit GitHub Copilot (`WorkspaceSetup`, wie `cfh workspace init`, siehe SPEC.md): beim Start und nach
einem Wechsel des Arbeitsordners automatisch, aber nur für `~/Confluence` (gehört der UI) oder einen Ordner mit `.cfh/workspace.json`
(früher eingerichtet; hält z.B. den cfh-Pfad aktuell). Dabei werden vom Benutzer geänderte Dateien nicht ersetzt und gelöschte nicht neu
angelegt; Fehler nur als Warnung in der Konsole. Andere Ordner nur über «Für Copilot einrichten» bzw. «In … öffnen». Die CLI richtet nie
von sich aus ein. Ordner mit `.` am Anfang (`.github`, `.vscode`, `.run`, `.cfh`) zählen nicht als Seiten.

## API (JSON, UTF-8)
Fehlerantwort: HTTP 4xx/5xx mit `{"error":"<deutsche Meldung>","kind":"config|auth|notfound|conflict|validation|input|other|session","problems":[…]?,"reason":…?}`.
Meldungen nennen Bedienelemente der UI, keine CLI-Befehle/-Optionen. `reason` (optional) für Fälle mit eigener UI-Hilfe:
`localChanges` (Export über lokale Änderungen; dazu `dir`), `otherServer` (Seitenordner gehört zu anderer Basis-URL; dazu
`serverUrl` und – falls ein Profil diese URL hat – `switchProfile`: Profilname, `""` = Standardprofil),
`spaceMismatch` (Push: Seite in anderem Space), `titleExists` (Titel im Space vergeben, bei Anlegen und Umbenennen),
`changedOnDisk` (`PUT /api/page`: Datei inzwischen geändert, 409).
Status: config 400, auth 401/403 (wie Confluence), notfound 404, conflict 409, validation 422 (mit `problems`),
input 400 (413 bei zu grosser Datei/Anfrage), session 401/403 (lokale Prüfung), other 400 (fachlicher Fehler, z.B.
Titel existiert schon, lokale Änderungen würden überschrieben, Confluence lehnt mit 4xx ab), 502 (Confluence nicht erreichbar oder
meldet 5xx) bzw. 500 (unerwartet; nie mit Stacktrace, nur diese werden in der Konsole protokolliert).
`kind` entspricht den CLI-Exit-Codes (config/other=1, validation=2, conflict=3, auth=4).
Lange Operationen laufen synchron; Fortschrittsmeldungen kommen als `log: [string]` in der Antwort.
Schreibende Confluence-Operationen sind serialisiert (ein Lock).

| Methode/Pfad | Request | Response |
|---|---|---|
| `GET /api/ping` | – | `{ok:true}` |
| `GET /api/status` | – | `{version, configured, baseUrl, profile, profiles:[…], auth:"token"\|"basic"\|null, configFile, workspace, workspaceWarning, copilotSetup, username, envOverride, configError, connections:[{profile, url, auth, username}]}` — `envOverride`: `CONFLUENCE_*`-Variablen übersteuern die Datei (nur ohne Profil); `configError`: Grund für `configured:false`; `connections`: gespeicherte Einstellungen je Profil (`profile:null` = Standard), nie Geheimnisse |
| `POST /api/config` | `{profile?, url, token?, username?, password?}` | Status. Schreibt Konfigurationsdatei (Rechte 600, bestehende andere Keys/Profile/Kommentare bleiben); leeres `token`/`password` = unverändert lassen. Mit `username` → Basic-Auth (Token des Profils wird entfernt), sonst Token (Benutzer/Passwort werden entfernt). Das Profil wird zum aktiven Profil; ein `--url` vom Start gilt danach nicht mehr |
| `POST /api/profile` | `{profile?}` | Status. Wechselt das aktive Profil, ohne etwas zu schreiben (leer = Standardprofil; unbekannt → 400) |
| `POST /api/whoami` | `{}` oder `{profile?, url, token?, username?, password?}` | `{user}` — ohne `url` die aktive Verbindung; mit `url` werden die Formularwerte getestet, **ohne** sie zu speichern (gleiche Regeln wie `POST /api/config`) |
| `POST /api/workspace` | `{path}` | Status |
| `POST /api/upload?name=<datei>` | Rohbytes | `{uploadId, name, kind:"pdf"\|"excel"\|"csv", size, sheets:[{name,hidden}]?}` |
| `POST /api/convert` | `{uploadId, sheets?, includeHidden?, headerRow?, title?}` | `{storage, problems}` |
| `POST /api/create` | `{storage, spaceKey, title, parent?, attachUploadId?, message?}` | `{id, title, version, webUrl, dir, problems, log, attachmentError?}` — legt Ordner im Arbeitsordner an (`Creator.createInto`); scheitert nur der Anhang, ist die Antwort trotzdem 200 mit `attachmentError` |
| `POST /api/export` | `{page, attachments?, children?, depth?, force?}` | `{results:[{id,title,version,dir,attachments,level}], log}` — `depth` ohne `children` impliziert Unterseiten (wie CLI); eine URL eines anderen Servers wird abgelehnt (400 `input`) |
| `POST /api/refresh` | `{dir}` | `{id, title, version, dir, backup:"page.xhtml.bak"\|null}` — holt die Server-Fassung in genau diesen Ordner (wie `export --force`, ohne Anhänge); lokale Änderungen landen in `page.xhtml.bak` |
| `GET /api/local` | – | `{pages:[{dir, id, title, spaceKey, version, localChanged, webUrl}]}` (rekursiv, `dir` relativ zum Arbeitsordner, `/` als Trenner; ungültige page.json → `{dir, error}`) |
| `POST /api/remote` | `{dir}` | `{localVersion, serverVersion, localChanged, serverChanged}` |
| `POST /api/diff` | `{dir}` | `{diff, added, removed, serverVersion, localVersion, serverTitle, localTitle}` |
| `GET /api/page?dir=` | – | `{storage, hash, meta}` — `hash`: SHA-256 (hex) der Datei |
| `PUT /api/page` | `{dir, storage, hash?, force?}` | `{problems, hash}` — speichert `page.xhtml` (UTF-8, byte-genau wie gesendet); mit `hash` nur, wenn die Datei seit dem Laden unverändert ist, sonst 409 `reason:"changedOnDisk"` (`force:true` überschreibt) |
| `POST /api/validate` | `{storage}` | `{problems}` |
| `POST /api/push` | `{dir, message?, dryRun?, force?, attachments?, title?}` | `{status:"updated"\|"unchanged"\|"dry-run", version, serverVersion, webUrl, added, removed, problems, diff?, log}` (`diff` nur beim Probelauf; `problems` enthält auch Warnungen zu verwendeten Anhängen, die nach dem Push auf dem Server fehlen würden) |
| `POST /api/open` | `{dir}` | `{path}` — öffnet Ordner im Finder/Explorer; leeres `dir` = Arbeitsordner |
| `POST /api/workspace/init` | `{force?}` | `{dir, files:[{path, status:"created"\|"updated"\|"unchanged"\|"replaced"\|"skipped", note}], skipped, log}` — richtet den Arbeitsordner für VS Code/IntelliJ ein; legt gelöschte Dateien wieder an; `force` ersetzt geänderte Dateien (alte Fassung als `.bak`) |
| `POST /api/open-ide` | `{ide:"vscode"\|"intellij", dir?}` | `{path, file, ide}` — öffnet immer den Arbeitsordner als Projekt (vorher einrichten, falls noch nicht geschehen); `dir` nur ein Seitenordner im Arbeitsordner (dessen `page.xhtml` öffnet VS Code mit). IDE nicht gefunden → 400 `other` mit Installationshinweis |
| `POST /api/shutdown` | – | `{}` |

`problems`: `[{severity:"ERROR"|"WARNING", line, column, message}]`.

## Frontend (`src/main/resources/ch/confluencehelper/ui/static/`)
`index.html`, `app.css`, `app.js` — Deutsch (Schweiz, ohne ß), hell/dunkel nach System, ohne Frameworks.
Kopfzeile: Verbindungsstatus (URL, Benutzer), Arbeitsordner, «Beenden». Vier Bereiche (Tabs):

1. **Neue Seite** — Datei (PDF/Excel/CSV) per Drag & Drop oder Auswahl → bei Excel Blattauswahl → «Umwandeln» →
   Vorschau (gerendert im Sandbox-iframe) + XHTML-Quelltext (bearbeitbar, mit «Prüfen») → Space-Key, Titel,
   übergeordnete Seite (URL/ID), «Original als Anhang» → «In Confluence anlegen» → Link zur Seite.
   Alternativ «Als Datei speichern» (Download der XHTML).
2. **Exportieren** — Seiten-URL/ID, Optionen Anhänge/Unterseiten (Tiefe)/Überschreiben → Ergebnisliste mit
   «Ordner öffnen».
3. **Lokale Seiten** — Kasten «Mit GitHub Copilot bearbeiten»: «In VS Code öffnen», «In IntelliJ öffnen» (`POST /api/open-ide`),
   «Für Copilot einrichten» (`POST /api/workspace/init`; bei übersprungenen Dateien Hinweis und «Trotzdem ersetzen» mit `force`). Darunter Tabelle aller Seiten im Arbeitsordner mit Status (lokal geändert, Server neuer) →
   pro Seite: Diff anzeigen, im Editor bearbeiten (Textarea + Prüfen + Speichern), «Ordner öffnen»
   (zum Bearbeiten in Claude Desktop), «Zurückschreiben» (Kommentar, Probelauf, Anhänge, Erzwingen nur nach
   Bestätigung bei Konflikt).
4. **Verbindung** — URL, Profil, Token (Passwortfeld) bzw. Benutzer/Passwort, «Speichern», «Verbindung testen»;
   Hinweis wo man das Personal Access Token erstellt. Wechsel im Feld «Profil» lädt URL/Anmeldeart/Benutzer dieses
   Profils (`connections`); geänderte URL → Hinweis «Token neu eingeben». «Verbindung testen» mit geänderten Werten
   testet nur (`POST /api/whoami` mit Formularwerten), gespeichert wird erst mit «Speichern».

Lokale Seiten: Editor sendet `hash` mit; ist die Datei inzwischen geändert (z.B. von Claude Desktop), bietet er «Neu laden»
oder «Trotzdem überschreiben». Zeilenenden (CRLF) einer Datei bleiben beim Speichern erhalten. Schlägt «Serverstand prüfen»
fehl, steht der Grund sichtbar in der Zeile. Beim Versionskonflikt empfiehlt der Dialog «Server-Fassung holen»
(`POST /api/refresh`, eigene Fassung als `page.xhtml.bak`), dann Änderungen übernehmen und erneut zurückschreiben.

Fehler werden verständlich angezeigt (Meldung aus `error`, Problemliste mit Zeilennummern).

## Starter
- `bin/cfh-ui` (sh) und `bin/cfh-ui.cmd` (Windows): baut das JAR, falls es fehlt und Maven vorhanden ist,
  dann `cfh ui`. `bin/cfh-ui` baut zusätzlich neu, wenn `pom.xml` oder `src/main` neuer als das JAR sind
  (z.B. nach `git pull`); schlägt das fehl, startet es mit dem bestehenden JAR.
- Doppelklick-Starter im Repo-Root: `Confluence-Helper.command` (macOS, ausführbar) und
  `Confluence-Helper.cmd` (Windows) → rufen `bin/cfh-ui` bzw. `bin\cfh-ui.cmd` auf.
  Konsolenfenster zeigt «Läuft unter http://127.0.0.1:PORT – Fenster schliessen oder ‹Beenden› im Browser».

## Tests
- Automatisch (`mvn verify`): `UiServerTest`, `ConfigFileTest` (JUnit, gegen `FakeConfluenceServer`); IDE-Start über einen
  Test-`Launcher`, Befehlslisten von `SystemLauncher.ideCommand` mit einem Datei-Probe statt echter Programme.
- Manuell: `UiDevServer [port] [--no-open]` (Testklassen) startet die UI gegen ein Fake-Confluence mit Demo-Space
  `DEMO` und druckt `UI_URL=…`; `--no-open` protokolliert «Ordner öffnen» und «In … öffnen» nur (`OPEN <pfad>`, `IDE <VSCODE|INTELLIJ> <ordner> [datei]`). Über stdin simuliert
  `edit <id>` eine fremde Änderung (neue Version), `version <id>` druckt `VERSION <id> <n>`.
- Browser: `src/test/ui-e2e/ui-e2e.mjs` (Playwright, nicht Teil von `mvn verify`, Aufruf siehe README) klickt alle
  Abläufe durch, inkl. Versionskonflikt, hell/dunkel und 390 px Breite, und meldet Konsolen-/CSP-Fehler.
