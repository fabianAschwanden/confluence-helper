package ch.confluencehelper.sync;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlugsTest {

    @Test
    void slugTransliteratesUmlautsAndAccents() {
        assertThat(Slugs.slug("Übersicht Größe & Prüfung")).isEqualTo("uebersicht-groesse-pruefung");
        assertThat(Slugs.slug("Café Zürich – Ärzte")).isEqualTo("cafe-zuerich-aerzte");
        assertThat(Slugs.slug("Übung")).isEqualTo("uebung"); // decomposed umlaut (macOS)
    }

    @Test
    void slugFallbackAndLength() {
        assertThat(Slugs.slug("!!!")).isEqualTo("seite");
        assertThat(Slugs.slug(null)).isEqualTo("seite");
        String s = Slugs.slug("a".repeat(59) + " bbbbbb");
        assertThat(s).hasSizeLessThanOrEqualTo(60).doesNotEndWith("-").isEqualTo("a".repeat(59));
    }

    @Test
    void folderName() {
        assertThat(Slugs.folderName("12345", "Projekt: Q3/Q4 Planung")).isEqualTo("12345-projekt-q3-q4-planung");
        assertThat(Slugs.folderName("../1", "x")).isEqualTo("1-x");
    }

    @Test
    void safeFileNameBlocksTraversal() {
        assertThat(Slugs.safeFileName("../../etc/passwd", "f")).isEqualTo("passwd");
        assertThat(Slugs.safeFileName("..\\..\\boot.ini", "f")).isEqualTo("boot.ini");
        assertThat(Slugs.safeFileName("..", "f")).isEqualTo("f");
        assertThat(Slugs.safeFileName("/", "f")).isEqualTo("f");
        assertThat(Slugs.safeFileName("", "f")).isEqualTo("f");
    }

    @Test
    void safeFileNameWindowsRules() {
        assertThat(Slugs.safeFileName("CON", "f")).isEqualTo("_CON");
        assertThat(Slugs.safeFileName("nul.txt", "f")).isEqualTo("_nul.txt");
        assertThat(Slugs.safeFileName("com1.tar.gz", "f")).isEqualTo("_com1.tar.gz");
        assertThat(Slugs.safeFileName("console.txt", "f")).isEqualTo("console.txt");
        assertThat(Slugs.safeFileName("a<b>c:d\"e|f?g*h.png", "f")).isEqualTo("a_b_c_d_e_f_g_h.png");
        assertThat(Slugs.safeFileName("report. . ", "f")).isEqualTo("report");
        assertThat(Slugs.safeFileName("tab\there.txt", "f")).isEqualTo("tab_here.txt");
        assertThat(Slugs.safeFileName(".hidden", "f")).isEqualTo("_hidden");
        assertThat(Slugs.safeFileName("Präsentation 2024.pptx", "f")).isEqualTo("Präsentation 2024.pptx");
    }

    @Test
    void safeFileNameKeepsExtensionWhenTruncating() {
        String n = Slugs.safeFileName("x".repeat(300) + ".pdf", "f");
        assertThat(n).hasSize(Slugs.MAX_FILE_NAME).endsWith(".pdf");
    }
}
