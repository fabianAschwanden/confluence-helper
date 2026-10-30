package ch.confluencehelper.sync;

import ch.confluencehelper.storage.Problem;
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

class CreatorTest {

    static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    @TempDir
    Path tmp;

    FakePageApi api = new FakePageApi();
    List<Problem> problems = List.of();
    Function<String, List<Problem>> validator = s -> problems;

    Creator creator() {
        return new Creator(api, validator, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsFromLooseFileAndWritesMetaBesideIt() throws Exception {
        api.add("1", "DOC", "Eltern", null, 1, "<p/>");
        Path file = tmp.resolve("entwurf.xhtml");
        Files.writeString(file, "<p>Hallo</p>");

        Creator.Result r = creator().create(file, "DOC", "Neue Seite", "1");

        assertThat(api.calls).contains("createPage DOC 1 Neue Seite");
        assertThat(api.storage(r.page().id())).isEqualTo("<p>Hallo</p>");
        PageMeta meta = PageStore.readMeta(tmp.resolve("page.json")).orElseThrow();
        assertThat(meta).isEqualTo(new PageMeta(r.page().id(), "Neue Seite", "DOC", 1, "1",
                "https://wiki.example.ch", r.page().webUrl(), NOW, PageStore.contentHash("<p>Hallo</p>"), Map.of()));

        // now linked: push works and a second create is refused
        Files.writeString(file, "<p>Hallo Welt</p>");
        assertThat(new Pusher(api, validator, Clock.systemUTC()).push(file,
                new Pusher.Options(null, null, false, false, false)).newVersion()).isEqualTo(2);
        assertThatThrownBy(() -> creator().create(file, "DOC", "Andere", null))
                .hasMessageContaining("cfh push");
    }

    @Test
    void refusesExistingTitle() throws Exception {
        api.add("9", "DOC", "Gibts schon", null, 1, "<p/>");
        Path dir = tmp.resolve("neu");
        PageStore.writeXhtml(dir.resolve("page.xhtml"), "<p/>");
        assertThatThrownBy(() -> creator().create(dir, "DOC", "Gibts schon", null))
                .isInstanceOf(SyncException.class).hasMessageContaining("bereits").hasMessageContaining("9");
        assertThat(api.calls).noneMatch(c -> c.startsWith("createPage"));
        assertThat(dir.resolve("page.json")).doesNotExist();
    }

    @Test
    void refusesInvalidStorage() throws Exception {
        problems = List.of(new Problem(Problem.Severity.ERROR, 2, 1, "nicht wohlgeformt"));
        Path file = tmp.resolve("page.xhtml");
        Files.writeString(file, "<p>");
        assertThatThrownBy(() -> creator().create(file, "DOC", "T", null))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.exitCode()).isEqualTo(2));
        assertThat(api.calls).isEmpty();
    }

    @Test
    void refusesParentInOtherSpace() throws Exception {
        api.add("5", "OTHER", "Fremd", null, 1, "<p/>");
        Path file = tmp.resolve("page.xhtml");
        Files.writeString(file, "<p/>");
        assertThatThrownBy(() -> creator().create(file, "DOC", "T", "5")).hasMessageContaining("OTHER");
        assertThat(api.calls).noneMatch(c -> c.startsWith("createPage"));
    }

    @Test
    void createIntoWritesPageFolder() {
        Creator.Result r = creator().createInto("<table/>", tmp, "DOC", "Preisliste 2026", null);
        Path dir = tmp.resolve(r.page().id() + "-preisliste-2026");
        assertThat(r.location().dir()).isEqualTo(dir.toAbsolutePath());
        assertThat(dir.resolve("page.xhtml")).hasContent("<table/>");
        assertThat(PageStore.readMeta(dir.resolve("page.json")).orElseThrow().version()).isEqualTo(1);
    }

    @Test
    void refusesExistingTitleInOtherCase() throws Exception {
        api.add("9", "DOC", "Projektplan", null, 1, "<p/>");
        Path file = tmp.resolve("page.xhtml");
        Files.writeString(file, "<p/>");
        assertThatThrownBy(() -> creator().create(file, "DOC", "projektplan", null))
                .isInstanceOf(SyncException.class).hasMessageContaining("bereits").hasMessageContaining("9");
        assertThat(api.calls).noneMatch(c -> c.startsWith("createPage"));
    }

    @Test
    void savedStorageOfTheServerIsWrittenLocally() throws Exception {
        api.normalizer = s -> s.replace("<br>", "<br />");
        Path file = tmp.resolve("page.xhtml");
        Files.writeString(file, "<p>a<br>b</p>");

        creator().create(file, "DOC", "Neu", null);
        assertThat(file).hasContent("<p>a<br />b</p>");
        api.calls.clear();
        assertThat(new Pusher(api, validator, Clock.systemUTC()).push(file,
                new Pusher.Options(null, null, false, false, false)).status()).isEqualTo(Pusher.Status.NO_CHANGE);

        api.responseWithBody = false;
        Creator.Result r = creator().createInto("<p>c<br>d</p>", tmp.resolve("out"), "DOC", "Import", null);
        assertThat(r.location().xhtml()).hasContent("<p>c<br />d</p>");
        assertThat(PageStore.readMeta(r.location().meta()).orElseThrow().contentHash())
                .isEqualTo(PageStore.contentHash("<p>c<br />d</p>"));
    }
}
