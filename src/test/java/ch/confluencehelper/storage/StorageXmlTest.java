package ch.confluencehelper.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageXmlTest {

    private static final String REALISTIC = """
            <h1>Projekt&uuml;bersicht</h1>
            <p>Gr&ouml;sse &amp; Umfang &ndash; Stand&nbsp;2026 &hellip; &laquo;Zitat&raquo; &copy; &euro; &trade;</p>
            <ac:structured-macro ac:name="info" ac:schema-version="1" ac:macro-id="3f2a8c1e-1b2c-4d5e-8f90-123456789abc">
              <ac:parameter ac:name="title">Hinweis</ac:parameter>
              <ac:rich-text-body><p>x</p></ac:rich-text-body>
            </ac:structured-macro>
            <p><ac:link><ri:page ri:content-title="X" ri:space-key="K"/><ac:plain-text-link-body><![CDATA[Text]]></ac:plain-text-link-body></ac:link></p>
            <p><ac:image ac:height="250"><ri:attachment ri:filename="a.png"/></ac:image></p>
            <ac:task-list>
            <ac:task><ac:task-id>1</ac:task-id><ac:task-status>incomplete</ac:task-status><ac:task-body>Aufgabe <time datetime="2026-01-01"/></ac:task-body></ac:task>
            </ac:task-list>
            <p><ac:emoticon ac:name="tick"/> erledigt</p>
            <p><ac:placeholder>Hier Text eingeben</ac:placeholder></p>
            <ac:structured-macro ac:name="code" ac:schema-version="1">
              <ac:parameter ac:name="language">java</ac:parameter>
              <ac:plain-text-body><![CDATA[<!DOCTYPE html> &foo; if (a < b && c > d) { }]]></ac:plain-text-body>
            </ac:structured-macro>
            <table data-layout="default"><colgroup><col style="width: 120.0px;"/><col style="width: 340.0px;"/></colgroup>
            <tbody><tr><th><p>Name</p></th><th><p>Wert</p></th></tr>
            <tr><td><p>&Auml;nderung</p></td><td><p>&szlig;-frei &mdash; &bdquo;ok&ldquo; &rsquo;</p></td></tr></tbody></table>
            <ac:structured-macro ac:name="toc" ac:schema-version="1"><ac:parameter ac:name="">default</ac:parameter></ac:structured-macro>
            <p>Mit <at:var at:name="x"/> Template-Variable.</p>
            """;

    @Test
    void realisticStorageIsValid() {
        assertThat(StorageXml.validate(REALISTIC)).isEmpty();
    }

    @Test
    void emptyStringIsValid() {
        assertThat(StorageXml.validate("")).isEmpty();
    }

    @Test
    void plainTextAndCrLfAreValid() {
        assertThat(StorageXml.validate("nur Text")).isEmpty();
        assertThat(StorageXml.validate("<p>a</p>\r\n<p>b</p>\r\n")).isEmpty();
    }

    @Test
    void escapedTextIsValid() {
        String text = "a & b < c > d \" ' \u0001";
        assertThat(StorageXml.validate("<p title=\"" + StorageXml.escapeAttr(text) + "\">"
                + StorageXml.escapeText(text) + "</p>")).isEmpty();
    }

    @Test
    void allHtml4EntitiesAreKnown() {
        String sample = "<p>&nbsp;&auml;&ouml;&uuml;&Auml;&Ouml;&Uuml;&szlig;&euro;&ndash;&mdash;&hellip;"
                + "&laquo;&raquo;&copy;&reg;&trade;&rsquo;&lsquo;&ldquo;&rdquo;&bdquo;&sbquo;&bull;&middot;"
                + "&deg;&plusmn;&times;&divide;&frac12;&alpha;&Omega;&rarr;&hArr;&infin;&ne;&le;&ge;"
                + "&thinsp;&zwnj;&lrm;&dagger;&permil;&lsaquo;&oelig;&Yuml;&circ;&tilde;&yuml;&apos;"
                + "&amp;&lt;&gt;&quot;&#228;&#xE4;</p>";
        assertThat(StorageXml.validate(sample)).isEmpty();
    }

    @Test
    void manyEntitiesDoNotHitExpansionLimits() {
        String big = "<p>" + "a&nbsp;&auml;".repeat(100_000) + "</p>";
        assertThat(StorageXml.validate(big)).isEmpty();
    }

    @Test
    void unclosedTagReportsLineOfOpeningTag() {
        String input = "<p>eins</p>\n<p>zwei\n<p>drei</p>\n";
        List<Problem> problems = StorageXml.validate(input);
        assertThat(problems).hasSize(1);
        Problem p = problems.get(0);
        assertThat(p.isError()).isTrue();
        assertThat(p.line()).isEqualTo(2);
        assertThat(p.message()).contains("<p>");
    }

    @Test
    void mismatchedEndTagReportsCorrectLine() {
        String input = "<p>ok</p>\n<p>ok</p>\n<div><strong>x</div>\n<p>danach</p>";
        Problem p = StorageXml.validate(input).get(0);
        assertThat(p.isError()).isTrue();
        assertThat(p.line()).isEqualTo(3);
    }

    @Test
    void errorOnFirstLineKeepsColumn() {
        Problem p = StorageXml.validate("<p>a & b</p>").get(0);
        assertThat(p.isError()).isTrue();
        assertThat(p.line()).isEqualTo(1);
        assertThat(p.column()).isBetween(6, 8);
    }

    @Test
    void rawAmpersandIsError() {
        List<Problem> problems = StorageXml.validate("<p>x</p>\n<p>Fish & Chips</p>");
        assertThat(problems).singleElement().satisfies(p -> {
            assertThat(p.isError()).isTrue();
            assertThat(p.line()).isEqualTo(2);
        });
    }

    @Test
    void undefinedEntityIsError() {
        List<Problem> problems = StorageXml.validate("<p>a</p>\n\n<p>&foobar;</p>");
        assertThat(problems).singleElement().satisfies(p -> {
            assertThat(p.isError()).isTrue();
            assertThat(p.line()).isEqualTo(3);
            assertThat(p.message()).contains("foobar");
        });
    }

    @Test
    void unboundPrefixIsError() {
        assertThat(StorageXml.validate("<foo:bar/>")).singleElement().matches(Problem::isError);
    }

    @Test
    void externalEntityIsNotResolved(@TempDir Path dir) throws Exception {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET-CONTENT");
        String uri = secret.toUri().toString();
        String input = "<!DOCTYPE x [<!ENTITY xxe SYSTEM \"" + uri + "\">]>\n<p>&xxe;</p>";
        List<Problem> problems = StorageXml.validate(input);
        assertThat(problems).isNotEmpty();
        assertThat(problems.get(0).isError()).isTrue();
        assertThat(problems.get(0).line()).isEqualTo(1);
        assertThat(problems).noneMatch(p -> p.message().contains("TOP-SECRET-CONTENT"));

        // an entity reference that is not one of the built-in HTML entities never reaches the file system
        assertThat(StorageXml.validate("<p>&xxe;</p>")).singleElement().matches(Problem::isError);
    }

    @Test
    void wrapperCannotBeEscaped() {
        assertThat(StorageXml.validate("]><p/>")).isEmpty(); // plain text, harmless
        assertThat(StorageXml.validate("<?xml version=\"1.0\"?><p/>")).anyMatch(Problem::isError);
    }

    @Test
    void macroWithoutNameWarns() {
        List<Problem> problems = StorageXml.validate(
                "<p>x</p>\n<ac:structured-macro ac:schema-version=\"1\"><ac:rich-text-body/></ac:structured-macro>");
        assertThat(problems).singleElement().satisfies(p -> {
            assertThat(p.severity()).isEqualTo(Problem.Severity.WARNING);
            assertThat(p.line()).isEqualTo(2);
            assertThat(p.message()).contains("ac:name");
        });
    }

    @Test
    void scriptWarns() {
        assertThat(StorageXml.validate("<p>a</p><script>alert(1)</script>"))
                .singleElement()
                .satisfies(p -> {
                    assertThat(p.severity()).isEqualTo(Problem.Severity.WARNING);
                    assertThat(p.message()).contains("<script>");
                });
    }

    @Test
    void codeMacroWithoutCdataWarns() {
        String code = """
                <ac:structured-macro ac:name="code"><ac:plain-text-body>int a = 1;</ac:plain-text-body></ac:structured-macro>
                <ac:structured-macro ac:name="noformat"><ac:plain-text-body>
                  a &lt; b
                </ac:plain-text-body></ac:structured-macro>
                <ac:structured-macro ac:name="code"><ac:plain-text-body><![CDATA[ok]]></ac:plain-text-body></ac:structured-macro>
                <ac:structured-macro ac:name="code"><ac:plain-text-body/></ac:structured-macro>
                """;
        List<Problem> problems = StorageXml.validate(code);
        assertThat(problems).hasSize(2).allMatch(p -> p.severity() == Problem.Severity.WARNING);
        assertThat(problems).extracting(Problem::line).containsExactly(1, 2);
        assertThat(problems.get(0).message()).contains("CDATA");
    }

    @Test
    void attachmentWithoutFilenameWarns() {
        assertThat(StorageXml.validate("<ac:image><ri:attachment ri:version-at-save=\"1\"/></ac:image>"))
                .singleElement()
                .satisfies(p -> assertThat(p.message()).contains("ri:filename"));
    }

    @Test
    void parameterWithoutNameWarns() {
        assertThat(StorageXml.validate(
                "<ac:structured-macro ac:name=\"info\"><ac:parameter>x</ac:parameter></ac:structured-macro>"))
                .singleElement()
                .satisfies(p -> {
                    assertThat(p.severity()).isEqualTo(Problem.Severity.WARNING);
                    assertThat(p.message()).contains("ac:parameter");
                });
    }

    @Test
    void warningsPrecedeFatalError() {
        List<Problem> problems = StorageXml.validate("<script/>\n<p>&</p>");
        assertThat(problems).extracting(Problem::severity)
                .containsExactly(Problem.Severity.WARNING, Problem.Severity.ERROR);
    }

    static final String LAYOUT = "<ac:layout><ac:layout-section ac:type=\"two_equal\">"
            + "<ac:layout-cell><p>A</p></ac:layout-cell><ac:layout-cell><p>B</p></ac:layout-cell>"
            + "</ac:layout-section></ac:layout>";

    @Test
    void contentInsideLayoutIsValid() {
        assertThat(StorageXml.validate(LAYOUT)).isEmpty();
        assertThat(StorageXml.validate("\n" + LAYOUT + "\n<!-- Kommentar -->\n")).isEmpty();
    }

    @Test
    void contentBesideLayoutWarns() {
        String panel = "<ac:structured-macro ac:name=\"info\" ac:schema-version=\"1\"><ac:rich-text-body>"
                + "<p>Zusammenfassung</p></ac:rich-text-body></ac:structured-macro>";
        for (String doc : List.of(panel + "\n" + LAYOUT, LAYOUT + "\n<h2>Neu</h2>", LAYOUT + "\nText", "x" + LAYOUT)) {
            assertThat(StorageXml.validate(doc)).as(doc).singleElement().satisfies(p -> {
                assertThat(p.severity()).isEqualTo(Problem.Severity.WARNING);
                assertThat(p.message()).contains("ausserhalb von <ac:layout>", "ac:layout-cell");
            });
        }
        assertThat(StorageXml.validate(LAYOUT + "\n<h2>Neu</h2>").get(0).line()).isEqualTo(2);
    }

    @Test
    void attachmentRefsListsOwnAttachmentsOnce() {
        String doc = "<p><ac:image><ri:attachment ri:filename=\"a.png\" /></ac:image></p>\n"
                + "<p><ac:link><ri:attachment ri:filename=\"b.pdf\" /></ac:link>"
                + "<ac:image><ri:attachment ri:filename=\"a.png\" /></ac:image></p>\n"
                + "<ac:link><ri:attachment ri:filename=\"c.pdf\"><ri:page ri:content-title=\"X\" /></ri:attachment></ac:link>"
                + "<ac:image><ri:url ri:value=\"https://x/y.png\" /></ac:image>";
        assertThat(StorageXml.attachmentRefs(doc)).extracting(StorageXml.AttachmentRef::filename)
                .containsExactly("a.png", "b.pdf");
        assertThat(StorageXml.attachmentRefs(doc).get(1).line()).isEqualTo(2);
        assertThat(StorageXml.attachmentRefs("<p>kaputt")).isEmpty();
    }
}
