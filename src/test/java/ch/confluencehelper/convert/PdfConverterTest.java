package ch.confluencehelper.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.InputSource;

class PdfConverterTest {

    private static final PDType1Font REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    @TempDir
    Path tmp;

    private final PdfConverter converter = new PdfConverter();

    @Test
    void headingsParagraphsHyphenationAndUmlauts() throws IOException {
        Pdf pdf = new Pdf();
        Pdf.Page p = pdf.page();
        p.text(REGULAR, 22, 50, 80, "Projektbericht");
        p.text(REGULAR, 11, 50, 115, "Dies ist der erste Absatz mit einer Zusammen-");
        p.text(REGULAR, 11, 50, 128, "arbeit über mehrere Zeilen. Grösse und Übersicht.");
        p.text(REGULAR, 11, 50, 150, "Zweiter Absatz: Bundes-");
        p.text(REGULAR, 11, 50, 163, "Republik bleibt mit Bindestrich; öffnen, ändern.");
        p.text(REGULAR, 15, 50, 195, "Ausgangslage");
        p.text(REGULAR, 11, 50, 220, "Text unter der Unterüberschrift mit A < B & C.");
        p.text(REGULAR, 13, 50, 250, "Details");
        p.text(REGULAR, 11, 50, 270, "Letzter Absatz.");

        String out = convert(pdf, ConvertOptions.defaults());

        assertThat(out.lines().toList()).containsExactly(
                "<h1>Projektbericht</h1>",
                "<p>Dies ist der erste Absatz mit einer Zusammenarbeit über mehrere Zeilen. Grösse und Übersicht.</p>",
                "<p>Zweiter Absatz: Bundes-Republik bleibt mit Bindestrich; öffnen, ändern.</p>",
                "<h2>Ausgangslage</h2>",
                "<p>Text unter der Unterüberschrift mit A &lt; B &amp; C.</p>",
                "<h3>Details</h3>",
                "<p>Letzter Absatz.</p>");
        assertWellFormed(out);
    }

    @Test
    void titleIsPrependedAndHeadingsShifted() throws IOException {
        Pdf pdf = new Pdf();
        Pdf.Page p = pdf.page();
        p.text(REGULAR, 20, 50, 80, "Kapitel");
        p.text(REGULAR, 11, 50, 110, "Inhalt.");

        String out = convert(pdf, new ConvertOptions(List.of(), false, true, "Mein <Titel>"));

        assertThat(out.lines().toList()).containsExactly(
                "<h1>Mein &lt;Titel&gt;</h1>", "<h2>Kapitel</h2>", "<p>Inhalt.</p>");
        assertWellFormed(out);
    }

    @Test
    void bulletAndNumberedLists() throws IOException {
        Pdf pdf = new Pdf();
        Pdf.Page p = pdf.page();
        p.text(REGULAR, 11, 50, 80, "Folgende Punkte:");
        p.text(REGULAR, 11, 60, 93, "• Erster Punkt");
        p.text(REGULAR, 11, 60, 106, "• Zweiter Punkt mit einer langen Fortset-");
        p.text(REGULAR, 11, 70, 119, "zung auf der nächsten Zeile");
        p.text(REGULAR, 11, 60, 132, "- Dritter Punkt");
        p.text(REGULAR, 11, 50, 155, "Schritte:");
        p.text(REGULAR, 11, 60, 168, "1. Vorbereiten");
        p.text(REGULAR, 11, 60, 181, "2) Durchführen");
        p.text(REGULAR, 11, 50, 204, "Schlusssatz.");

        String out = convert(pdf, ConvertOptions.defaults());

        assertThat(out.lines().toList()).containsExactly(
                "<p>Folgende Punkte:</p>",
                "<ul>",
                "<li>Erster Punkt</li>",
                "<li>Zweiter Punkt mit einer langen Fortsetzung auf der nächsten Zeile</li>",
                "<li>Dritter Punkt</li>",
                "</ul>",
                "<p>Schritte:</p>",
                "<ol>",
                "<li>Vorbereiten</li>",
                "<li>Durchführen</li>",
                "</ol>",
                "<p>Schlusssatz.</p>");
        assertWellFormed(out);
    }

    @Test
    void repeatedHeaderFooterAndPageNumbersAreRemoved() throws IOException {
        Pdf pdf = new Pdf();
        for (int i = 1; i <= 3; i++) {
            Pdf.Page p = pdf.page();
            p.text(REGULAR, 9, 50, 40, "Muster AG – Vertraulich");
            p.text(REGULAR, 11, 50, 300, "Inhalt der Seite " + i + ".");
            p.text(REGULAR, 9, 50, 800, "Stand 2024-0" + i + " / Seite " + i + " von 3");
            p.text(REGULAR, 9, 290, 815, String.valueOf(i));
        }

        String out = convert(pdf, ConvertOptions.defaults());

        assertThat(out.lines().toList()).containsExactly(
                "<p>Inhalt der Seite 1.</p>", "<p>Inhalt der Seite 2.</p>", "<p>Inhalt der Seite 3.</p>");
        assertWellFormed(out);
    }

    @Test
    void headerAndFooterOfTwoPageDocumentAreRemoved() throws IOException {
        Pdf pdf = new Pdf();
        for (int i = 1; i <= 2; i++) {
            Pdf.Page p = pdf.page();
            p.text(REGULAR, 11, 50, 40, "Kopfzeile");
            p.text(REGULAR, 11, 50, 300, "Inhalt " + i + ".");
            p.text(REGULAR, 9, 50, 790, "Muster AG · Bahnhofstrasse 1 · 8001 Zürich");
            p.text(REGULAR, 11, 50, 805, "Seite " + i + " von 2");
        }

        String out = convert(pdf, ConvertOptions.defaults());

        assertThat(out.lines().toList()).containsExactly("<p>Inhalt 1.</p>", "<p>Inhalt 2.</p>");
    }

    @Test
    void paragraphContinuesAcrossPageBreak() throws IOException {
        Pdf pdf = new Pdf();
        pdf.page().text(REGULAR, 11, 50, 790, "Dieser Satz beginnt auf der ersten");
        pdf.page().text(REGULAR, 11, 50, 60, "und endet auf der zweiten Seite.");

        String out = convert(pdf, ConvertOptions.defaults());

        assertThat(out.lines().toList()).containsExactly(
                "<p>Dieser Satz beginnt auf der ersten und endet auf der zweiten Seite.</p>");
    }

    @Test
    void shortBoldLineBecomesHeading() throws IOException {
        Pdf pdf = new Pdf();
        Pdf.Page p = pdf.page();
        p.text(REGULAR, 11, 50, 80, "Einleitung mit normalem Text.");
        p.text(BOLD, 11, 50, 105, "Wichtige Hinweise");
        p.text(REGULAR, 11, 50, 125, "Hier folgt der Text zum Abschnitt.");
        p.text(REGULAR, 11, 50, 138, "Satz mit einem ");
        float x = 50 + width(REGULAR, "Satz mit einem ", 11);
        p.text(BOLD, 11, x, 138, "fetten");
        p.text(REGULAR, 11, x + width(BOLD, "fetten", 11), 138, " Wort.");

        String out = convert(pdf, ConvertOptions.defaults());

        assertThat(out.lines().toList()).containsExactly(
                "<p>Einleitung mit normalem Text.</p>",
                "<h1>Wichtige Hinweise</h1>",
                "<p>Hier folgt der Text zum Abschnitt. Satz mit einem fetten Wort.</p>");
        assertWellFormed(out);
    }

    @Test
    void pdfWithoutTextYieldsInfoMacroAndWarning() throws IOException {
        Pdf pdf = new Pdf();
        pdf.page();
        PrintStream original = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String out;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            out = convert(pdf, new ConvertOptions(List.of(), false, true, "Scan"));
        } finally {
            System.setErr(original);
        }

        assertThat(out).startsWith("<h1>Scan</h1>\n<ac:structured-macro ac:name=\"info\">")
                .contains("<ac:rich-text-body>", "OCR");
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Warnung", "OCR");
        assertWellFormed(out);
    }

    @Test
    void encryptedWithEmptyUserPasswordOpens() throws IOException {
        Pdf pdf = new Pdf();
        pdf.page().text(REGULAR, 11, 50, 80, "Geschützter Inhalt.");
        Path file = pdf.save(tmp.resolve("owner.pdf"), "owner-secret", "");

        assertThat(converter.toStorage(file, ConvertOptions.defaults())).isEqualTo("<p>Geschützter Inhalt.</p>\n");
    }

    @Test
    void passwordProtectedPdfFailsWithGermanMessage() throws IOException {
        Pdf pdf = new Pdf();
        pdf.page().text(REGULAR, 11, 50, 80, "Geheim.");
        Path file = pdf.save(tmp.resolve("user.pdf"), "owner-secret", "user-secret");

        assertThatThrownBy(() -> converter.toStorage(file, ConvertOptions.defaults()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("passwortgeschützt");
    }

    @Test
    void joinHandlesHyphenation() {
        StringBuilder sb = new StringBuilder("Zusammen-");
        PdfLayout.join(sb, "arbeit");
        assertThat(sb).hasToString("Zusammenarbeit");
        sb = new StringBuilder("E-Mail und -");
        PdfLayout.join(sb, "weiter");
        assertThat(sb).hasToString("E-Mail und - weiter");
        sb = new StringBuilder("Trenn­");
        PdfLayout.join(sb, "Wort");
        assertThat(sb).hasToString("TrennWort");
    }

    @Test
    void tableRowsAtThePageTopAreNotMistakenForHeaders() throws IOException {
        Pdf pdf = new Pdf();
        int row = 1;
        for (int p = 1; p <= 3; p++) {
            Pdf.Page page = pdf.page();
            page.text(BOLD, 11, 72, 72, "Pos.").text(BOLD, 11, 150, 72, "Artikel").text(BOLD, 11, 400, 72, "Betrag");
            for (int i = 0; i < 5; i++, row++) {
                float y = 88 + i * 14;
                page.text(REGULAR, 11, 72, y, String.valueOf(row)).text(REGULAR, 11, 150, y, "Artikel Nr. " + row)
                        .text(REGULAR, 11, 400, y, row * 10 + ".00");
            }
        }

        String out = convert(pdf, ConvertOptions.defaults());

        for (int i = 1; i <= 15; i++) {
            assertThat(out).contains(i + " Artikel Nr. " + i + " " + i * 10 + ".00");
        }
        assertThat(out).contains("Pos. Artikel Betrag");
    }

    @Test
    void numberedHeadingsAndFirstBodyLinesSurviveButFurnitureGoes() throws IOException {
        Pdf pdf = new Pdf();
        for (int p = 1; p <= 3; p++) {
            Pdf.Page page = pdf.page();
            page.text(REGULAR, 9, 72, 40, "Muster AG – Vertraulich");
            page.text(BOLD, 14, 72, 90, "Kapitel " + p);
            for (int i = 0; i < 4; i++) {
                page.text(REGULAR, 11, 72, 114 + i * 14, "Text auf Seite " + p + " Zeile " + i + ".");
            }
            page.text(REGULAR, 9, 250, 810, "Seite " + p + " von 3");
        }

        String out = convert(pdf, ConvertOptions.defaults());

        assertThat(out).contains("Kapitel 1", "Kapitel 2", "Kapitel 3", "Text auf Seite 2 Zeile 0.",
                "Text auf Seite 3 Zeile 0.").doesNotContain("Vertraulich", "von 3");
    }

    @Test
    void loneNumbersInSinglePageDocumentsAreKept() throws IOException {
        Pdf pdf = new Pdf();
        Pdf.Page p = pdf.page();
        p.text(REGULAR, 11, 72, 60, "4711");
        p.text(REGULAR, 11, 72, 300, "Inhalt.");
        p.text(REGULAR, 11, 72, 800, "2026");

        assertThat(convert(pdf, ConvertOptions.defaults()).lines().toList())
                .containsExactly("<p>4711</p>", "<p>Inhalt.</p>", "<p>2026</p>");
    }

    @Test
    void abbreviationsAndWrappedDatesStayInTheParagraph() throws IOException {
        Pdf pdf = new Pdf();
        Pdf.Page p = pdf.page();
        String[] body = {"Die Gemeinde Zürich prüft die Zusam-", "menarbeit mit externen Partnern, insbesondere die",
                "z. B. für Bauprojekte nötigen Ein-", "und Ausgaben. Gültig ist die Regelung ab dem",
                "1. Januar 2026 für alle Abteilungen der Stadt."};
        for (int i = 0; i < body.length; i++) {
            p.text(REGULAR, 11, 72, 200 + i * 14, body[i]);
        }
        p.text(REGULAR, 11, 72, 300, "a. Erste Variante");
        p.text(REGULAR, 11, 72, 314, "b. Zweite Variante");

        assertThat(convert(pdf, ConvertOptions.defaults()).lines().toList()).containsExactly(
                "<p>Die Gemeinde Zürich prüft die Zusammenarbeit mit externen Partnern, insbesondere die z. B. für "
                        + "Bauprojekte nötigen Ein- und Ausgaben. Gültig ist die Regelung ab dem 1. Januar 2026 für "
                        + "alle Abteilungen der Stadt.</p>",
                "<ol>", "<li>Erste Variante</li>", "<li>Zweite Variante</li>", "</ol>");
    }

    @Test
    void suspendedCompoundsKeepTheirHyphen() throws IOException {
        Pdf pdf = new Pdf();
        Pdf.Page p = pdf.page();
        String[] body = {"Wir planen Ein-", "und Ausgaben sowie Gross-", "oder Kleinschreibung im", "Jahr 2026 neu."};
        for (int i = 0; i < body.length; i++) {
            p.text(REGULAR, 11, 72, 280 + i * 14, body[i]);
        }

        assertThat(convert(pdf, ConvertOptions.defaults()))
                .isEqualTo("<p>Wir planen Ein- und Ausgaben sowie Gross- oder Kleinschreibung im Jahr 2026 neu.</p>\n");
    }

    @Test
    void twoColumnsAreReadOneAfterTheOther() throws IOException {
        Pdf pdf = new Pdf();
        Pdf.Page p = pdf.page();
        p.text(BOLD, 18, 72, 150, "Newsletter Herbst");
        String[] left = {"Linke Spalte beginnt hier", "und erzählt die erste", "Geschichte zu Ende."};
        String[] right = {"Rechte Spalte hat einen", "völlig anderen Inhalt", "über das Budget."};
        for (int i = 0; i < 3; i++) {
            p.text(REGULAR, 11, 72, 200 + i * 14, left[i]);
            p.text(REGULAR, 11, 320, 200 + i * 14, right[i]);
        }
        p.text(REGULAR, 11, 72, 300, "Position").text(REGULAR, 11, 250, 300, "Menge").text(REGULAR, 11, 400, 300, "Preis");

        assertThat(convert(pdf, ConvertOptions.defaults()).lines().toList()).containsExactly(
                "<h1>Newsletter Herbst</h1>",
                "<p>Linke Spalte beginnt hier und erzählt die erste Geschichte zu Ende.</p>",
                "<p>Rechte Spalte hat einen völlig anderen Inhalt über das Budget.</p>",
                "<p>Position Menge Preis</p>");
    }

    @Test
    void officePrivateUseBulletsBecomeListItems() {
        assertThat(PdfLineStripper.normalize("\uf0a7 Erster Punkt")).isEqualTo("• Erster Punkt");
        assertThat(PdfLineStripper.normalize("A\ue123B")).isEqualTo("AB");
        assertThat(PdfLayout.listMatch("\uf0b7 Punkt")).containsExactly(null, "Punkt");

        List<PdfLine> lines = List.of(
                new PdfLine(1, "Einleitungssatz mit Punkt.", 11, false, 72, 422, 842),
                new PdfLine(1, PdfLineStripper.normalize("\uf0a7 Erster Punkt der Liste"), 11, false, 72, 442, 842),
                new PdfLine(1, PdfLineStripper.normalize("\uf0a7 Zweiter Punkt der Liste"), 11, false, 72, 456, 842));
        assertThat(PdfLayout.layout(lines, 1)).containsExactly(
                new PdfLayout.Paragraph("Einleitungssatz mit Punkt."),
                new PdfLayout.ListBlock(false, List.of("Erster Punkt der Liste", "Zweiter Punkt der Liste")));
    }

    @Test
    void titleShiftsHeadingsWithoutMergingLevels() throws IOException {
        Pdf pdf = new Pdf();
        Pdf.Page p = pdf.page();
        p.text(BOLD, 18, 72, 200, "Titel");
        p.text(BOLD, 14, 72, 230, "Abschnitt");
        p.text(BOLD, 12.5f, 72, 255, "Unterabschnitt");
        p.text(REGULAR, 11, 72, 280, "Text.");

        assertThat(convert(pdf, new ConvertOptions(List.of(), false, true, "Bericht")).lines().toList()).containsExactly(
                "<h1>Bericht</h1>", "<h2>Titel</h2>", "<h3>Abschnitt</h3>", "<h4>Unterabschnitt</h4>", "<p>Text.</p>");
    }

    @Test
    void joinKeepsHyphenBeforeConjunction() {
        StringBuilder sb = new StringBuilder("Ein-");
        PdfLayout.join(sb, "und Ausgaben");
        assertThat(sb).hasToString("Ein- und Ausgaben");
        sb = new StringBuilder("Gross-");
        PdfLayout.join(sb, "oder Kleinschreibung");
        assertThat(sb).hasToString("Gross- oder Kleinschreibung");
    }

    // ---- helpers ----

    private String convert(Pdf pdf, ConvertOptions options) throws IOException {
        Path file = pdf.save(tmp.resolve("doc.pdf"), null, null);
        return converter.toStorage(file, options);
    }

    private static float width(PDType1Font font, String text, float size) throws IOException {
        return font.getStringWidth(text) / 1000 * size;
    }

    private static void assertWellFormed(String fragment) {
        String xml = "<root xmlns:ac=\"http://atlassian.com/content\" xmlns:ri=\"http://atlassian.com/resource/identifier\">"
                + fragment + "</root>";
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new AssertionError("Not well-formed: " + e.getMessage() + "\n" + fragment, e);
        }
    }

    /** Minimal in-test PDF builder; y coordinates are measured from the top of an A4 page. */
    private static final class Pdf {
        private final List<Page> pages = new ArrayList<>();

        Page page() {
            Page p = new Page();
            pages.add(p);
            return p;
        }

        Path save(Path file, String ownerPassword, String userPassword) throws IOException {
            try (PDDocument doc = new PDDocument()) {
                for (Page page : pages) {
                    PDPage pdPage = new PDPage(PDRectangle.A4);
                    doc.addPage(pdPage);
                    float height = pdPage.getMediaBox().getHeight();
                    try (PDPageContentStream cs = new PDPageContentStream(doc, pdPage)) {
                        for (Text t : page.texts) {
                            cs.beginText();
                            cs.setFont(t.font, t.size);
                            cs.newLineAtOffset(t.x, height - t.y);
                            cs.showText(t.text);
                            cs.endText();
                        }
                    }
                }
                if (ownerPassword != null) {
                    StandardProtectionPolicy policy =
                            new StandardProtectionPolicy(ownerPassword, userPassword, new AccessPermission());
                    policy.setEncryptionKeyLength(128);
                    doc.protect(policy);
                }
                doc.save(file.toFile());
            }
            return file;
        }

        private record Text(PDType1Font font, float size, float x, float y, String text) {
        }

        static final class Page {
            private final List<Text> texts = new ArrayList<>();

            Page text(PDType1Font font, float size, float x, float y, String text) {
                texts.add(new Text(font, size, x, y, text));
                return this;
            }
        }
    }
}
