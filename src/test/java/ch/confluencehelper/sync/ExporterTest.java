package ch.confluencehelper.sync;

import ch.confluencehelper.client.PageRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExporterTest {

    static final Instant NOW = Instant.parse("2026-10-30T08:00:00Z");

    @TempDir
    Path tmp;

    FakePageApi api = new FakePageApi();
    Exporter exporter;

    @BeforeEach
    void setUp() {
        exporter = new Exporter(api, Clock.fixed(NOW, ZoneOffset.UTC), s -> { });
        api.add("1", "DOC", "Startseite", null, 4, "<p>Start\r\n</p>");
        api.add("2", "DOC", "Kapitel Ä", "1", 1, "<p>K</p>");
        api.add("3", "DOC", "Unterkapitel", "2", 2, "<p>U</p>");
    }

    @Test
    void exportsSinglePageByteExact() throws Exception {
        List<Exporter.Result> r = exporter.export(PageRef.ofId("1"), tmp, Exporter.Options.pageOnly());

        Path dir = tmp.resolve("1-startseite");
        assertThat(r).singleElement().satisfies(x -> assertThat(x.dir()).isEqualTo(dir));
        assertThat(Files.readAllBytes(dir.resolve("page.xhtml")))
                .isEqualTo("<p>Start\r\n</p>".getBytes(StandardCharsets.UTF_8));
        assertThat(PageStore.readMeta(dir.resolve("page.json"))).contains(new PageMeta("1", "Startseite", "DOC", 4,
                null, "https://wiki.example.ch", "https://wiki.example.ch/pages/viewpage.action?pageId=1", NOW,
                PageStore.contentHash("<p>Start\n</p>"), Map.of()));
        assertThat(dir.resolve("attachments")).doesNotExist();
        assertThat(api.calls).noneMatch(c -> c.startsWith("children"));
    }

    @Test
    void exportsChildrenNestedWithDepth() {
        List<Exporter.Result> all = exporter.export(PageRef.ofId("1"), tmp,
                new Exporter.Options(false, true, Integer.MAX_VALUE, false));
        assertThat(all).extracting(Exporter.Result::level).containsExactly(0, 1, 2);
        assertThat(tmp.resolve("1-startseite/2-kapitel-ae/3-unterkapitel/page.xhtml")).hasContent("<p>U</p>");

        List<Exporter.Result> one = exporter.export(PageRef.ofId("1"), tmp.resolve("d1"),
                new Exporter.Options(false, true, 1, false));
        assertThat(one).extracting(x -> x.page().id()).containsExactly("1", "2");
    }

    @Test
    void downloadsAttachmentsWithSanitizedNames() throws Exception {
        api.addAttachment("1", "../../evil.sh", "x".getBytes());
        api.addAttachment("1", "Bericht.pdf", "pdf".getBytes());
        api.addAttachment("1", "bericht.pdf", "pdf2".getBytes());
        api.addAttachment("1", "AUX", "a".getBytes());

        List<Exporter.Result> r = exporter.export(PageRef.ofId("1"), tmp, new Exporter.Options(true, false, 0, false));

        Path att = tmp.resolve("1-startseite/attachments");
        assertThat(r.getFirst().attachments()).isEqualTo(4);
        try (var files = Files.list(att)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .containsExactlyInAnyOrder("evil.sh", "Bericht.pdf", "att1002-bericht.pdf", "_AUX");
        }
        assertThat(tmp.resolve("evil.sh")).doesNotExist();
    }

    @Test
    void refusesToOverwriteUnpushedLocalEdits() throws Exception {
        exporter.export(PageRef.ofId("1"), tmp, Exporter.Options.pageOnly());
        Path xhtml = tmp.resolve("1-startseite/page.xhtml");
        Files.writeString(xhtml, "<p>lokal bearbeitet</p>");

        assertThatThrownBy(() -> exporter.export(PageRef.ofId("1"), tmp, Exporter.Options.pageOnly()))
                .isInstanceOf(SyncException.class).hasMessageContaining("--force");
        assertThat(xhtml).hasContent("<p>lokal bearbeitet</p>");

        exporter.export(PageRef.ofId("1"), tmp, new Exporter.Options(false, false, 0, true));
        assertThat(xhtml).hasContent("<p>Start\r\n</p>");
    }

    @Test
    void reExportAfterServerChangeOverwrites() throws Exception {
        exporter.export(PageRef.ofId("1"), tmp, Exporter.Options.pageOnly());
        api.add("1", "DOC", "Startseite", null, 5, "<p>neu</p>");
        exporter.export(PageRef.ofId("1"), tmp, Exporter.Options.pageOnly());
        assertThat(tmp.resolve("1-startseite/page.xhtml")).hasContent("<p>neu</p>");
        assertThat(PageStore.readMeta(tmp.resolve("1-startseite/page.json")).orElseThrow().version()).isEqualTo(5);
    }

    @Test
    void reExportAfterServerChangeRefusesToDropLocalEdits() throws Exception {
        exporter.export(PageRef.ofId("1"), tmp, Exporter.Options.pageOnly());
        Path xhtml = tmp.resolve("1-startseite/page.xhtml");
        Files.writeString(xhtml, "<p>lokal erweitert</p>");
        api.add("1", "DOC", "Startseite", null, 5, "<p>v5 vom Kollegen</p>");

        assertThatThrownBy(() -> exporter.export(PageRef.ofId("1"), tmp, Exporter.Options.pageOnly()))
                .isInstanceOf(SyncException.class).hasMessageContaining("lokale Änderungen")
                .hasMessageContaining("-o").hasMessageContaining("--force");
        assertThat(xhtml).hasContent("<p>lokal erweitert</p>");
        assertThat(PageStore.readMeta(tmp.resolve("1-startseite/page.json")).orElseThrow().version()).isEqualTo(4);

        exporter.export(PageRef.ofId("1"), tmp, new Exporter.Options(false, false, 0, true));
        assertThat(xhtml).hasContent("<p>v5 vom Kollegen</p>");
        assertThat(tmp.resolve("1-startseite/page.xhtml.bak")).hasContent("<p>lokal erweitert</p>");
    }

    @Test
    void existingFileWithoutPageJsonIsNotOverwritten() throws Exception {
        Path xhtml = tmp.resolve("1-startseite/page.xhtml");
        PageStore.writeXhtml(xhtml, "<p>irgendwas</p>");
        assertThatThrownBy(() -> exporter.export(PageRef.ofId("1"), tmp, Exporter.Options.pageOnly()))
                .isInstanceOf(SyncException.class);
        assertThat(xhtml).hasContent("<p>irgendwas</p>");
    }

    @Test
    void reExportKeepsLocallyChangedAttachments() throws Exception {
        api.addAttachment("1", "bild.png", "png".getBytes());
        Exporter.Options withAttachments = new Exporter.Options(true, false, 0, false);
        exporter.export(PageRef.ofId("1"), tmp, withAttachments);
        Path bild = tmp.resolve("1-startseite/attachments/bild.png");
        Files.writeString(bild, "bearbeitet");

        assertThatThrownBy(() -> exporter.export(PageRef.ofId("1"), tmp, withAttachments))
                .isInstanceOf(SyncException.class).hasMessageContaining("bild.png");
        assertThat(bild).hasContent("bearbeitet");
        try (var files = Files.list(tmp.resolve("1-startseite"))) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.startsWith(".cfh-"));
        }

        exporter.export(PageRef.ofId("1"), tmp, new Exporter.Options(true, false, 0, true));
        assertThat(bild).hasContent("png");
    }

    @Test
    void reExportRemovesAttachmentsDeletedOnTheServer() throws Exception {
        api.addAttachment("1", "vertrag-alt.pdf", "alt".getBytes());
        api.addAttachment("1", "aktuell.pdf", "neu".getBytes());
        Exporter.Options withAttachments = new Exporter.Options(true, false, 0, false);
        exporter.export(PageRef.ofId("1"), tmp, withAttachments);
        Path att = tmp.resolve("1-startseite/attachments");
        Files.writeString(att.resolve("eigene-notiz.txt"), "lokal neu");
        api.attachments.get("1").removeIf(a -> a.title().equals("vertrag-alt.pdf"));

        exporter.export(PageRef.ofId("1"), tmp, withAttachments);

        try (var files = Files.list(att)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .containsExactlyInAnyOrder("aktuell.pdf", "eigene-notiz.txt");
        }
        assertThat(PageStore.readMeta(tmp.resolve("1-startseite/page.json")).orElseThrow().attachments())
                .containsOnlyKeys("aktuell.pdf");
    }

    @Test
    void refusesToExportIntoSymlinkedFolders() throws Exception {
        Path elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        api.addAttachment("1", "x.txt", "x".getBytes());
        Path dir = Files.createDirectories(tmp.resolve("out/1-startseite"));
        Files.createSymbolicLink(dir.resolve("attachments"), elsewhere);

        assertThatThrownBy(() -> exporter.export(PageRef.ofId("1"), tmp.resolve("out"),
                new Exporter.Options(true, false, 0, false))).isInstanceOf(SyncException.class)
                .hasMessageContaining("symbolischer Link");
        try (var files = Files.list(elsewhere)) {
            assertThat(files).isEmpty();
        }

        Files.createSymbolicLink(elsewhere.resolve("1-startseite"), Files.createDirectories(tmp.resolve("ziel")));
        assertThatThrownBy(() -> exporter.export(PageRef.ofId("1"), elsewhere, Exporter.Options.pageOnly()))
                .isInstanceOf(SyncException.class);
    }
}
