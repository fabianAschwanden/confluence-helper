package ch.confluencehelper.convert;

import static ch.confluencehelper.convert.ExcelConverterTest.assertWellFormed;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvConverterTest {

    @TempDir
    Path dir;

    private final CsvConverter converter = new CsvConverter();

    @Test
    void commaSeparatedExactOutput() throws Exception {
        String out = convert("a.csv", "Name,Ort\nMüller <&>,Zürich\n".getBytes(StandardCharsets.UTF_8));

        assertThat(out).isEqualTo("""
                <table>
                <tbody>
                <tr><th>Name</th><th>Ort</th></tr>
                <tr><td>Müller &lt;&amp;&gt;</td><td>Zürich</td></tr>
                </tbody>
                </table>
                """);
        assertWellFormed(out);
    }

    @Test
    void quotedFieldsWithDelimitersQuotesAndNewlines() throws Exception {
        String csv = "Titel,Notiz\r\n\"a, b\",\"Er sagte \"\"Hallo\"\"\r\nzweite Zeile\"\r\nx,y\r\n";

        String out = convert("q.csv", csv.getBytes(StandardCharsets.UTF_8));

        assertThat(out).contains("<tr><td>a, b</td><td>Er sagte \"Hallo\"<br/>zweite Zeile</td></tr>",
                "<tr><td>x</td><td>y</td></tr>");
        assertWellFormed(out);
    }

    @Test
    void semicolonWithDecimalCommasAndWindows1252() throws Exception {
        String csv = "Ort;Betrag;Anteil\r\nZürich;1,50;12,5%\r\nGenève;2,75;87,5%\r\n";

        String out = convert("ch.csv", csv.getBytes(Charset.forName("windows-1252")));

        assertThat(out).contains("<tr><th>Ort</th><th>Betrag</th><th>Anteil</th></tr>",
                "<tr><td>Zürich</td><td>1,50</td><td>12,5%</td></tr>",
                "<tr><td>Genève</td><td>2,75</td><td>87,5%</td></tr>");
    }

    @Test
    void utf8BomIsStripped() throws Exception {
        byte[] body = "Größe;Wert\nÄ;1\n".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[body.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(body, 0, bytes, 3, body.length);

        String out = convert("bom.csv", bytes);

        assertThat(out).contains("<tr><th>Größe</th><th>Wert</th></tr>").doesNotContain("﻿");
    }

    @Test
    void tsvUsesTab() throws Exception {
        String out = convert("t.tsv", "a,b\tc;d\n1,2\t3;4\n".getBytes(StandardCharsets.UTF_8));
        assertThat(out).contains("<tr><th>a,b</th><th>c;d</th></tr>", "<tr><td>1,2</td><td>3;4</td></tr>");
    }

    @Test
    void detectsTabInCsvFileAndExcelSepLine() throws Exception {
        assertThat(convert("x.csv", "a\tb\n1\t2\n".getBytes(StandardCharsets.UTF_8)))
                .contains("<tr><td>1</td><td>2</td></tr>");
        assertThat(convert("y.csv", "sep=|\na|b\n1|2\n".getBytes(StandardCharsets.UTF_8)))
                .contains("<tr><th>a</th><th>b</th></tr>").doesNotContain("sep=");
    }

    @Test
    void delimiterDetectionIgnoresQuotedContent() {
        assertThat(CsvConverter.detectDelimiter("\"a;b;c\",x\n\"d;e\",y\n")).isEqualTo(',');
        assertThat(CsvConverter.detectDelimiter("a;b\nc;d,e\n")).isEqualTo(';');
        assertThat(CsvConverter.detectDelimiter("einzelne Spalte\n")).isEqualTo(',');
    }

    @Test
    void raggedRowsArePaddedAndBorderRowsTrimmed() throws Exception {
        String out = convert("r.csv", "\n,,\nA,B,C\n1\n\n".getBytes(StandardCharsets.UTF_8));
        assertThat(out).contains("<tr><th>A</th><th>B</th><th>C</th></tr>\n<tr><td>1</td><td></td><td></td></tr>\n</tbody>");
        assertWellFormed(out);
    }

    @Test
    void titleHeaderOptionAndEmptyFile() throws Exception {
        String out = converter.toStorage(write("t.csv", "a,b\n".getBytes(StandardCharsets.UTF_8)),
                new ConvertOptions(List.of(), false, false, "Liste"));
        assertThat(out).startsWith("<h1>Liste</h1>\n<table>").contains("<tr><td>a</td><td>b</td></tr>");

        assertThat(convert("e.csv", new byte[0])).isEqualTo("<p>Keine Daten gefunden.</p>\n");
    }

    @Test
    void parserHandlesLineEndingsAndMissingFinalNewline() {
        assertThat(CsvConverter.parse("a,b\rc,\"d\"", ','))
                .containsExactly(List.of("a", "b"), List.of("c", "d"));
        assertThat(CsvConverter.parse("x,\"\"\n", ',')).containsExactly(List.of("x", ""));
    }

    private String convert(String name, byte[] content) throws IOException {
        return converter.toStorage(write(name, content), ConvertOptions.defaults());
    }

    private Path write(String name, byte[] content) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, content);
        return file;
    }

    @Test
    void tooLargeTableNamesNoExcelOptions() {
        String csv = "a\n" + "\n".repeat(1000) + ";".repeat(1000) + "b\n";
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> convert("gross.csv", csv.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(TableTooLargeException.class, e -> {
                    assertThat(e.sheet()).isFalse();
                    assertThat(e.getMessage()).contains("Tabelle zu gross").doesNotContain("--sheet").doesNotContain("Blatt");
                });
    }
}
