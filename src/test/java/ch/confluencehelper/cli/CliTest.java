package ch.confluencehelper.cli;

import ch.confluencehelper.client.ConfluenceException;
import ch.confluencehelper.config.ConfigException;
import ch.confluencehelper.sync.FakePageApi;
import ch.confluencehelper.sync.PageMeta;
import ch.confluencehelper.sync.PageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CliTest {

    @TempDir
    Path tmp;

    FakePageApi api = new FakePageApi();
    List<String> factoryArgs = new ArrayList<>();
    RuntimeException factoryFailure;
    StringWriter out;
    StringWriter err;

    int run(String... args) {
        out = new StringWriter();
        err = new StringWriter();
        CommandLine cmd = Main.commandLine(new CfhCommand((url, profile, verbose) -> {
            factoryArgs.add(url + "|" + profile + "|" + verbose);
            if (factoryFailure != null) {
                throw factoryFailure;
            }
            return api;
        }));
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        return cmd.execute(args);
    }

    Path exported() {
        api.add("7", "DOC", "Handbuch", null, 3, "<p>alt</p>\n");
        assertThat(run("export", "7", "-o", tmp.toString())).isZero();
        return tmp.resolve("7-handbuch");
    }

    @Test
    void versionAndHelp() {
        assertThat(run("--version")).isZero();
        assertThat(out.toString()).startsWith("cfh ");
        assertThat(run("help")).isZero();
        assertThat(out.toString()).contains("export", "push", "create", "convert", "import", "diff", "validate",
                "whoami");
        assertThat(run("help", "push")).isZero();
        assertThat(out.toString()).contains("--dry-run");
        assertThat(factoryArgs).isEmpty();
    }

    @Test
    void invalidInputExitsWithOne() {
        assertThat(run("--gibts-nicht")).isEqualTo(1);
        assertThat(run("create", "x.xhtml")).isEqualTo(1);
        assertThat(err.toString()).contains("--space");
    }

    @Test
    void globalOptionsWorkBeforeAndAfterSubcommand() {
        assertThat(run("--url", "https://a.example", "whoami", "--profile", "test", "-v")).isZero();
        assertThat(out.toString()).contains("Max Muster (mmuster)");
        assertThat(factoryArgs).containsExactly("https://a.example|test|true");
    }

    @Test
    void exportAndNoOpPush() {
        Path dir = exported();
        assertThat(dir.resolve("page.xhtml")).hasContent("<p>alt</p>\n");
        assertThat(out.toString()).contains("1 Seite exportiert.");

        assertThat(run("push", dir.toString())).isZero();
        assertThat(out.toString()).contains("Keine Änderung");
    }

    @Test
    void exportAcceptsUrl() {
        api.add("42", "DOC", "Per URL", null, 1, "<p/>");
        assertThat(run("export", "https://wiki.example.ch/pages/viewpage.action?pageId=42", "-o", tmp.toString()))
                .isZero();
        assertThat(tmp.resolve("42-per-url/page.json")).exists();
    }

    @Test
    void pushDryRunThenPush() throws Exception {
        Path dir = exported();
        Files.writeString(dir.resolve("page.xhtml"), "<p>neu</p>\n");

        assertThat(run("push", dir.toString(), "--dry-run")).isZero();
        assertThat(out.toString()).contains("Probelauf", "Version 4", "+1/-1");
        assertThat(api.storage("7")).isEqualTo("<p>alt</p>\n");

        assertThat(run("push", dir.toString(), "-m", "Update")).isZero();
        assertThat(out.toString()).contains("Version 3 → 4");
        assertThat(api.storage("7")).isEqualTo("<p>neu</p>\n");
    }

    @Test
    void requiredMessageAbortsAnEmptyPushBeforeAnyServerCall() throws Exception {
        Path dir = exported();
        Files.writeString(dir.resolve("page.xhtml"), "<p>neu</p>\n");
        api.calls.clear();
        factoryArgs.clear();

        // IntelliJ's $Prompt$ yields "" on Cancel
        assertThat(run("push", "--require-message", "--attachments", dir.toString(), "-m", "")).isEqualTo(1);
        assertThat(err.toString()).contains("Kein Versionskommentar", "nichts hochgeladen");
        assertThat(run("push", dir.toString(), "--require-message")).isEqualTo(1);
        assertThat(factoryArgs).isEmpty();
        assertThat(api.storage("7")).isEqualTo("<p>alt</p>\n");

        assertThat(run("push", "--require-message", dir.toString(), "--dry-run")).isZero();
        assertThat(run("push", "--require-message", dir.toString(), "-m", "Fix&Deploy")).isZero();
        assertThat(api.storage("7")).isEqualTo("<p>neu</p>\n");
        assertThat(run("help", "push")).isZero();
        assertThat(out.toString()).doesNotContain("--require-message");
    }

    @Test
    void exportWithEmptyPageAborts() {
        assertThat(run("export", "", "-o", tmp.toString())).isEqualTo(1);
        assertThat(err.toString()).contains("Keine Seite angegeben", "abgebrochen");
        assertThat(factoryArgs).isEmpty();
    }

    @Test
    void versionConflictExitsWithThree() throws Exception {
        Path dir = exported();
        Files.writeString(dir.resolve("page.xhtml"), "<p>lokal</p>");
        api.add("7", "DOC", "Handbuch", null, 4, "<p>fremd</p>");

        assertThat(run("push", dir.toString())).isEqualTo(3);
        assertThat(err.toString()).contains("Versionskonflikt", "cfh diff");

        assertThat(run("diff", dir.toString())).isZero();
        assertThat(out.toString()).contains("lokal Version 3, Server Version 4", "1 Version weiter",
                "-<p>fremd</p>", "+<p>lokal</p>");
    }

    @Test
    void serverSide409ExitsWithThree() throws Exception {
        Path dir = exported();
        Files.writeString(dir.resolve("page.xhtml"), "<p>lokal</p>");
        api.failure = new ConfluenceException(409, "Version must be incremented");
        assertThat(run("push", dir.toString())).isEqualTo(3);
    }

    @Test
    void invalidXhtmlExitsWithTwo() throws Exception {
        Path dir = exported();
        Files.writeString(dir.resolve("page.xhtml"), "<p>offen");
        assertThat(run("push", dir.toString())).isEqualTo(2);
        assertThat(err.toString()).contains("FEHLER");
        assertThat(run("validate", dir.toString())).isEqualTo(2);
        // absolute path of the file, so IDE problem matchers can resolve it
        assertThat(out.toString()).startsWith(dir.toAbsolutePath().resolve("page.xhtml") + ":1:");

        Files.writeString(dir.resolve("page.xhtml"), "<p>gut &auml;</p>");
        assertThat(run("validate", dir.toString())).isZero();
        assertThat(out.toString()).contains("OK");
    }

    @Test
    void authProblemExitsWithFour() {
        api.failure = new ConfluenceException(401, "Nicht angemeldet");
        assertThat(run("whoami")).isEqualTo(4);
        assertThat(err.toString()).contains("HTTP 401", "Token");
    }

    @Test
    void configProblemExplainsSetup() {
        factoryFailure = new ConfigException("Keine URL konfiguriert");
        assertThat(run("whoami")).isEqualTo(1);
        assertThat(err.toString()).contains("Keine URL konfiguriert", "CONFLUENCE_TOKEN", "config.properties");
    }

    @Test
    void createLinksFileForPush() throws Exception {
        Path file = tmp.resolve("neu.xhtml");
        Files.writeString(file, "<p>Neu</p>");
        assertThat(run("create", file.toString(), "--space", "DOC", "--title", "Neue Seite")).isZero();
        PageMeta meta = PageStore.readMeta(tmp.resolve("page.json")).orElseThrow();
        assertThat(meta.title()).isEqualTo("Neue Seite");

        assertThat(run("create", file.toString(), "--space", "DOC", "--title", "Neue Seite")).isEqualTo(1);
        assertThat(err.toString()).contains("bereits");
    }

    @Test
    void convertAndImportCsv() throws Exception {
        Path csv = tmp.resolve("preise.csv");
        Files.writeString(csv, "Artikel;Preis\nApfel;1.20\n");

        assertThat(run("convert", csv.toString())).isZero();
        assertThat(out.toString()).contains("<table", "Apfel");
        assertThat(factoryArgs).isEmpty();

        Path target = tmp.resolve("out.xhtml");
        assertThat(run("convert", csv.toString(), "-o", target.toString())).isZero();
        assertThat(target).content().contains("<th");

        Path outDir = tmp.resolve("export");
        assertThat(run("import", csv.toString(), "--space", "DOC", "--title", "Preise", "--attach-source",
                "-o", outDir.toString())).isZero();
        assertThat(api.calls).anyMatch(c -> c.startsWith("upload ") && c.endsWith("preise.csv"));
        String id = api.pages.keySet().iterator().next();
        assertThat(outDir.resolve(id + "-preise/page.json")).exists();
    }

    @Test
    void missingFileIsReported() {
        assertThat(run("convert", tmp.resolve("fehlt.pdf").toString())).isEqualTo(1);
        assertThat(err.toString()).contains("Datei nicht gefunden");
        assertThat(run("push", tmp.resolve("fehlt").toString())).isEqualTo(1);
    }

    @Test
    void redirectedOutputIsUtf8AndNonUtf8FilesGetAHint() throws Exception {
        java.nio.charset.Charset cp1252 = java.nio.charset.Charset.forName("windows-1252");
        assertThat(Main.stdoutCharset(false, cp1252)).isEqualTo(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(Main.stdoutCharset(true, cp1252)).isEqualTo(cp1252);

        Path ansi = tmp.resolve("ansi.xhtml");
        Files.write(ansi, "<p>Grösse → Ä</p>".replace("→", "-").getBytes(cp1252));
        assertThat(run("validate", ansi.toString())).isEqualTo(1);
        assertThat(err.toString()).contains("nicht UTF-8-kodiert");
    }

    @Test
    void pushHintsNoLongerSuggestAPlainReExport() {
        Path dir = exported();
        api.add("7", "DOC", "Handbuch", null, 4, "<p>fremd</p>");
        PageStore.writeXhtml(dir.resolve("page.xhtml"), "<p>lokal</p>");

        assertThat(run("push", dir.toString())).isEqualTo(Main.VERSION_CONFLICT);
        assertThat(err.toString()).contains("cfh diff", "-o ANDERER_ORDNER").doesNotContain("neu exportieren");
        assertThat(run("export", "7", "-o", tmp.toString())).isEqualTo(1);
        assertThat(dir.resolve("page.xhtml")).hasContent("<p>lokal</p>");
    }

    @Test
    void workspaceInitSetsUpTheFolderAndIsIdempotent() throws Exception {
        Path dir = exported();
        assertThat(run("workspace", "init", tmp.toString())).isZero();
        assertThat(out.toString()).contains("eingerichtet", "angelegt:", ".github/copilot-instructions.md",
                ".vscode/tasks.json", "code \"" + tmp.toAbsolutePath().normalize() + "\"");
        assertThat(tmp.resolve(".github/copilot-instructions.md")).exists();
        assertThat(tmp.resolve("AGENTS.md")).exists();
        assertThat(dir.resolve("page.xhtml")).hasContent("<p>alt</p>\n");
        assertThat(factoryArgs).hasSize(1); // only the export needed a connection

        Files.writeString(tmp.resolve("AGENTS.md"), "eigene");
        assertThat(run("workspace", "init", tmp.toString())).isZero();
        assertThat(out.toString()).contains("unverändert:", "übersprungen:  AGENTS.md").doesNotContain("angelegt:");
        assertThat(run("workspace", "init", tmp.toString(), "--force")).isZero();
        assertThat(out.toString()).contains("ersetzt:");
        assertThat(tmp.resolve("AGENTS.md.bak")).hasContent("eigene");

        assertThat(run("workspace", "init", tmp.resolve("fehlt").toString())).isEqualTo(1);
        assertThat(err.toString()).contains("Ordner nicht gefunden");
    }
}
