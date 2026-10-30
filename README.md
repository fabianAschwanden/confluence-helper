# confluence-helper (`cfh`)

Kommandozeilen-Werkzeug für **Confluence Data Center / Server** (REST API v1):

- **Neue Seiten aus Dateien:** PDF, Excel (`.xlsx`/`.xls`) oder CSV in Confluence-Storage-XHTML umwandeln und als Seite anlegen.
- **Seiten exportieren, bearbeiten, zurückspielen:** Seite als `page.xhtml` + `page.json` herunterladen, lokal (z.B. mit Claude Desktop oder GitHub Copilot in VS Code/IntelliJ) erweitern, Unterschiede ansehen und als neue Version hochladen – mit Versionsprüfung, damit keine fremden Änderungen überschrieben werden.
- **Oberfläche:** dasselbe per Klick im Browser, Start per Doppelklick, siehe [Oberfläche (UI)](#oberfläche-ui).

Austauschformat ist das **Storage-Format** von Confluence (XHTML mit `ac:`/`ri:`-Elementen). Es wird byte-genau gespeichert, Makros, Links und Bilder gehen dabei nicht verloren.

Es braucht **keine Admin-Rechte und keine App-Installation**: ein Personal Access Token und normale Lese-/Schreibrechte auf die betroffenen Seiten genügen.

## Voraussetzungen

- Java 21 oder neuer (`java -version`)
- Maven 3.9+ (nur zum Bauen)
- Confluence Data Center / Server ab 7.9 (Personal Access Tokens); ältere Versionen nur mit Benutzername/Passwort

## Bauen und installieren

```sh
git clone <repo> ~/git/confluence-helper
cd ~/git/confluence-helper
mvn -q package               # baut target/cfh.jar inkl. Tests (schneller: -DskipTests)

# bin/cfh in den PATH verlinken (Symlinks werden aufgelöst)
mkdir -p ~/bin && ln -s "$PWD/bin/cfh" ~/bin/cfh    # ~/bin muss im PATH sein
cfh --help
```

Windows: `bin\cfh.cmd` verwenden bzw. den Ordner `bin` in den `PATH` aufnehmen. Java wird über `JAVA_HOME` oder den `PATH` gefunden. Zusätzliche JVM-Optionen nur für `cfh` gehen über `JAVA_OPTS`.

## Konfiguration

### 1. Personal Access Token erstellen

In Confluence: **Profil (Avatar oben rechts) > Einstellungen > Persönliche Zugriffstokens > Token erstellen**. Name vergeben, Ablaufdatum wählen, Token kopieren (er wird nur einmal angezeigt). Dafür braucht es keine Admin-Rechte. Der Token hat dieselben Rechte wie dein Benutzer.

Hat der Admin Personal Access Tokens deaktiviert, geht ersatzweise Basic Auth mit `username`/`password`.

### 2. Konfigurationsdatei

`~/.config/confluence-helper/config.properties` (anderer Ort: Umgebungsvariable `CFH_CONFIG`):

```properties
url=https://wiki.firma.ch
token=DEIN_PERSONAL_ACCESS_TOKEN
```

```sh
mkdir -p ~/.config/confluence-helper
chmod 600 ~/.config/confluence-helper/config.properties   # nur für dich lesbar
cfh whoami                                                 # testet Verbindung und Anmeldung
```

`url` ist die Basis-URL inkl. Kontextpfad, z.B. `https://wiki.firma.ch` oder `https://firma.ch/confluence`. Ist die Datei für Gruppe/Welt lesbar, warnt `cfh`. `http://` funktioniert, aber nur mit Warnung. Tokens und Passwörter erscheinen nie in Ausgaben oder Fehlermeldungen. Unsichtbare Zeichen vom Kopieren (geschütztes Leerzeichen, Zero-Width-Space, BOM) am Anfang/Ende werden entfernt.

### Umgebungsvariablen

| Variable | Key in der Datei | Bedeutung |
| --- | --- | --- |
| `CONFLUENCE_URL` | `url` | Basis-URL |
| `CONFLUENCE_TOKEN` | `token` | Personal Access Token (bevorzugt, `Authorization: Bearer`) |
| `CONFLUENCE_USER` | `username` | Fallback Basic Auth |
| `CONFLUENCE_PASSWORD` | `password` | Fallback Basic Auth |
| `CFH_CONFIG` | – | Pfad zur Konfigurationsdatei |

Priorität: Option `--url` > Umgebungsvariable > Datei.

### Profile (mehrere Server)

```properties
url=https://wiki.firma.ch
token=TOKEN_PROD

test.url=https://wiki-test.firma.ch
test.token=TOKEN_TEST
```

```sh
cfh --profile test whoami
```

Mit `--profile` gelten nur die Keys dieses Profils, die `CONFLUENCE_*`-Variablen werden dann ignoriert (keine vermischten Zugangsdaten). `--url` überschreibt weiterhin die URL.

### Firmen-Proxy und Firmen-CA

`cfh` verwendet die Standard-Proxy-Einstellungen der JVM und deren Truststore. Beides wird über JVM-Optionen gesetzt, entweder in `JAVA_TOOL_OPTIONS` (gilt für alle Java-Programme, die JVM meldet dann «Picked up JAVA_TOOL_OPTIONS» auf stderr) oder in `JAVA_OPTS` (nur `cfh`):

```sh
# Proxy fest einstellen …
export JAVA_TOOL_OPTIONS="-Dhttps.proxyHost=proxy.firma.ch -Dhttps.proxyPort=8080 -Dhttp.nonProxyHosts=localhost|*.intern.firma.ch"
# … oder die Proxy-Einstellung des Betriebssystems übernehmen
export JAVA_TOOL_OPTIONS="-Djava.net.useSystemProxies=true"
```

Ein Proxy, der eine eigene Anmeldung verlangt (HTTP 407), wird nicht unterstützt.

Wird das Confluence-Zertifikat von einer firmeneigenen CA ausgestellt (Fehler «PKIX path building failed»), die CA in eine Kopie des Java-Truststores aufnehmen:

```sh
java_home=$(java -XshowSettings:properties -version 2>&1 | awk -F' = ' '/java.home/ {print $2}')
cp "$java_home/lib/security/cacerts" ~/.config/confluence-helper/truststore.jks
keytool -importcert -alias firma-ca -file firma-root-ca.pem \
        -keystore ~/.config/confluence-helper/truststore.jks -storepass changeit -noprompt
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=$HOME/.config/confluence-helper/truststore.jks -Djavax.net.ssl.trustStorePassword=changeit"
```

(Die Kopie von `cacerts` ist nötig, damit öffentliche CAs weiterhin gelten; `changeit` ist dessen Standard-Passwort.)

## Oberfläche (UI)
Für alle, die lieber klicken: eine kleine Weboberfläche im Browser mit derselben Fachlogik wie die CLI.

### Starten
- **macOS:** Doppelklick auf `Confluence-Helper.command` im Repo-Ordner. Blockiert macOS den ersten Start (Gatekeeper), einmal **Rechtsklick > Öffnen** wählen; fehlt das Ausführrecht: `chmod +x Confluence-Helper.command`.
- **Windows:** Doppelklick auf `Confluence-Helper.cmd`.
- **Terminal:** `bin/cfh-ui` (bzw. `bin\cfh-ui.cmd`) oder `cfh ui`.

Die Starter bauen `target/cfh.jar`, falls es fehlt und Maven installiert ist; `bin/cfh-ui` baut auch neu, wenn die Quellen neuer sind (z.B. nach `git pull`). Danach öffnet sich der Browser. Das Konsolenfenster zeigt «Läuft unter http://127.0.0.1:PORT …» und muss offen bleiben, solange die Oberfläche benutzt wird. Optionen von `cfh ui` (die Starter reichen sie durch):

- `--port N`: fester Port (Standard: zufällig)
- `--no-browser`: Browser nicht öffnen, URL aus der Konsole kopieren
- `--workspace DIR`: Arbeitsordner (Standard: zuletzt in der Oberfläche gewählt, sonst `~/Confluence`)
- dazu die globalen Optionen `--url`, `--profile`, `-v`

Beenden mit **«Beenden»** oben rechts, Ctrl+C in der Konsole oder automatisch: Nach 10 Minuten ohne Anfrage (z.B. Browser-Tab geschlossen) endet der Server von selbst.

### Bereiche
- **Neue Seite:** PDF, Excel oder CSV hineinziehen, bei Excel Blätter wählen, «Umwandeln». Vorschau und XHTML-Quelltext prüfen und bearbeiten, dann Space, Titel und übergeordnete Seite angeben und «In Confluence anlegen» (optional mit Original als Anhang) oder «Als Datei speichern».
- **Exportieren:** Seiten-URL oder ID, optional mit Anhängen und Unterseiten; Ergebnis mit «Ordner öffnen».
- **Lokale Seiten:** «In VS Code öffnen» / «In IntelliJ öffnen» öffnen den Arbeitsordner als Projekt für GitHub Copilot (siehe [Bearbeiten mit GitHub Copilot](#4-bearbeiten-mit-github-copilot-vs-code-intellij)); «Für Copilot einrichten» richtet den gewählten Arbeitsordner ein oder bringt ihn auf den neuesten Stand (nötig für andere Ordner als `~/Confluence`). Darunter alle Seiten im Arbeitsordner mit Status (lokal geändert, Server neuer). Pro Seite: Diff, Editor (Prüfen, Speichern), «Ordner öffnen» (z.B. für Claude Desktop) und «Zurückschreiben» mit Kommentar, Probelauf und Anhängen. Bei einem Versionskonflikt holt «Server-Fassung holen» den Serverstand, die eigene Fassung bleibt als `page.xhtml.bak`.
- **Verbindung:** URL, Profil, Token bzw. Benutzer/Passwort; «Verbindung testen» prüft ohne zu speichern, «Speichern» schreibt die [Konfigurationsdatei](#2-konfigurationsdatei) (Rechte 600).

### Arbeitsordner
Standard ist `~/Confluence`. Er ist in der Kopfzeile änderbar und wird in `~/.config/confluence-helper/ui.properties` gespeichert. Exporte und neu angelegte Seiten landen dort in `<ID>-<titel>/` mit `page.xhtml` + `page.json`, also im selben Layout wie bei der CLI: Ordner aus der UI funktionieren mit `cfh diff`/`cfh push` und umgekehrt. Den Standardordner `~/Confluence` richtet die UI beim Start für VS Code/IntelliJ mit GitHub Copilot ein (wie `cfh workspace init`; von dir geänderte Dateien bleiben unverändert), andere Ordner nur auf Knopfdruck.

### Sicherheit
Der Server lauscht nur auf `127.0.0.1` und ist von anderen Rechnern nicht erreichbar. Jeder Start erzeugt ein zufälliges Token, ohne das der Server keine API-Anfrage annimmt (auch nicht von anderen Webseiten im selben Browser); Token und Passwörter für Confluence gehen nie an den Browser zurück. Details: [docs/UI-SPEC.md](docs/UI-SPEC.md).

### Web-UI testen
Automatisch laufen `UiServerTest` und `ConfigFileTest` mit `mvn verify`. Der Browser-Test `src/test/ui-e2e/ui-e2e.mjs` ist nicht Teil davon: Er startet `UiDevServer` mit einem Fake-Confluence, klickt alle Abläufe in headless Chromium durch (inkl. Versionskonflikt, hell/dunkel, Handy-Breite) und legt Screenshots in `$SHOTS` ab (Standard `$TMPDIR/cfh-ui-e2e`). Das Skript kompiliert die Testklassen selbst (braucht `mvn` und Node.js ab 20.15). Optional sind `PORT`, `BUILD_DIR` und `CHANNEL=chrome`.
```sh
cd "$(mktemp -d)" && npm i playwright && npx playwright install chromium
node ~/git/confluence-helper/src/test/ui-e2e/ui-e2e.mjs
```

## Benötigte Berechtigungen

Nur normale Benutzerrechte, keine Admin- oder Space-Admin-Rechte:

| Aktion | Recht in Confluence |
| --- | --- |
| `whoami`, `export`, `diff` | Space/Seite **anzeigen** |
| `create`, `import` | Seiten im Space **hinzufügen** (bei `--parent` zusätzlich Leserecht auf die übergeordnete Seite) |
| `push` | Seite **bearbeiten** (Seitenbeschränkungen beachten) |
| `export --attachments` | anzeigen (Anhänge lesen) |
| `push --attachments`, `import --attach-source` | zusätzlich **Anhänge hinzufügen** |

`convert`, `validate` und `workspace init` arbeiten rein lokal und brauchen keine Verbindung.

## Befehle

Globale Optionen (bei jedem Befehl): `--url=URL`, `--profile=NAME`, `-v/--verbose` (zeigt HTTP-Methoden und Pfade, nie Header oder Token), `-h/--help`, `-V/--version`.

Eine **Seite** (`SEITE`) kann als ID oder als Confluence-URL angegeben werden, z.B. `123456`, `…/pages/viewpage.action?pageId=123456`, `…/display/TEAM/Meine+Seite` oder `…/spaces/TEAM/pages/123456/…`.

### `cfh whoami`
Verbindung und angemeldeten Benutzer prüfen.
```sh
cfh whoami
```

### `cfh export SEITE [-o DIR] [--attachments] [--children] [--depth N] [--force]`
Lädt die Seite nach `DIR/<ID>-<titel>/` (Standard: aktueller Ordner):
```
<ID>-<titel>/
  page.xhtml       Storage-Format, unverändert vom Server
  page.json        Metadaten (ID, Titel, Space, Version, Basis-URL, Hash, Anhänge)
  attachments/     nur mit --attachments
  page.xhtml.bak   nur nach --force über lokale Änderungen
```
```sh
cfh export https://wiki.firma.ch/display/TEAM/Projekthandbuch -o ~/confluence
cfh export 123456 --attachments
cfh export 123456 --children --depth 2 -o ~/confluence     # Unterseiten als verschachtelte Ordner
```
- `--children`: Unterseiten mitexportieren; `--depth N` begrenzt die Tiefe (impliziert `--children`).
- Ein erneuter Export überschreibt `page.xhtml` nur, wenn sie keine lokalen Änderungen enthält, sonst Abbruch. `--force` überschreibt trotzdem und sichert die lokale Fassung als `page.xhtml.bak`.
- Mit `--attachments` werden auf dem Server gelöschte, lokal unveränderte Dateien entfernt; eigene neue Dateien bleiben liegen. Lokal geänderte Anhänge blockieren den Export ohne `--force`.

### `cfh diff ORDNER|page.xhtml [--other-server]`
Unified Diff Server ↔ lokal und Versionsabstand.
```sh
cfh diff ~/confluence/123456-projekthandbuch
```

### `cfh push ORDNER|page.xhtml [-m MSG] [--title T] [--dry-run] [--force] [--attachments] [--other-server]`
Lädt die lokale Fassung als neue Version hoch.
```sh
cfh push ~/confluence/123456-projekthandbuch --dry-run
cfh push ~/confluence/123456-projekthandbuch -m "Kapitel Betrieb ergänzt"
cfh push ~/confluence/123456-projekthandbuch --title "Projekthandbuch 2026"
cfh push ~/confluence/123456-projekthandbuch --attachments   # neue/geänderte Dateien aus attachments/
```
- Validiert `page.xhtml` vorher (Fehler → Abbruch, Exit 2). Unveränderter Inhalt und Titel → keine neue Version.
- Ist die Seite auf dem Server inzwischen neuer als in `page.json` → Abbruch (Exit 3), siehe [Versionskonflikt](#3-versionskonflikt).
- `--force`: trotz neuerer Server-Version überschreiben (bzw. trotz anderem Space in `page.json`).
- Gehört `page.json` zu einer anderen Basis-URL → Abbruch. Nur `--other-server` schreibt dann auf den aktuell konfigurierten Server und verknüpft den Ordner neu.
- Nach dem Push enthält `page.xhtml` den Storage, den Confluence tatsächlich gespeichert hat, und `page.json` die neue Version.
- `--attachments`: lädt nur neue oder inhaltlich geänderte Dateien hoch (geänderte als neue Version des bestehenden Anhangs). Versteckte Dateien, `*.bak` und symbolische Links werden nie hochgeladen.

### `cfh create page.xhtml|ORDNER --space K --title T [--parent SEITE]`
Legt eine neue Seite aus einer lokalen Storage-Datei an und schreibt `page.json` daneben. Danach funktionieren `diff` und `push` für diese Datei.
```sh
cfh create entwurf/page.xhtml --space TEAM --title "Release-Notes 3.2" --parent 123456
```
- Existiert der Titel im Space schon (auch in anderer Gross-/Kleinschreibung) → Abbruch; Titel sind pro Space eindeutig.
- Liegt bereits eine `page.json` mit Seiten-ID neben der Datei → Abbruch (dann `push` verwenden).
- `-m` wird akzeptiert, aber derzeit ignoriert.

### `cfh convert DATEI [-o page.xhtml] [--title T] [--sheet NAME]… [--include-hidden] [--no-header]`
Wandelt PDF, Excel oder CSV lokal in Storage-XHTML um (ohne Verbindung). Ohne `-o` geht das Ergebnis auf stdout (umgeleitet immer UTF-8).
```sh
cfh convert bericht.pdf -o entwurf/page.xhtml --title "Jahresbericht 2025"
cfh convert liste.xlsx --sheet Kunden --sheet Projekte -o entwurf/page.xhtml
cfh convert export.csv --no-header > entwurf/page.xhtml
```
- `--title`: setzt eine `<h1>` an den Anfang (PDF-Überschriften rücken eine Stufe nach unten).
- `--sheet`: nur diese Blätter (mehrfach möglich); `--include-hidden`: auch ausgeblendete Blätter, Zeilen und Spalten; `--no-header`: erste Zeile nicht als Tabellenkopf.
- PDF: Überschriften (aus Schriftgrösse), Absätze, Aufzählungen, zweispaltiger Text; Silbentrennung wird zusammengezogen, wiederkehrende Kopf-/Fusszeilen und Seitenzahlen entfernt.
- Excel/CSV: pro Blatt eine `<h2>` mit Blattname und eine Tabelle; formatierte Werte (Datum, Prozent), Formelergebnisse, verbundene Zellen, Hyperlinks. CSV-Trennzeichen (`,` `;` Tab) werden erkannt.

### `cfh import DATEI --space K --title T [--parent SEITE] [--attach-source] [-m MSG] [-o DIR] [--sheet NAME]… [--include-hidden] [--no-header]`
`convert` + `create` in einem Schritt. Die lokale Kopie landet in `DIR/<ID>-<titel>/` (Standard: aktueller Ordner) und kann danach mit `push` weiterbearbeitet werden.
```sh
cfh import offerte.pdf --space TEAM --title "Offerte Muster AG" --parent 123456 --attach-source
cfh import inventar.xlsx --space IT --title "Hardware-Inventar" --sheet Laptops -o ~/confluence
```
- `--attach-source`: Originaldatei als Anhang an die neue Seite hängen; `-m` ist der Anhang-Kommentar.

### `cfh validate page.xhtml|ORDNER`
Prüft Wohlgeformtheit und bekannte Storage-Regeln (z.B. Makro ohne `ac:name`, `code`-Makro ohne CDATA, `<ri:attachment>` ohne Dateiname, `<script>`). HTML-Entities wie `&nbsp;` sind erlaubt. Probleme erscheinen als `<absoluter Pfad>:<zeile>:<spalte>: FEHLER|WARNUNG: <meldung>` (anklickbar in Terminals und IDEs).
```sh
cfh validate ~/confluence/123456-projekthandbuch
```

### `cfh workspace init [DIR] [--force]`
Macht den Ordner (Standard: aktueller Ordner) zu einem Projekt für VS Code und IntelliJ mit GitHub Copilot: Anweisungen und Prompts für Copilot, `AGENTS.md`, VS-Code-Einstellungen und -Aufgaben, IntelliJ-Run-Konfigurationen, `.editorconfig`. Seitenordner bleiben unberührt. Erneut ausführen aktualisiert unveränderte Dateien; von dir geänderte bleiben, `--force` ersetzt sie und sichert sie als `.bak`. Details: [docs/copilot.md](docs/copilot.md).
```sh
cfh workspace init ~/confluence && code ~/confluence
```
### `cfh ui [--port N] [--no-browser] [--workspace DIR]`
Startet die Weboberfläche im Browser (nur auf `127.0.0.1`), siehe [Oberfläche (UI)](#oberfläche-ui).
```sh
cfh ui
cfh ui --workspace ~/confluence --no-browser    # URL aus der Konsole selbst öffnen
```

## Workflows

### 1. PDF oder Excel → neue Seite

Direkt:
```sh
cfh import preisliste.xlsx --space VERKAUF --title "Preisliste 2026" --parent https://wiki.firma.ch/display/VERKAUF/Preise
```

Mit Überarbeitung vor dem Anlegen (z.B. Einleitung ergänzen, Tabellen aufräumen, mit Claude Desktop oder GitHub Copilot):
```sh
mkdir -p entwurf
cfh convert bericht.pdf -o entwurf/page.xhtml
# entwurf/page.xhtml bearbeiten (siehe docs/claude-desktop.md bzw. docs/copilot.md)
cfh validate entwurf/page.xhtml
cfh create entwurf/page.xhtml --space TEAM --title "Jahresbericht 2025"
# spätere Änderungen: cfh push entwurf
```

### 2. Export → Bearbeitung in Claude Desktop → Diff → Push

```sh
cfh export https://wiki.firma.ch/display/TEAM/Projekthandbuch -o ~/confluence
# ~/confluence/123456-projekthandbuch/page.xhtml in Claude Desktop erweitern lassen,
# Ergebnis vollständig zurück in page.xhtml speichern (UTF-8)
cfh validate ~/confluence/123456-projekthandbuch
cfh diff     ~/confluence/123456-projekthandbuch
cfh push     ~/confluence/123456-projekthandbuch --dry-run
cfh push     ~/confluence/123456-projekthandbuch -m "Abschnitt Betrieb ergänzt"
```

Anleitung und fertiger Anweisungsblock für ein Claude-Desktop-Projekt: [docs/claude-desktop.md](docs/claude-desktop.md). `page.json` nie bearbeiten. Mit GitHub Copilot im Editor: [Workflow 4](#4-bearbeiten-mit-github-copilot-vs-code-intellij).

### 3. Versionskonflikt

Hat jemand die Seite seit deinem Export geändert, bricht `push` mit Exit-Code 3 ab:

```
Fehler: Versionskonflikt bei Seite 123456: lokal Version 7, auf dem Server Version 8. …
```

Vorgehen:

1. `cfh diff ORDNER` zeigt, was auf dem Server anders ist (Zeilen mit `-` stammen vom Server, `+` von dir).
2. Server-Version in einen **anderen** Ordner holen: `cfh export 123456 -o ~/confluence/server`.
3. Deine Änderungen in die neue Server-Fassung übernehmen (z.B. Claude Desktop oder Copilot beide Dateien geben und zusammenführen lassen), dort speichern und von dort pushen: `cfh push ~/confluence/server/123456-projekthandbuch`.
4. Nur wenn die fremden Änderungen bewusst verworfen werden sollen: `cfh push ORDNER --force`.

`page.xhtml` nicht einfach durch die Server-Fassung ersetzen, sonst gehen deine Änderungen verloren. Ein erneuter `export` in denselben Ordner bricht bei lokalen Änderungen ab (mit `--force` landet die lokale Fassung in `page.xhtml.bak`).

### 4. Bearbeiten mit GitHub Copilot (VS Code, IntelliJ)
```sh
cfh export https://wiki.firma.ch/display/TEAM/Projekthandbuch -o ~/confluence
cfh workspace init ~/confluence      # einmalig; die UI macht das für ~/Confluence selbst
code ~/confluence                    # oder IntelliJ: Datei > Öffnen …
```
`page.xhtml` öffnen und Copilot Chat im Agent-Modus beauftragen, z.B. mit `/seite-erweitern`. Copilot prüft mit `cfh validate`, zeigt `cfh diff` und schreibt nie ohne deine Bestätigung zurück (`push` fragt immer nach). Zurückschreiben über die Aufgabe bzw. Run-Konfiguration «cfh: Zurückschreiben (aktuelle Seite)» oder `cfh push`. Anleitung: [docs/copilot.md](docs/copilot.md).

## Exit-Codes

| Code | Bedeutung |
| --- | --- |
| 0 | OK |
| 1 | Allgemeiner Fehler (Datei/Seite nicht gefunden, Konfiguration, Netzwerk, Titel existiert schon, …) |
| 2 | Validierung: Storage-XHTML fehlerhaft (`validate`, `convert`, Abbruch von `push`/`create`/`import`) |
| 3 | Versionskonflikt beim `push` |
| 4 | Anmeldung/Rechte (HTTP 401/403): Token oder Rechte prüfen, `cfh whoami` |

## Einschränkungen

- **Keine OCR:** gescannte PDFs ohne Textebene ergeben nur einen Hinweis (Info-Makro), Bilder aus PDFs werden nicht übernommen.
- **PDF-Struktur ist heuristisch:** Überschriften, Listen und Spalten werden aus Schriftgrössen und Positionen abgeleitet; komplexe Layouts und Tabellen im PDF brauchen Nacharbeit.
- **Makros bleiben Storage-XML:** `ac:structured-macro`, Links, Bilder usw. erscheinen in `page.xhtml` als XML und müssen beim Bearbeiten unverändert bleiben. Es gibt keine Umwandlung nach Markdown.
- **Confluence normalisiert beim Speichern** (z.B. `<br/>` → `<br />`, ergänzt `ac:macro-id`). `cfh` übernimmt nach `push`/`create` den gespeicherten Storage in `page.xhtml`; ein `diff` direkt danach zeigt deshalb keine Unterschiede, auch wenn die Datei leicht anders aussieht als vor dem Push.
- **Excel/CSV:** max. 1 Mio. Zellen pro Tabelle; Diagramme, Bilder und Zellformatierung (Farben) werden nicht übernommen.
- Nur Confluence **Data Center / Server** (REST API v1), nicht Confluence Cloud.
- `create -m` wird derzeit ignoriert.
