package ch.confluencehelper.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExcelConverterTest {

    @TempDir
    Path dir;

    private final ExcelConverter converter = new ExcelConverter(Locale.US);

    @Test
    void simpleSheetExactOutput() throws Exception {
        Workbook wb = new XSSFWorkbook();
        Sheet s = wb.createSheet("Übersicht");
        set(s, 0, 0, "Name");
        set(s, 0, 1, "Ort");
        set(s, 1, 0, "Müller <&> \"Söhne\"");
        set(s, 1, 1, "Zürich");

        String out = convert(wb, "a.xlsx", ConvertOptions.defaults());

        assertThat(out).isEqualTo("""
                <h2>Übersicht</h2>
                <table>
                <tbody>
                <tr><th>Name</th><th>Ort</th></tr>
                <tr><td>Müller &lt;&amp;&gt; "Söhne"</td><td>Zürich</td></tr>
                </tbody>
                </table>
                """);
        assertWellFormed(out);
    }

    @Test
    void titleAndNoHeaderRow() throws Exception {
        Workbook wb = new XSSFWorkbook();
        set(wb.createSheet("S"), 0, 0, "x");

        String out = convert(wb, "a.xlsx", new ConvertOptions(List.of(), false, false, "Bericht & Co"));

        assertThat(out).startsWith("<h1>Bericht &amp; Co</h1>\n<h2>S</h2>\n");
        assertThat(out).contains("<tr><td>x</td></tr>").doesNotContain("<th>");
    }

    @Test
    void formatsDatesPercentCurrencyAndFormulas() throws Exception {
        Workbook wb = new XSSFWorkbook();
        Sheet s = wb.createSheet("Zahlen");
        set(s, 0, 0, "Wert");
        Row r1 = s.createRow(1);
        Cell date = r1.createCell(0);
        date.setCellValue(LocalDate.of(2024, 3, 15));
        date.setCellStyle(style(wb, "dd.mm.yyyy"));
        Row r2 = s.createRow(2);
        Cell pct = r2.createCell(0);
        pct.setCellValue(0.125);
        pct.setCellStyle(style(wb, "0.0%"));
        Row r3 = s.createRow(3);
        Cell chf = r3.createCell(0);
        chf.setCellValue(1234.5);
        chf.setCellStyle(style(wb, "\"CHF \"#,##0.00"));
        Row r4 = s.createRow(4);
        r4.createCell(0).setCellFormula("A4*2");
        Row r5 = s.createRow(5);
        r5.createCell(0).setCellFormula("A2+0");
        r5.getCell(0).setCellStyle(style(wb, "yyyy-mm-dd"));

        String out = convert(wb, "z.xlsx", ConvertOptions.defaults());

        assertThat(out).contains("<td>15.03.2024</td>", "<td>12.5%</td>", "<td>CHF 1,234.50</td>",
                "<td>2469</td>", "<td>2024-03-15</td>");
        assertThat(out).doesNotContain("A4*2");
        assertWellFormed(out);
    }

    @Test
    void builtinShortDateFollowsLocaleLikeExcel() throws Exception {
        Workbook wb = new XSSFWorkbook();
        Sheet s = wb.createSheet("D");
        Cell c = s.createRow(0).createCell(0);
        c.setCellValue(LocalDate.of(2024, 3, 15));
        CellStyle st = wb.createCellStyle();
        st.setDataFormat((short) 14);
        c.setCellStyle(st);
        Path file = save(wb, "d.xlsx");

        assertThat(new ExcelConverter(Locale.forLanguageTag("de-CH")).toStorage(file, ConvertOptions.defaults()))
                .contains("<th>15.03.2024</th>");
        assertThat(converter.toStorage(file, ConvertOptions.defaults())).contains("<th>3/15/2024</th>");
    }

    @Test
    void unsupportedFormulaFallsBackToCachedValue() throws Exception {
        Workbook wb = new XSSFWorkbook();
        Sheet s = wb.createSheet("F");
        set(s, 0, 0, "Wert");
        Cell f = s.createRow(1).createCell(0);
        f.setCellFormula("CELL(\"address\",A1)");
        f.setCellValue("$A$1"); // cached result as Excel would have stored it

        String out = convert(wb, "f.xlsx", ConvertOptions.defaults());

        assertThat(out).contains("<td>$A$1</td>");
    }

    @Test
    void mergedCellsBecomeSpans() throws Exception {
        Workbook wb = new XSSFWorkbook();
        Sheet s = wb.createSheet("M");
        set(s, 0, 0, "Gruppe");
        set(s, 0, 2, "C");
        set(s, 1, 0, "lang");
        set(s, 1, 1, "b1");
        set(s, 1, 2, "c1");
        set(s, 2, 1, "b2");
        set(s, 2, 2, "c2");
        s.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));
        s.addMergedRegion(new CellRangeAddress(1, 2, 0, 0));

        String out = convert(wb, "m.xlsx", ConvertOptions.defaults());

        assertThat(out).contains(
                "<tr><th colspan=\"2\">Gruppe</th><th>C</th></tr>\n"
                        + "<tr><td rowspan=\"2\">lang</td><td>b1</td><td>c1</td></tr>\n"
                        + "<tr><td>b2</td><td>c2</td></tr>\n");
        assertWellFormed(out);
    }

    @Test
    void mergedRegionBeyondTheDataIsClampedToIt() throws Exception {
        Workbook wb = new XSSFWorkbook();
        Sheet s = wb.createSheet("M");
        set(s, 0, 0, "a");
        set(s, 0, 1, "b");
        set(s, 1, 0, "Fusszeile");
        s.addMergedRegion(new CellRangeAddress(1, 1, 0, 2));

        String out = convert(wb, "m.xlsx", ConvertOptions.defaults());

        assertThat(out).contains("<tr><th>a</th><th>b</th></tr>", "<tr><td colspan=\"2\">Fusszeile</td></tr>");
    }

    @Test
    void trimsEmptyBorderRowsAndColumnsAndConvertsLineBreaks() throws Exception {
        Workbook wb = new XSSFWorkbook();
        Sheet s = wb.createSheet("T");
        s.createRow(0).createCell(0).setCellValue("   ");
        set(s, 3, 2, "Kopf");
        set(s, 4, 2, "Zeile 1\nZeile 2");
        s.createRow(8).createCell(9).setCellValue("");

        String out = convert(wb, "t.xlsx", ConvertOptions.defaults());

        assertThat(out).contains("<tr><th>Kopf</th></tr>\n<tr><td>Zeile 1<br/>Zeile 2</td></tr>\n</tbody>");
        assertWellFormed(out);
    }

    @Test
    void hyperlinksBecomeAnchors() throws Exception {
        Workbook wb = new XSSFWorkbook();
        Sheet s = wb.createSheet("L");
        set(s, 0, 0, "Link");
        Cell c = s.createRow(1).createCell(0);
        c.setCellValue("Beispiel");
        Hyperlink link = wb.getCreationHelper().createHyperlink(HyperlinkType.URL);
        link.setAddress("https://example.com/?a=1&b=2");
        c.setHyperlink(link);
        Cell m = s.createRow(2).createCell(0);
        m.setCellValue("Mail");
        Hyperlink mail = wb.getCreationHelper().createHyperlink(HyperlinkType.EMAIL);
        mail.setAddress("mailto:info@example.com");
        m.setHyperlink(mail);
        Cell d = s.createRow(3).createCell(0);
        d.setCellValue("intern");
        Hyperlink doc = wb.getCreationHelper().createHyperlink(HyperlinkType.DOCUMENT);
        doc.setAddress("'L'!A1");
        d.setHyperlink(doc);

        String out = convert(wb, "l.xlsx", ConvertOptions.defaults());

        assertThat(out).contains("<td><a href=\"https://example.com/?a=1&amp;b=2\">Beispiel</a></td>",
                "<td><a href=\"mailto:info@example.com\">Mail</a></td>", "<td>intern</td>");
        assertWellFormed(out);
    }

    @Test
    void hiddenSheetsSkippedUnlessRequested() throws Exception {
        Workbook wb = new XSSFWorkbook();
        set(wb.createSheet("Sichtbar"), 0, 0, "v");
        set(wb.createSheet("Versteckt"), 0, 0, "h");
        wb.setSheetVisibility(1, SheetVisibility.HIDDEN);
        Path file = save(wb, "h.xlsx");

        assertThat(converter.toStorage(file, ConvertOptions.defaults()))
                .contains("<h2>Sichtbar</h2>").doesNotContain("Versteckt");
        assertThat(converter.toStorage(file, new ConvertOptions(List.of(), true, true, null)))
                .contains("<h2>Sichtbar</h2>", "<h2>Versteckt</h2>");
        assertThat(converter.toStorage(file, new ConvertOptions(List.of("versteckt"), false, true, null)))
                .contains("<h2>Versteckt</h2>").doesNotContain("Sichtbar");
    }

    @Test
    void sheetFilterKeepsRequestedOrderAndReportsUnknownSheets() throws Exception {
        Workbook wb = new XSSFWorkbook();
        set(wb.createSheet("A"), 0, 0, "a");
        set(wb.createSheet("B"), 0, 0, "b");
        set(wb.createSheet("C"), 0, 0, "c");
        Path file = save(wb, "f.xlsx");

        String out = converter.toStorage(file, new ConvertOptions(List.of("C", "A"), false, true, null));
        assertThat(out).doesNotContain("<h2>B</h2>");
        assertThat(out.indexOf("<h2>C</h2>")).isLessThan(out.indexOf("<h2>A</h2>"));

        assertThatThrownBy(() -> converter.toStorage(file, new ConvertOptions(List.of("X"), false, true, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("«X» nicht gefunden")
                .hasMessageContaining("A, B, C");
    }

    @Test
    void emptySheetsSkippedAndNoteWhenNothingLeft() throws Exception {
        Workbook wb = new XSSFWorkbook();
        wb.createSheet("Leer");
        set(wb.createSheet("Daten"), 0, 0, "x");
        assertThat(convert(wb, "e.xlsx", ConvertOptions.defaults()))
                .doesNotContain("Leer").contains("<h2>Daten</h2>");

        Workbook empty = new XSSFWorkbook();
        empty.createSheet("Leer");
        assertThat(convert(empty, "e2.xlsx", ConvertOptions.defaults())).isEqualTo("<p>Keine Daten gefunden.</p>\n");
    }

    @Test
    void readsLegacyXls() throws Exception {
        Workbook wb = new HSSFWorkbook();
        Sheet s = wb.createSheet("Alt");
        set(s, 0, 0, "Kopf");
        set(s, 0, 1, "Wert");
        set(s, 1, 0, "Ärger");
        s.getRow(1).createCell(1).setCellFormula("1/4");
        s.getRow(1).getCell(1).setCellStyle(style(wb, "0%"));
        s.addMergedRegion(new CellRangeAddress(2, 2, 0, 1));
        set(s, 2, 0, "Summe");

        String out = convert(wb, "alt.xls", ConvertOptions.defaults());

        assertThat(out).contains("<h2>Alt</h2>", "<td>Ärger</td><td>25%</td>", "<td colspan=\"2\">Summe</td>");
        assertWellFormed(out);
    }

    @Test
    void invalidFileGivesIoException() throws Exception {
        Path file = dir.resolve("kaputt.xlsx");
        Files.writeString(file, "keine Tabelle");
        assertThatThrownBy(() -> converter.toStorage(file, ConvertOptions.defaults()))
                .isInstanceOf(IOException.class);
    }

    @Test
    void systemDateTimeLongDateAndTimeFormatsFollowLocale() throws Exception {
        Workbook wb = new XSSFWorkbook();
        Sheet s = wb.createSheet("D");
        set(s, 0, 0, "Wert");
        Object[][] rows = {
                {java.time.LocalDateTime.of(2026, 3, 15, 14, 30), (short) 22},
                {LocalDate.of(2026, 3, 15), "[$-F800]dddd\\,\\ mmmm\\ dd\\,\\ yyyy"},
                {java.time.LocalDateTime.of(2026, 3, 15, 14, 30), "[$-F400]h:mm:ss\\ AM/PM"}};
        for (int i = 0; i < rows.length; i++) {
            Cell c = s.createRow(i + 1).createCell(0);
            if (rows[i][0] instanceof LocalDate d) {
                c.setCellValue(d);
            } else {
                c.setCellValue((java.time.LocalDateTime) rows[i][0]);
            }
            CellStyle st = rows[i][1] instanceof Short n ? wb.createCellStyle() : style(wb, (String) rows[i][1]);
            if (rows[i][1] instanceof Short n) {
                st.setDataFormat(n);
            }
            c.setCellStyle(st);
        }
        Path file = save(wb, "d.xlsx");

        assertThat(new ExcelConverter(Locale.forLanguageTag("de-CH")).toStorage(file, ConvertOptions.defaults()))
                .contains("<td>15.03.2026 14:30</td>", "<td>Sonntag, 15. März 2026</td>", "<td>14:30:00</td>");
    }

    @Test
    void hyperlinkFormulasBecomeAnchors() throws Exception {
        Workbook wb = new XSSFWorkbook();
        Sheet s = wb.createSheet("L");
        set(s, 0, 0, "Link");
        s.createRow(1).createCell(0).setCellFormula("HYPERLINK(\"https://example.ch/a?b=1&c=2\",\"Beispiel-Link\")");
        set(s, 2, 1, "https://example.ch/ziel");
        s.getRow(2).createCell(0).setCellFormula("HYPERLINK(B3,\"Ziel\")");
        s.createRow(3).createCell(0).setCellFormula("HYPERLINK(\"javascript:alert(1)\",\"Böse\")");

        String out = convert(wb, "l.xlsx", ConvertOptions.defaults());

        assertThat(out).contains("<td><a href=\"https://example.ch/a?b=1&amp;c=2\">Beispiel-Link</a></td>",
                "<td><a href=\"https://example.ch/ziel\">Ziel</a></td>", "<td>Böse</td>");
        assertWellFormed(out);
    }

    @Test
    void hiddenRowsAndColumnsAreSkippedUnlessRequested() throws Exception {
        Workbook wb = new XSSFWorkbook();
        Sheet s = wb.createSheet("H");
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                set(s, r, c, (char) ('A' + c) + String.valueOf(r + 1));
            }
        }
        set(s, 1, 1, "GEHEIM");
        s.setColumnHidden(1, true);
        s.getRow(2).setZeroHeight(true);
        s.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));
        Path file = save(wb, "h.xlsx");

        String out = converter.toStorage(file, ConvertOptions.defaults());
        assertThat(out).doesNotContain("GEHEIM", "A3", "B1")
                .contains("<tr><th colspan=\"2\">A1</th></tr>", "<tr><td>A2</td><td>C2</td></tr>");

        assertThat(converter.toStorage(file, new ConvertOptions(List.of(), true, true, null)))
                .contains("GEHEIM", "A3");
    }

    @Test
    void mergedRegionOverTheWholeSheetDoesNotBlowUp() throws Exception {
        Workbook wb = new XSSFWorkbook();
        Sheet s = wb.createSheet("M");
        set(s, 0, 0, "Titel");
        set(s, 1, 0, "a");
        set(s, 1, 1, "b");
        s.addMergedRegion(new CellRangeAddress(0, 0, 0, 16_383)); // title across the whole row
        Sheet all = wb.createSheet("Alles");
        set(all, 0, 0, "Alles");
        all.addMergedRegion(new CellRangeAddress(0, 99_999, 0, 16_383)); // 1.6e9 cells

        long start = System.nanoTime();
        String out = convert(wb, "m.xlsx", ConvertOptions.defaults());

        assertThat(out).contains("<tr><th colspan=\"2\">Titel</th></tr>", "<tr><td>a</td><td>b</td></tr>",
                "<tr><th>Alles</th></tr>");
        assertThat(System.nanoTime() - start).isLessThan(10_000_000_000L);
    }

    @Test
    void farAwayCellFailsWithClearMessageInsteadOfRunningOutOfMemory() throws Exception {
        Workbook wb = new XSSFWorkbook();
        Sheet s = wb.createSheet("F");
        set(s, 0, 0, "a");
        set(s, 1_048_575, 16_383, "z");

        assertThatThrownBy(() -> convert(wb, "f.xlsx", ConvertOptions.defaults()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Tabelle zu gross");
    }

    // --- helpers

    private String convert(Workbook wb, String name, ConvertOptions options) throws IOException {
        return converter.toStorage(save(wb, name), options);
    }

    private Path save(Workbook wb, String name) throws IOException {
        Path file = dir.resolve(name);
        try (OutputStream os = Files.newOutputStream(file); wb) {
            wb.write(os);
        }
        return file;
    }

    private static void set(Sheet s, int row, int col, String value) {
        Row r = s.getRow(row) != null ? s.getRow(row) : s.createRow(row);
        r.createCell(col).setCellValue(value);
    }

    private static CellStyle style(Workbook wb, String format) {
        CellStyle st = wb.createCellStyle();
        st.setDataFormat(wb.createDataFormat().getFormat(format));
        return st;
    }

    static void assertWellFormed(String fragment) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.newDocumentBuilder().parse(new ByteArrayInputStream(
                ("<root>" + fragment + "</root>").getBytes(StandardCharsets.UTF_8)));
    }
}
