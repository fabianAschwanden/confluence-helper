package ch.confluencehelper.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageStoreTest {

    @TempDir
    Path tmp;

    @Test
    void xhtmlIsWrittenByteExact() throws Exception {
        String storage = "<p>Grüsse\r\n&nbsp;<ac:structured-macro ac:name=\"info\"/></p>\n\n  ";
        Path f = tmp.resolve("a/page.xhtml");
        PageStore.writeXhtml(f, storage);
        assertThat(Files.readAllBytes(f)).isEqualTo(storage.getBytes(StandardCharsets.UTF_8));
        assertThat(PageStore.readXhtml(f)).isEqualTo(storage);
        try (var s = Files.list(f.getParent())) {
            assertThat(s).hasSize(1); // no temp files left behind
        }
    }

    @Test
    void readDropsBom() throws Exception {
        Path f = tmp.resolve("page.xhtml");
        Files.writeString(f, "﻿<p>x</p>", StandardCharsets.UTF_8);
        assertThat(PageStore.readXhtml(f)).isEqualTo("<p>x</p>");
    }

    @Test
    void metaRoundTrip() throws Exception {
        PageMeta meta = new PageMeta("123", "Titel «ä»", "DOC", 7, null, "https://wiki.example.ch",
                "https://wiki.example.ch/x", Instant.parse("2026-10-30T12:34:56.789Z"));
        Path f = tmp.resolve("page.json");
        PageStore.writeMeta(f, meta);
        assertThat(PageStore.readMeta(f)).contains(meta);
        assertThat(Files.readString(f)).contains("\"exportedAt\" : \"2026-10-30T12:34:56.789Z\"")
                .contains("\"parentId\" : null");
    }

    @Test
    void metaToleratesMissingAndUnknownFields() throws Exception {
        Path f = tmp.resolve("page.json");
        Files.writeString(f, "{\"id\":\"5\",\"version\":2,\"extra\":true}");
        assertThat(PageStore.readMeta(f)).contains(new PageMeta("5", null, null, 2, null, null, null, null));
        assertThat(PageStore.readMeta(tmp.resolve("missing.json"))).isEmpty();
    }

    @Test
    void invalidMetaIsReported() throws Exception {
        Path f = tmp.resolve("page.json");
        Files.writeString(f, "{kaputt");
        assertThatThrownBy(() -> PageStore.readMeta(f)).isInstanceOf(SyncException.class)
                .hasMessageContaining("keine gültige page.json");
    }

    @Test
    void locateFolderAndFile() throws Exception {
        Path dir = tmp.resolve("1-x");
        PageStore.writeXhtml(dir.resolve("page.xhtml"), "<p/>");
        PageStore.Location byDir = PageStore.locate(dir);
        assertThat(byDir.xhtml()).isEqualTo(dir.resolve("page.xhtml").toAbsolutePath());
        assertThat(byDir.meta()).isEqualTo(dir.resolve("page.json").toAbsolutePath());

        Path file = tmp.resolve("entwurf.xhtml");
        Files.writeString(file, "<p/>");
        PageStore.Location byFile = PageStore.locate(file);
        assertThat(byFile.xhtml()).isEqualTo(file.toAbsolutePath());
        assertThat(byFile.meta()).isEqualTo(tmp.resolve("page.json").toAbsolutePath());

        assertThatThrownBy(() -> PageStore.locate(tmp.resolve("leer"))).isInstanceOf(SyncException.class);
        Files.createDirectory(tmp.resolve("leer"));
        assertThatThrownBy(() -> PageStore.locate(tmp.resolve("leer")))
                .hasMessageContaining("keine page.xhtml");
    }

    @Test
    void requireMetaGivesHint() throws Exception {
        Path file = tmp.resolve("page.xhtml");
        Files.writeString(file, "<p/>");
        assertThatThrownBy(() -> PageStore.requireMeta(PageStore.locate(file)))
                .hasMessageContaining("cfh export").hasMessageContaining("cfh create");
    }
}
