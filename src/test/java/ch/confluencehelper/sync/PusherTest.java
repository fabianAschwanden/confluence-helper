package ch.confluencehelper.sync;

import ch.confluencehelper.client.ConfluenceException;
import ch.confluencehelper.client.PageRef;
import ch.confluencehelper.storage.Problem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PusherTest {

    static final Instant NOW = Instant.parse("2026-10-30T09:00:00Z");

    @TempDir
    Path tmp;

    FakePageApi api = new FakePageApi();
    List<Problem> problems = List.of();
    Function<String, List<Problem>> validator = s -> problems;
    Path dir;

    @BeforeEach
    void setUp() {
        api.add("7", "DOC", "Handbuch", "1", 3, "<p>alt</p>\n");
        new Exporter(api).export(PageRef.ofId("7"), tmp, Exporter.Options.pageOnly());
        dir = tmp.resolve("7-handbuch");
    }

    Pusher pusher() {
        return new Pusher(api, validator, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static Pusher.Options opts() {
        return new Pusher.Options(null, "via cfh", false, false, false);
    }

    @Test
    void pushesNewVersionAndUpdatesMeta() throws Exception {
        Files.writeString(dir.resolve("page.xhtml"), "<p>neu</p>\n<p>mehr</p>\n");

        Pusher.Result r = pusher().push(dir, opts());

        assertThat(r.status()).isEqualTo(Pusher.Status.UPDATED);
        assertThat(r.newVersion()).isEqualTo(4);
        assertThat(r.stats()).isEqualTo(new Differ.Stats(2, 1));
        assertThat(api.storage("7")).isEqualTo("<p>neu</p>\n<p>mehr</p>\n");
        assertThat(api.calls).contains("updatePage 7 v4 Handbuch via cfh");
        PageMeta meta = PageStore.readMeta(dir.resolve("page.json")).orElseThrow();
        assertThat(meta.version()).isEqualTo(4);
        assertThat(meta.exportedAt()).isEqualTo(NOW);
        assertThat(meta.parentId()).isEqualTo("1");
    }

    @Test
    void noChangeSkipsPut() throws Exception {
        Files.writeString(dir.resolve("page.xhtml"), "<p>alt</p>\r\n"); // only line endings differ
        Pusher.Result r = pusher().push(dir.resolve("page.xhtml"), opts());
        assertThat(r.status()).isEqualTo(Pusher.Status.NO_CHANGE);
        assertThat(api.calls).noneMatch(c -> c.startsWith("updatePage"));
    }

    @Test
    void titleChangeAloneIsPushed() {
        Pusher.Result r = pusher().push(dir, new Pusher.Options("Handbuch 2", null, false, false, false));
        assertThat(r.status()).isEqualTo(Pusher.Status.UPDATED);
        assertThat(r.titleChanged()).isTrue();
        assertThat(api.pages.get("7").title()).isEqualTo("Handbuch 2");
        assertThat(PageStore.readMeta(dir.resolve("page.json")).orElseThrow().title()).isEqualTo("Handbuch 2");
    }

    @Test
    void versionConflictAbortsUnlessForced() throws Exception {
        Files.writeString(dir.resolve("page.xhtml"), "<p>lokal</p>");
        api.add("7", "DOC", "Handbuch", "1", 5, "<p>fremd</p>");

        assertThatThrownBy(() -> pusher().push(dir, opts()))
                .isInstanceOfSatisfying(VersionConflictException.class, e -> {
                    assertThat(e.exitCode()).isEqualTo(3);
                    assertThat(e.serverVersion()).isEqualTo(5);
                    assertThat(e.getMessage()).contains("cfh diff").contains("--force");
                });
        assertThat(api.storage("7")).isEqualTo("<p>fremd</p>");

        Pusher.Result r = pusher().push(dir, new Pusher.Options(null, null, false, true, false));
        assertThat(r.newVersion()).isEqualTo(6);
        assertThat(api.storage("7")).isEqualTo("<p>lokal</p>");
    }

    @Test
    void validationErrorsAbortBeforeAnyServerCall() throws Exception {
        problems = List.of(new Problem(Problem.Severity.ERROR, 1, 5, "kaputt"));
        api.calls.clear();
        assertThatThrownBy(() -> pusher().push(dir, opts()))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> {
                    assertThat(e.exitCode()).isEqualTo(2);
                    assertThat(e.problems()).hasSize(1);
                });
        assertThat(api.calls).isEmpty();
    }

    @Test
    void warningsDoNotBlock() throws Exception {
        problems = List.of(new Problem(Problem.Severity.WARNING, 0, 0, "Hinweis"));
        Files.writeString(dir.resolve("page.xhtml"), "<p>neu</p>");
        assertThat(pusher().push(dir, opts()).warnings()).hasSize(1);
    }

    @Test
    void dryRunChangesNothing() throws Exception {
        Files.writeString(dir.resolve("page.xhtml"), "<p>neu</p>\n");
        String metaBefore = Files.readString(dir.resolve("page.json"));

        Pusher.Result r = pusher().push(dir, new Pusher.Options(null, null, true, false, false));

        assertThat(r.status()).isEqualTo(Pusher.Status.DRY_RUN);
        assertThat(r.newVersion()).isEqualTo(4);
        assertThat(r.stats()).isEqualTo(new Differ.Stats(1, 1));
        assertThat(api.storage("7")).isEqualTo("<p>alt</p>\n");
        assertThat(dir.resolve("page.json")).hasContent(metaBefore);
    }

    @Test
    void uploadsNewOrChangedAttachmentsOnly() throws Exception {
        api.addAttachment("7", "gleich.txt", "abc".getBytes());
        api.addAttachment("7", "anders.txt", "abc".getBytes());
        api.addAttachment("7", "gleichlang.txt", "Meier".getBytes());
        Path att = Files.createDirectories(dir.resolve("attachments"));
        Files.writeString(att.resolve("gleich.txt"), "abc");
        Files.writeString(att.resolve("anders.txt"), "abcdef");
        Files.writeString(att.resolve("gleichlang.txt"), "Meyer"); // same size, other content
        Files.writeString(att.resolve("neu.png"), "png");
        Files.writeString(att.resolve(".DS_Store"), "x");

        Pusher.Result dry = pusher().push(dir, new Pusher.Options(null, null, true, false, true));
        assertThat(dry.uploads()).extracting(p -> p.getFileName().toString())
                .containsExactly("anders.txt", "gleichlang.txt", "neu.png");
        assertThat(api.calls).noneMatch(c -> c.startsWith("upload"));

        Pusher.Result r = pusher().push(dir, new Pusher.Options(null, null, false, false, true));
        assertThat(r.status()).isEqualTo(Pusher.Status.NO_CHANGE);
        assertThat(api.calls).filteredOn(c -> c.startsWith("upload"))
                .containsExactly("upload 7 anders.txt #att1001", "upload 7 gleichlang.txt #att1002", "upload 7 neu.png");
        assertThat(api.attachments.get("7")).hasSize(4);

        // recorded in page.json: a second push uploads nothing
        api.calls.clear();
        assertThat(pusher().push(dir, new Pusher.Options(null, null, false, false, true)).uploads()).isEmpty();
    }

    @Test
    void warnsAboutReferencedAttachmentsThatWouldBeMissingOnTheServer() throws Exception {
        api.addAttachment("7", "da.png", "png".getBytes());
        Files.writeString(dir.resolve("page.xhtml"), """
                <p>neu</p>
                <ac:image><ri:attachment ri:filename="da.png" /></ac:image>
                <ac:image><ri:attachment ri:filename="netzplan.png" /></ac:image>
                <ac:link><ri:attachment ri:filename="fehlt.pdf" /></ac:link>
                <ac:link><ri:attachment ri:filename="fremd.pdf"><ri:page ri:content-title="Andere" /></ri:attachment></ac:link>
                """);
        Files.createDirectories(dir.resolve("attachments"));
        Files.writeString(dir.resolve("attachments/netzplan.png"), "png");

        Pusher.Result dry = pusher().push(dir, new Pusher.Options(null, null, true, false, false));
        assertThat(dry.warnings()).extracting(Problem::message).satisfiesExactly(
                m -> assertThat(m).contains("«netzplan.png»", "--attachments"),
                m -> assertThat(m).contains("«fehlt.pdf»", "nicht hochgeladen"));
        assertThat(dry.warnings()).allMatch(w -> !w.isError()).extracting(Problem::line).containsExactly(3, 4);

        // uploaded in the same run: only the file that exists nowhere is left
        Pusher.Result withUpload = pusher().push(dir, new Pusher.Options(null, null, true, false, true));
        assertThat(withUpload.warnings()).extracting(Problem::message).singleElement().asString().contains("«fehlt.pdf»");
    }

    @Test
    void noAttachmentLookupWithoutReferences() throws Exception {
        Files.writeString(dir.resolve("page.xhtml"), "<p>neu</p>\n");
        assertThat(pusher().push(dir, new Pusher.Options(null, null, true, false, false)).warnings()).isEmpty();
        assertThat(api.calls).noneMatch(c -> c.startsWith("attachments"));
    }

    @Test
    void exportedAttachmentsWithSanitizedNamesUpdateTheOriginal() throws Exception {
        String nfd = java.text.Normalizer.normalize("Zürich.pdf", java.text.Normalizer.Form.NFD);
        api.addAttachment("7", "Plan: Q3.pdf", "plan".getBytes());
        api.addAttachment("7", nfd, "zh".getBytes());
        new Exporter(api).export(PageRef.ofId("7"), tmp, new Exporter.Options(true, false, 0, false));
        Path att = dir.resolve("attachments");
        assertThat(att.resolve("Plan_ Q3.pdf")).hasContent("plan");

        api.calls.clear();
        assertThat(pusher().push(dir, new Pusher.Options(null, null, false, false, true)).uploads()).isEmpty();

        Files.writeString(att.resolve("Plan_ Q3.pdf"), "PLAN");
        Files.writeString(att.resolve("Zürich.pdf"), "ZH");
        pusher().push(dir, new Pusher.Options(null, null, false, false, true));

        assertThat(api.calls).filteredOn(c -> c.startsWith("upload")).containsExactlyInAnyOrder(
                "upload 7 Plan_ Q3.pdf als Plan: Q3.pdf #att1000", "upload 7 Zürich.pdf als " + nfd + " #att1001");
        assertThat(api.attachments.get("7")).extracting(a -> a.title()).containsExactly("Plan: Q3.pdf", nfd);
        assertThat(api.attachmentData.get("att1000")).isEqualTo("PLAN".getBytes());
    }

    @Test
    void attachmentDeletedOnServerIsNotUploadedAgain() throws Exception {
        api.addAttachment("7", "vertrag-alt.pdf", "alt".getBytes());
        new Exporter(api).export(PageRef.ofId("7"), tmp, new Exporter.Options(true, false, 0, false));
        api.attachments.get("7").clear();

        pusher().push(dir, new Pusher.Options(null, null, false, false, true));

        assertThat(api.calls).noneMatch(c -> c.startsWith("upload"));
        assertThat(api.attachments.get("7")).isEmpty();
    }

    @Test
    void symlinkedAttachmentsAreNeverUploaded() throws Exception {
        Path secret = Files.writeString(tmp.resolve("id_rsa"), "PRIVATE KEY");
        Path att = Files.createDirectories(dir.resolve("attachments"));
        Files.createSymbolicLink(att.resolve("notes.txt"), secret);

        Pusher.Result r = pusher().push(dir, new Pusher.Options(null, null, false, false, true));

        assertThat(r.uploads()).isEmpty();
        assertThat(api.calls).noneMatch(c -> c.startsWith("upload"));
    }

    @Test
    void serverNormalisedStorageIsWrittenBackSoTheNextPushIsNoChange() throws Exception {
        api.normalizer = s -> s.replace("<br>", "<br />");
        Files.writeString(dir.resolve("page.xhtml"), "<p>a<br>b</p>");

        assertThat(pusher().push(dir, opts()).status()).isEqualTo(Pusher.Status.UPDATED);
        assertThat(dir.resolve("page.xhtml")).hasContent("<p>a<br />b</p>");

        api.calls.clear();
        assertThat(pusher().push(dir, opts()).status()).isEqualTo(Pusher.Status.NO_CHANGE);
        assertThat(api.calls).noneMatch(c -> c.startsWith("updatePage"));
        // and a refreshing export sees no local edits
        new Exporter(api).export(PageRef.ofId("7"), tmp, Exporter.Options.pageOnly());
    }

    @Test
    void responseWithoutBodyIsFetchedAgain() throws Exception {
        api.normalizer = s -> s.replace("<br>", "<br />");
        api.responseWithBody = false;
        Files.writeString(dir.resolve("page.xhtml"), "<p>a<br>b</p>");

        pusher().push(dir, opts());

        assertThat(dir.resolve("page.xhtml")).hasContent("<p>a<br />b</p>");
        assertThat(PageStore.readMeta(dir.resolve("page.json")).orElseThrow().contentHash())
                .isEqualTo(PageStore.contentHash("<p>a<br />b</p>"));
    }

    @Test
    void refusesToPushToAnotherServerEvenWithForce() throws Exception {
        PageMeta m = PageStore.readMeta(dir.resolve("page.json")).orElseThrow();
        PageStore.writeMeta(dir.resolve("page.json"), new PageMeta(m.id(), m.title(), m.spaceKey(), m.version(),
                m.parentId(), "https://prod.example.ch", m.webUrl(), m.exportedAt(), m.contentHash(), Map.of()));
        Files.writeString(dir.resolve("page.xhtml"), "<p>neu</p>");
        api.calls.clear();

        for (boolean force : new boolean[] {false, true}) {
            assertThatThrownBy(() -> pusher().push(dir, new Pusher.Options(null, null, false, force, false)))
                    .isInstanceOf(SyncException.class)
                    .hasMessageContaining("https://prod.example.ch").hasMessageContaining("https://wiki.example.ch")
                    .hasMessageContaining("--other-server");
        }
        assertThat(api.calls).noneMatch(c -> c.startsWith("updatePage") || c.startsWith("getPage"));

        pusher().push(dir, new Pusher.Options(null, null, false, false, false, true));
        assertThat(api.storage("7")).isEqualTo("<p>neu</p>");
        assertThat(PageStore.readMeta(dir.resolve("page.json")).orElseThrow().baseUrl()).isEqualTo("https://wiki.example.ch");
    }

    @Test
    void sameServerWithTrailingSlashOrDefaultPortIsAccepted() {
        assertThat(PageStore.normalizeBaseUrl("HTTPS://Wiki.Example.ch:443/confluence/"))
                .isEqualTo(PageStore.normalizeBaseUrl("https://wiki.example.ch/confluence"));
        assertThat(PageStore.normalizeBaseUrl("https://wiki.example.ch:8443"))
                .isNotEqualTo(PageStore.normalizeBaseUrl("https://wiki.example.ch"));
    }

    @Test
    void refusesWhenThePageLivesInAnotherSpace() throws Exception {
        api.add("7", "HR", "Handbuch", "1", 3, "<p>alt</p>\n");
        Files.writeString(dir.resolve("page.xhtml"), "<p>neu</p>");

        assertThatThrownBy(() -> pusher().push(dir, opts())).isInstanceOf(SyncException.class)
                .hasMessageContaining("Space DOC").hasMessageContaining("HR");
        assertThat(api.calls).noneMatch(c -> c.startsWith("updatePage"));
    }

    @Test
    void failedUploadAfterPutStillRecordsTheNewVersion() throws Exception {
        Files.writeString(dir.resolve("page.xhtml"), "<p>neu</p>");
        Files.createDirectories(dir.resolve("attachments"));
        Files.writeString(dir.resolve("attachments/gross.zip"), "zip");
        api.uploadFailure = new ConfluenceException(413, "Anhang zu gross");

        assertThatThrownBy(() -> pusher().push(dir, new Pusher.Options(null, null, false, false, true)))
                .isInstanceOf(SyncException.class)
                .hasMessageContaining("Version 4 gespeichert").hasMessageContaining("gross.zip")
                .hasMessageContaining("HTTP 413");
        assertThat(PageStore.readMeta(dir.resolve("page.json")).orElseThrow().version()).isEqualTo(4);

        // retry: no version conflict with our own push, only the attachment is uploaded
        Pusher.Result r = pusher().push(dir, new Pusher.Options(null, null, false, false, true));
        assertThat(r.status()).isEqualTo(Pusher.Status.NO_CHANGE);
        assertThat(api.calls).filteredOn(c -> c.startsWith("upload")).hasSize(2);
    }

    @Test
    void lostResponseOfACommittedPutIsRecovered() throws Exception {
        Files.writeString(dir.resolve("page.xhtml"), "<p>neu</p>");
        api.afterUpdateFailure = new ConfluenceException("Zeitüberschreitung bei der Verbindung zu wiki.example.ch",
                new java.net.http.HttpTimeoutException("request timed out"));

        Pusher.Result r = pusher().push(dir, opts());

        assertThat(r.status()).isEqualTo(Pusher.Status.UPDATED);
        assertThat(PageStore.readMeta(dir.resolve("page.json")).orElseThrow().version()).isEqualTo(4);
    }

    @Test
    void newerServerVersionWithIdenticalContentIsAdopted() throws Exception {
        api.add("7", "DOC", "Handbuch", "1", 4, "<p>neu</p>");
        Files.writeString(dir.resolve("page.xhtml"), "<p>neu</p>");

        Pusher.Result r = pusher().push(dir, opts());

        assertThat(r.status()).isEqualTo(Pusher.Status.NO_CHANGE);
        assertThat(PageStore.readMeta(dir.resolve("page.json")).orElseThrow().version()).isEqualTo(4);
    }

    @Test
    void missingMetaGivesHint() throws Exception {
        Path f = tmp.resolve("lose/page.xhtml");
        PageStore.writeXhtml(f, "<p/>");
        assertThatThrownBy(() -> pusher().push(f, opts())).isInstanceOf(SyncException.class)
                .hasMessageContaining("cfh create");
    }

    @Test
    void renameToATitleThatExistsInTheSpaceIsRefusedBeforeWriting() {
        api.add("8", "DOC", "Kontakte", "1", 1, "<p/>");
        assertThatThrownBy(() -> pusher().push(dir, new Pusher.Options("kontakte", null, false, false, false)))
                .isInstanceOfSatisfying(SyncException.class, e -> {
                    assertThat(e.reason()).isEqualTo(SyncException.Reason.TITLE_EXISTS);
                    assertThat(e.getMessage()).contains("«kontakte»", "ID 8");
                });
        assertThat(api.calls).noneMatch(c -> c.startsWith("updatePage"));
        // changing only the case of the own title is fine
        assertThat(pusher().push(dir, new Pusher.Options("HANDBUCH", null, false, false, false)).status())
                .isEqualTo(Pusher.Status.UPDATED);
    }
}
