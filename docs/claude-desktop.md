# Confluence-Seiten mit Claude Desktop bearbeiten

`cfh export` legt jede Seite als `page.xhtml` im **Confluence-Storage-Format** ab: XHTML mit Confluence-eigenen Elementen (`ac:` für Makros und Links, `ri:` für Verweise auf Seiten, Anhänge und Benutzer). Claude kann diese Datei direkt erweitern, wenn es die Regeln unten einhält. Danach spielt `cfh push` die Änderung als neue Version zurück.

Alternative im Editor: GitHub Copilot in VS Code oder IntelliJ bearbeitet die Dateien direkt im Arbeitsordner und prüft sie selbst, siehe [copilot.md](copilot.md).

## Ablauf

1. **Exportieren**
   ```sh
   cfh export https://wiki.firma.ch/display/TEAM/Projekthandbuch -o ~/confluence
   ```
2. **Claude-Desktop-Projekt einrichten (einmalig):** In Claude Desktop ein Projekt anlegen, z.B. «Confluence-Seiten», und den [Anweisungsblock](#anweisungsblock-für-das-projekt) unten unter **Projektanweisungen** einfügen.
3. **Seite an Claude geben:** Im Projekt einen Chat starten und den Inhalt von `page.xhtml` anhängen oder einfügen, dazu den Auftrag, z.B. «Ergänze ein Kapitel ‹Betrieb› mit einer Tabelle der Ansprechpersonen und einem Warnhinweis zum Wartungsfenster.» Weitere Quellen (PDF, Excel, Notizen) können zusätzlich angehängt werden.
   - Nimmt Claude Desktop die Endung `.xhtml` nicht an: Inhalt in den Chat einfügen oder eine Kopie als `.txt` anhängen.
   - Mit einer Dateisystem-Erweiterung (Einstellungen > Erweiterungen) kann Claude `page.xhtml` auch direkt im Export-Ordner lesen und schreiben.
4. **Ergebnis speichern:** Claudes Antwort (die vollständige Datei) unverändert in `page.xhtml` speichern, Kodierung UTF-8. `page.json` nicht anfassen.
5. **Prüfen und hochladen**
   ```sh
   cd ~/confluence/123456-projekthandbuch
   cfh validate .
   cfh diff .
   cfh push . --dry-run
   cfh push . -m "Kapitel Betrieb ergänzt"
   ```
   Meldet `validate` Fehler (Exit 2), die Meldung mit Zeile/Spalte an Claude zurückgeben und korrigieren lassen.
6. **Nächste Runde:** Nach dem Push steht in `page.xhtml` der von Confluence gespeicherte, leicht normalisierte Storage (z.B. ergänzte `ac:macro-id`). Für weitere Änderungen immer diese aktuelle Datei an Claude geben, nicht eine ältere Fassung aus dem Chat.

Neue Seite statt bestehender: `cfh convert quelle.pdf -o entwurf/page.xhtml` (oder eine leere Datei), mit Claude bearbeiten, dann `cfh create entwurf/page.xhtml --space TEAM --title "…"`.

Bilder oder Dateien, die die Seite neu referenziert, vorher in den Ordner `attachments/` legen und mit `cfh push . --attachments` hochladen; im Storage verweist man über den Dateinamen darauf (siehe unten).

## Anweisungsblock für das Projekt

In die Projektanweisungen von Claude Desktop kopieren. Die Regeln und Bausteine darin sind dieselben, die `cfh workspace init` für GitHub Copilot in `.github/copilot-instructions.md` schreibt (Quelle: `src/main/resources/ch/confluencehelper/workspace/`, ein Test hält beide gleich):

````text
Du bearbeitest Confluence-Seiten im Confluence-Storage-Format (XHTML, Confluence Data Center).
Die Dateien heissen page.xhtml und werden mit dem Werkzeug «cfh» wieder hochgeladen.

## Antwortformat

Gib immer die VOLLSTÄNDIGE Datei zurück, in genau einem Codeblock (```xml), ohne Kürzungen wie «...» oder «Rest unverändert». Keine Erklärungen im Codeblock. Neue Dateinamen für Anhänge nennst du nach dem Codeblock.

## Regeln für das Storage-Format

1. `page.xhtml` ist ein XHTML-Fragment: KEIN `<?xml ...?>`, kein DOCTYPE, kein `<html>`, `<head>` oder `<body>`.
2. Lass alles, was du nicht ändern sollst, Zeichen für Zeichen unverändert, auch Reihenfolge, Zeilenumbrüche und Attribute.
3. Alle Elemente und Attribute mit Präfix `ac:` oder `ri:` bleiben exakt erhalten, insbesondere `ac:macro-id`, `ac:schema-version`, `ac:name`, `ac:local-id`, `ri:filename`, `ri:content-title`, `ri:space-key`, `ri:userkey`, `ac:task-id`. Bestehende Makros nie in normales HTML umschreiben und keine bestehenden IDs ändern oder erfinden. Neue Makros bekommen KEINE `ac:macro-id` (Confluence vergibt sie beim Speichern).
4. Wohlgeformtes XML: jedes Element schliessen, leere Elemente als `<br />`, `<hr />`, `<ri:attachment ri:filename="x.png" />`. Attribute immer in doppelten Anführungszeichen.
5. Im Text maskieren: `&` → `&amp;`, `<` → `&lt;`, `>` → `&gt;`; in Attributen zusätzlich `"` → `&quot;`. Umlaute und Sonderzeichen direkt als UTF-8 schreiben (ä, é, «»), nicht als Entity.
6. Code gehört in ein code-Makro mit `<![CDATA[ ... ]]>`; darin wird nichts maskiert. Kommt `]]>` im Code vor, als `]]]]><![CDATA[>` schreiben.
7. Kein `<script>`, kein `<style>`, keine `style`- oder `class`-Attribute erfinden; bestehende lassen.
8. Seitenlinks nur auf Seiten, deren Titel du kennst; Bilder und Dateien nur mit Dateinamen, die als Anhang existieren oder die der Benutzer hochladen wird. Neue Dateinamen ausdrücklich nennen.
9. `page.json` nie bearbeiten. Wenn etwas unklar ist, nachfragen statt raten.
10. Beginnt die Seite mit `<ac:layout>` (Seitenlayout), gehört jeder neue Inhalt in eine `<ac:layout-cell>`: «oben» heisst am Anfang der ersten Zelle der ersten `<ac:layout-section>`, «am Ende» am Schluss der letzten Zelle der letzten Section. Nie Inhalt vor, nach oder neben `<ac:layout>` schreiben.

## Bausteine (genau so schreiben)

Überschriften, Text und Listen (Seiten beginnen meist mit `<h2>`, der Seitentitel steht schon oben):

```xml
<h2>Titel</h2>
<p>Text mit <strong>fett</strong>, <em>kursiv</em>, <code>inline-code</code> und <a href="https://example.ch">Link</a>.<br />Neue Zeile.</p>
<ul><li><p>Punkt</p></li></ul>
<ol><li><p>Schritt</p></li></ol>
```

Tabelle (bei bestehenden Tabellen vorhandene Attribute und `<colgroup>` beibehalten, neue Zeilen im gleichen Aufbau):

```xml
<table><tbody>
<tr><th><p>Spalte A</p></th><th><p>Spalte B</p></th></tr>
<tr><td><p>Wert 1</p></td><td><p>Wert 2</p></td></tr>
</tbody></table>
```

Panel (`info` = blau, `tip` = grün, `note` = gelb, `warning` = rot; Titel optional):

```xml
<ac:structured-macro ac:name="warning" ac:schema-version="1"><ac:parameter ac:name="title">Achtung</ac:parameter><ac:rich-text-body><p>Text</p></ac:rich-text-body></ac:structured-macro>
```

Code:

```xml
<ac:structured-macro ac:name="code" ac:schema-version="1"><ac:parameter ac:name="language">bash</ac:parameter><ac:parameter ac:name="title">Beispiel</ac:parameter><ac:plain-text-body><![CDATA[echo "a < b && c"]]></ac:plain-text-body></ac:structured-macro>
```

Status (`colour`: `Grey`, `Red`, `Yellow`, `Green`, `Blue`; steht inline, z.B. in einem `<p>`):

```xml
<p><ac:structured-macro ac:name="status" ac:schema-version="1"><ac:parameter ac:name="colour">Green</ac:parameter><ac:parameter ac:name="title">Erledigt</ac:parameter></ac:structured-macro></p>
```

Link auf eine Seite (`ri:space-key` nur bei anderem Space):

```xml
<p><ac:link><ri:page ri:space-key="TEAM" ri:content-title="Betriebshandbuch" /><ac:plain-text-link-body><![CDATA[Betriebshandbuch]]></ac:plain-text-link-body></ac:link></p>
```

Bild aus einem Anhang der Seite:

```xml
<p><ac:image ac:alt="Architektur" ac:width="600"><ri:attachment ri:filename="architektur.png" /></ac:image></p>
```

Link auf einen Anhang:

```xml
<p><ac:link><ri:attachment ri:filename="offerte.pdf" /><ac:plain-text-link-body><![CDATA[Offerte (PDF)]]></ac:plain-text-link-body></ac:link></p>
```

Aufgabenliste (neue `ac:task-id` = nächste freie Zahl auf der Seite; Status `incomplete` oder `complete`):

```xml
<ac:task-list>
<ac:task><ac:task-id>1</ac:task-id><ac:task-status>incomplete</ac:task-status><ac:task-body>Offerte prüfen</ac:task-body></ac:task>
</ac:task-list>
```

Aufklappbarer Bereich:

```xml
<ac:structured-macro ac:name="expand" ac:schema-version="1"><ac:parameter ac:name="title">Details anzeigen</ac:parameter><ac:rich-text-body><p>Text</p></ac:rich-text-body></ac:structured-macro>
```

Seitenlayout (bestehende Sections und Zellen nie umbauen; neuer Inhalt nur in eine `<ac:layout-cell>`):

```xml
<ac:layout><ac:layout-section ac:type="single"><ac:layout-cell><h2>Titel</h2><p>Text</p></ac:layout-cell></ac:layout-section></ac:layout>
```

Inhaltsverzeichnis:

```xml
<ac:structured-macro ac:name="toc" ac:schema-version="1"><ac:parameter ac:name="maxLevel">3</ac:parameter></ac:structured-macro>
```
````

## Bausteine im Detail

Alle Beispiele sind mit `cfh validate` geprüft.

### Überschriften, Absätze, Listen

```xml
<h2>Betrieb</h2>
<p>Das System läuft auf <strong>zwei</strong> Servern.<br />Wartung jeweils am Dienstag.</p>
<ul>
  <li><p>Server A: Produktion</p></li>
  <li><p>Server B: Test</p></li>
</ul>
<ol>
  <li><p>Backup prüfen</p></li>
  <li><p>Update einspielen</p></li>
</ol>
```

`<h1>` bis `<h6>` sind möglich; auf Confluence-Seiten beginnt man meist mit `<h2>`, weil der Seitentitel bereits oben steht. `<p>` in `<li>` ist optional, Confluence speichert es oft so.

### Sonderzeichen

```xml
<p>Kosten &amp; Nutzen: Wert &lt; 10 und &gt; 5, Firma «Muster AG».</p>
```

Ein nacktes `&` oder `<` im Text macht die Datei ungültig (`cfh validate` meldet Zeile und Spalte). Named Entities wie `&nbsp;` oder `&auml;` sind zulässig, direkte UTF-8-Zeichen sind aber lesbarer.

### Tabelle

```xml
<table>
  <tbody>
    <tr><th><p>Rolle</p></th><th><p>Person</p></th><th><p>Telefon</p></th></tr>
    <tr><td><p>Betrieb</p></td><td><p>Anna Muster</p></td><td><p>+41 41 000 00 00</p></td></tr>
  </tbody>
</table>
```

Verbundene Zellen: `colspan="2"` bzw. `rowspan="2"` auf `<td>`/`<th>`. Bei bestehenden Tabellen Attribute wie `class="wrapped"` und das `<colgroup>` stehen lassen.

### Info-, Tipp-, Hinweis- und Warn-Panel

```xml
<ac:structured-macro ac:name="info" ac:schema-version="1">
  <ac:parameter ac:name="title">Gut zu wissen</ac:parameter>
  <ac:rich-text-body><p>Das Wartungsfenster ist dienstags 18–20 Uhr.</p></ac:rich-text-body>
</ac:structured-macro>
```

`ac:name` ist `info` (blau), `tip` (grün), `note` (gelb) oder `warning` (rot). Der Parameter `title` ist optional. In `ac:rich-text-body` steht normales XHTML (Absätze, Listen, Tabellen).

### Code-Makro

```xml
<ac:structured-macro ac:name="code" ac:schema-version="1">
  <ac:parameter ac:name="language">java</ac:parameter>
  <ac:parameter ac:name="title">Beispiel</ac:parameter>
  <ac:parameter ac:name="linenumbers">true</ac:parameter>
  <ac:plain-text-body><![CDATA[if (a < b && c) {
    System.out.println("<ok>");
}]]></ac:plain-text-body>
</ac:structured-macro>
```

Der Code steht in `<![CDATA[ … ]]>` und wird darin **nicht** maskiert. Ohne CDATA warnt `cfh validate`. Übliche Sprachen: `bash`, `java`, `js`, `python`, `sql`, `xml`, `yaml`, `json`, `text`.

### Status

```xml
<p>Stand: <ac:structured-macro ac:name="status" ac:schema-version="1"><ac:parameter ac:name="colour">Yellow</ac:parameter><ac:parameter ac:name="title">In Arbeit</ac:parameter></ac:structured-macro></p>
```

`colour` (britische Schreibweise): `Grey`, `Red`, `Yellow`, `Green`, `Blue`. Das Makro steht inline, also innerhalb eines `<p>` oder einer Tabellenzelle.

### Links

```xml
<!-- Seite im selben Space -->
<p><ac:link><ri:page ri:content-title="Betriebshandbuch" /><ac:plain-text-link-body><![CDATA[zum Betriebshandbuch]]></ac:plain-text-link-body></ac:link></p>
<!-- Seite in einem anderen Space -->
<p><ac:link><ri:page ri:space-key="IT" ri:content-title="Netzwerk" /></ac:link></p>
<!-- Abschnitt auf einer Seite -->
<p><ac:link ac:anchor="Betrieb"><ri:page ri:content-title="Betriebshandbuch" /></ac:link></p>
<!-- externe Adresse -->
<p><a href="https://www.example.ch/doku">Herstellerdoku</a></p>
```

Seitenlinks verweisen über den **exakten Titel** (`ri:content-title`). Ohne Linktext zeigt Confluence den Seitentitel an.

### Bilder und Anhänge

```xml
<p><ac:image ac:alt="Architektur" ac:width="600"><ri:attachment ri:filename="architektur.png" /></ac:image></p>
<p><ac:link><ri:attachment ri:filename="offerte.pdf" /><ac:plain-text-link-body><![CDATA[Offerte (PDF)]]></ac:plain-text-link-body></ac:link></p>
```

Die Datei muss als Anhang der Seite existieren. Neue Dateien in `attachments/` legen und mit `cfh push . --attachments` hochladen (braucht das Recht «Anhänge hinzufügen»). Bild aus dem Internet: `<ac:image><ri:url ri:value="https://…/bild.png" /></ac:image>`.

### Aufgabenliste

```xml
<ac:task-list>
  <ac:task>
    <ac:task-id>1</ac:task-id>
    <ac:task-status>incomplete</ac:task-status>
    <ac:task-body>Offerte prüfen</ac:task-body>
  </ac:task>
  <ac:task>
    <ac:task-id>2</ac:task-id>
    <ac:task-status>complete</ac:task-status>
    <ac:task-body>Termin vereinbaren</ac:task-body>
  </ac:task>
</ac:task-list>
```

Bestehende `ac:task-id` nie ändern; neue Aufgaben bekommen eine auf der Seite noch nicht verwendete Zahl. Erwähnungen von Personen (`<ri:user ri:userkey="…" />`) nur aus bestehendem Inhalt übernehmen, Userkeys lassen sich nicht erraten.

### Aufklappbarer Bereich (Expand)

```xml
<ac:structured-macro ac:name="expand" ac:schema-version="1">
  <ac:parameter ac:name="title">Technische Details anzeigen</ac:parameter>
  <ac:rich-text-body><p>Port 8443, TLS 1.3.</p></ac:rich-text-body>
</ac:structured-macro>
```

### Inhaltsverzeichnis

```xml
<ac:structured-macro ac:name="toc" ac:schema-version="1">
  <ac:parameter ac:name="maxLevel">3</ac:parameter>
</ac:structured-macro>
```

Üblicherweise ganz oben auf der Seite; es listet die Überschriften `<h1>`–`<h6>` (hier bis Stufe 3).

## Typische Fehler

| Symptom | Ursache / Abhilfe |
| --- | --- |
| `cfh validate`: «Auf "&" … muss umgehend der Entityname folgen» | `&` im Text nicht als `&amp;` geschrieben |
| `validate`: Element nicht geschlossen | `<br>` statt `<br />`, fehlendes `</p>` oder `</ac:structured-macro>` |
| Makro erscheint auf der Seite als «Unbekanntes Makro» | Tippfehler in `ac:name` oder Makro-Plugin nicht installiert |
| Bild fehlt («Anhang nicht gefunden») | `ri:filename` stimmt nicht mit dem Anhang überein oder Datei nicht mit `--attachments` hochgeladen |
| Teile der Seite verschwunden | Claude hat gekürzt («…») – immer die vollständige Datei verlangen und vor dem Push `cfh diff` prüfen |
| `push` bricht mit Exit 3 ab | Seite wurde inzwischen geändert, siehe README, Abschnitt «Versionskonflikt» |
