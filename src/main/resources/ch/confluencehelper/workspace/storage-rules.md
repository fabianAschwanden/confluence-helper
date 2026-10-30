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
