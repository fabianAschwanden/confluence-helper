package ch.confluencehelper.e2e;

import ch.confluencehelper.cli.CfhCommand;
import ch.confluencehelper.cli.Main;
import ch.confluencehelper.config.Config;
import ch.confluencehelper.storage.StorageXml;
import ch.confluencehelper.sync.PageMeta;
import ch.confluencehelper.sync.PageStore;
import ch.confluencehelper.sync.Slugs;
import ch.confluencehelper.testing.FakeConfluenceServer;
import ch.confluencehelper.testing.FakeConfluenceServer.PageState;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end: the real picocli command, real {@code Config} loading (injected environment) and the
 * real HTTP client against {@link FakeConfluenceServer} under a context path.
 */
class CliE2ETest {

    static final String ROOT_STORAGE = "<h1>Handbuch</h1>\n<p>Erste Zeile &auml; ü</p>\r\n<p>alt</p>\n";

    @TempDir
    Path tmp;

    FakeConfluenceServer server;
    Map<String, String> env = new HashMap<>();
    String out;
    String err;
    String log;
    String rootId;
    String childId;
    String grandchildId;

    @BeforeEach
    void setUp() {
        server = FakeConfluenceServer.start("/confluence");
        server.addSpace("DOC");
        server.setCurrentUser("anna.muster", "Anna Muster");
        rootId = server.addPage("DOC", null, "Handbuch", ROOT_STORAGE);
        childId = server.addPage("DOC", rootId, "Kapitel Ä", "<p>Kind</p>");
        grandchildId = server.addPage("DOC", childId, "Abschnitt 1", "<p>Enkel</p>");
        server.addAttachment(rootId, "bild.png", "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, (byte) 0xff});

        // never read the developer's real ~/.config/confluence-helper/config.properties
        env.put(Config.ENV_CONFIG, tmp.resolve("keine-config.properties").toString());
        env.put(Config.ENV_URL, server.url().toString());
        env.put(Config.ENV_TOKEN, server.token());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    int run(String... args) {
        StringWriter o = new StringWriter();
        StringWriter e = new StringWriter();
        ByteArrayOutputStream l = new ByteArrayOutputStream();
        int code;
        try (PrintStream logStream = new PrintStream(l, true, StandardCharsets.UTF_8)) {
            CommandLine cmd = Main.commandLine(new CfhCommand(CfhCommand.clientFactory(env, logStream)));
            cmd.setOut(new PrintWriter(o, true));
            cmd.setErr(new PrintWriter(e, true));
            code = cmd.execute(args);
        }
        out = o.toString();
        err = e.toString();
        log = l.toString(StandardCharsets.UTF_8);
        return code;
    }

    Path rootDir(Path base) {
        return base.resolve(Slugs.folderName(rootId, "Handbuch"));
    }

    /** Exports the root page into {@code tmp/export} and returns its folder. */
    Path exportRoot() {
        assertThat(run("export", rootId, "-o", tmp.resolve("export").toString())).as(err).isZero();
        return rootDir(tmp.resolve("export"));
    }

    static PageMeta meta(Path dir) {
        return PageStore.readMeta(dir.resolve(PageStore.META)).orElseThrow();
    }

    static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- whoami / config

    @Test
    void whoamiWithEnvironmentConfig() {
        assertThat(run("whoami")).as(err).isZero();
        assertThat(out).contains("Anna Muster").contains(server.url().toString());
        assertThat(err).isEmpty();
        assertThat(log).contains("ohne HTTPS"); // fake runs on http://
        assertThat(server.requests()).allSatisfy(r -> {
            assertThat(r.rawPath()).startsWith("/confluence/");
            assertThat(r.header("Authorization")).isEqualTo("Bearer " + server.token());
        });
    }

    @Test
    void whoamiWithProfileFromConfigFileAndVerboseLog() throws IOException {
        Path file = tmp.resolve("config.properties");
        Files.writeString(file, "url=https://falsch.example\n"
                + "test.url=" + server.url() + "/\n"
                + "test.token=" + server.token() + "\n");
        env.clear();
        env.put(Config.ENV_CONFIG, file.toString());

        assertThat(run("whoami", "--profile", "test", "-v")).as(err).isZero();
        assertThat(out).contains("Anna Muster");
        assertThat(log).contains("GET /confluence/rest/api/user/current").doesNotContain(server.token());
    }

    @Test
    void urlOptionOverridesEnvironment() {
        env.put(Config.ENV_URL, "https://falsch.example");
        assertThat(run("--url", server.url().toString(), "whoami")).as(err).isZero();
    }

    @Test
    void missingConfigurationExplainsSetup() {
        env.remove(Config.ENV_URL);
        assertThat(run("whoami")).isEqualTo(Main.ERROR);
        assertThat(err).contains("Konfigurationsfehler", "CONFLUENCE_URL");
    }

    @Test
    void wrongTokenExitsWithFourWithoutLeakingIt() {
        env.put(Config.ENV_TOKEN, "falsches-geheimnis");
        assertThat(run("whoami")).isEqualTo(Main.AUTH);
        assertThat(err).contains("401").doesNotContain("falsches-geheimnis");

        assertThat(run("export", rootId, "-o", tmp.toString())).isEqualTo(Main.AUTH);
        assertThat(tmp.resolve(Slugs.folderName(rootId, "Handbuch"))).doesNotExist();
    }

    @Test
    void forbiddenExitsWithFour() {
        server.failNext(403, "Keine Berechtigung für Space DOC");
        assertThat(run("export", rootId, "-o", tmp.toString())).isEqualTo(Main.AUTH);
        assertThat(err).contains("403", "Keine Berechtigung für Space DOC");
    }

    // ---------------------------------------------------------------- export

    @Test
    void exportWithAttachmentsAndChildren() throws IOException {
        Path base = tmp.resolve("export");
        assertThat(run("export", rootId, "-o", base.toString(), "--attachments", "--children")).as(err).isZero();
        assertThat(out).contains("3 Seiten exportiert.");

        Path root = rootDir(base);
        assertThat(Files.readAllBytes(root.resolve(PageStore.XHTML)))
                .isEqualTo(ROOT_STORAGE.getBytes(StandardCharsets.UTF_8)); // byte-exact incl. CRLF/entities
        PageMeta m = meta(root);
        assertThat(m.id()).isEqualTo(rootId);
        assertThat(m.title()).isEqualTo("Handbuch");
        assertThat(m.spaceKey()).isEqualTo("DOC");
        assertThat(m.version()).isEqualTo(1);
        assertThat(m.baseUrl()).isEqualTo(server.url().toString());
        assertThat(Files.readAllBytes(root.resolve(PageStore.ATTACHMENTS).resolve("bild.png")))
                .isEqualTo(server.attachmentBytes(rootId, "bild.png"));

        Path child = root.resolve(Slugs.folderName(childId, "Kapitel Ä"));
        assertThat(read(child.resolve(PageStore.XHTML))).isEqualTo("<p>Kind</p>");
        assertThat(meta(child).parentId()).isEqualTo(rootId);
        Path grandchild = child.resolve(Slugs.folderName(grandchildId, "Abschnitt 1"));
        assertThat(read(grandchild.resolve(PageStore.XHTML))).isEqualTo("<p>Enkel</p>");
    }

    @Test
    void exportDepthLimitsChildren() {
        Path base = tmp.resolve("export");
        assertThat(run("export", rootId, "-o", base.toString(), "--depth", "1")).as(err).isZero();
        Path child = rootDir(base).resolve(Slugs.folderName(childId, "Kapitel Ä"));
        assertThat(child.resolve(PageStore.XHTML)).exists();
        assertThat(child.resolve(Slugs.folderName(grandchildId, "Abschnitt 1"))).doesNotExist();
    }

    @Test
    void exportResolvesDisplayAndViewpageUrls() {
        String id = server.addPage("DOC", null, "Preise & Konditionen+Plus", "<p>CHF</p>");
        String display = server.url() + "/display/DOC/Preise+%26+Konditionen%2BPlus";
        Path base = tmp.resolve("export");
        assertThat(run("export", display, "-o", base.toString())).as(err).isZero();
        assertThat(base.resolve(Slugs.folderName(id, "Preise & Konditionen+Plus")).resolve(PageStore.XHTML)).exists();

        String viewpage = server.url() + "/pages/viewpage.action?pageId=" + childId;
        assertThat(run("export", viewpage, "-o", base.toString())).as(err).isZero();
        assertThat(base.resolve(Slugs.folderName(childId, "Kapitel Ä")).resolve(PageStore.XHTML)).exists();
    }

    @Test
    void exportOfUnknownPageFails() {
        assertThat(run("export", "999999", "-o", tmp.toString())).isEqualTo(Main.ERROR);
        assertThat(err).contains("404");
    }

    // ---------------------------------------------------------------- push

    @Test
    void editThenPushCreatesNewVersion() throws IOException {
        Path dir = exportRoot();
        String edited = read(dir.resolve(PageStore.XHTML)).replace("<p>alt</p>", "<p>neu ergänzt</p>");
        Files.writeString(dir.resolve(PageStore.XHTML), edited, StandardCharsets.UTF_8);

        assertThat(run("push", dir.toString(), "-m", "Ergänzt mit Claude")).as(err).isZero();
        assertThat(out).contains("Version 1 → 2");

        PageState page = server.page(rootId);
        assertThat(page.version()).isEqualTo(2);
        assertThat(page.storage()).isEqualTo(edited);
        assertThat(page.versionMessage()).isEqualTo("Ergänzt mit Claude");
        assertThat(page.parentId()).isNull();
        assertThat(meta(dir).version()).isEqualTo(2);

        // a second push of the same content is a no-op against the new version
        server.clearRequests();
        assertThat(run("push", dir.resolve(PageStore.XHTML).toString())).as(err).isZero();
        assertThat(server.requests("PUT")).isEmpty();
    }

    @Test
    void pushWithoutChangeSendsNoPut() {
        Path dir = exportRoot();
        server.clearRequests();
        assertThat(run("push", dir.toString())).as(err).isZero();
        assertThat(out).contains("Keine Änderung");
        assertThat(server.requests("PUT")).isEmpty();
        assertThat(server.page(rootId).version()).isEqualTo(1);
    }

    @Test
    void pushTitleChangeOnly() {
        Path dir = exportRoot();
        assertThat(run("push", dir.toString(), "--title", "Handbuch 2026")).as(err).isZero();
        assertThat(server.page(rootId).title()).isEqualTo("Handbuch 2026");
        assertThat(server.page(rootId).storage()).isEqualTo(ROOT_STORAGE);
        assertThat(meta(dir).title()).isEqualTo("Handbuch 2026");
    }

    @Test
    void dryRunChangesNothing() throws IOException {
        Path dir = exportRoot();
        Files.writeString(dir.resolve(PageStore.XHTML), "<p>neu</p>", StandardCharsets.UTF_8);
        assertThat(run("push", dir.toString(), "--dry-run")).as(err).isZero();
        assertThat(out).contains("Probelauf");
        assertThat(server.requests("PUT")).isEmpty();
        assertThat(meta(dir).version()).isEqualTo(1);
    }

    @Test
    void concurrentRemoteEditBlocksPushUntilForce() throws IOException {
        Path dir = exportRoot();
        Files.writeString(dir.resolve(PageStore.XHTML), "<p>lokal</p>", StandardCharsets.UTF_8);
        server.editPage(rootId, null, "<p>remote</p>");
        server.clearRequests();

        assertThat(run("push", dir.toString())).isEqualTo(Main.VERSION_CONFLICT);
        assertThat(err).contains("diff", "--force");
        assertThat(server.requests("PUT")).isEmpty();
        assertThat(server.page(rootId).storage()).isEqualTo("<p>remote</p>");
        assertThat(server.page(rootId).version()).isEqualTo(2);
        assertThat(meta(dir).version()).isEqualTo(1);
        assertThat(read(dir.resolve(PageStore.XHTML))).isEqualTo("<p>lokal</p>");

        assertThat(run("push", dir.toString(), "--force")).as(err).isZero();
        assertThat(server.page(rootId).version()).isEqualTo(3);
        assertThat(server.page(rootId).storage()).isEqualTo("<p>lokal</p>");
        assertThat(meta(dir).version()).isEqualTo(3);
    }

    @Test
    void invalidXhtmlIsRejectedBeforeAnyWrite() throws IOException {
        Path dir = exportRoot();
        Files.writeString(dir.resolve(PageStore.XHTML), "<p>offen\n<p>kaputt & so</p>", StandardCharsets.UTF_8);
        server.clearRequests();

        assertThat(run("push", dir.toString())).isEqualTo(Main.VALIDATION);
        assertThat(err).contains("FEHLER");
        assertThat(server.requests("PUT")).isEmpty();
        assertThat(server.page(rootId).version()).isEqualTo(1);
    }

    @Test
    void pushUploadsNewAttachments() throws IOException {
        Path base = tmp.resolve("export");
        assertThat(run("export", rootId, "-o", base.toString(), "--attachments")).as(err).isZero();
        Path dir = rootDir(base);
        byte[] pdf = "%PDF-1.4 neu".getBytes(StandardCharsets.US_ASCII);
        Files.write(dir.resolve(PageStore.ATTACHMENTS).resolve("anhang.pdf"), pdf);

        assertThat(run("push", dir.toString(), "--attachments")).as(err).isZero();
        assertThat(out).contains("Anhang hochgeladen: anhang.pdf");
        assertThat(server.attachmentBytes(rootId, "anhang.pdf")).isEqualTo(pdf);
        assertThat(server.attachments(rootId)).hasSize(2); // unchanged bild.png not re-uploaded
    }

    // ---------------------------------------------------------------- diff

    @Test
    void diffShowsLocalAndRemoteChanges() throws IOException {
        Path dir = exportRoot();
        assertThat(run("diff", dir.toString())).as(err).isZero();
        assertThat(out).contains("(aktuell)", "Keine Unterschiede");

        Files.writeString(dir.resolve(PageStore.XHTML),
                read(dir.resolve(PageStore.XHTML)).replace("<p>alt</p>", "<p>neu</p>"), StandardCharsets.UTF_8);
        server.editPage(rootId, "Handbuch (remote)", null);

        assertThat(run("diff", dir.toString())).as(err).isZero();
        assertThat(out).contains("Server ist 1 Version weiter", "Handbuch (remote)",
                "-<p>alt</p>", "+<p>neu</p>");
        assertThat(server.requests("PUT")).isEmpty();
    }

    // ---------------------------------------------------------------- create

    @Test
    void createFromXhtmlThenPush() throws IOException {
        Path work = Files.createDirectories(tmp.resolve("entwurf"));
        Path file = work.resolve("neu.xhtml");
        Files.writeString(file, "<h2>Neu</h2>\n<p>Entwurf</p>\n", StandardCharsets.UTF_8);

        assertThat(run("create", file.toString(), "--space", "DOC", "--title", "Neue Seite",
                "--parent", server.url() + "/display/DOC/Handbuch")).as(err).isZero();
        PageState created = server.findPage("DOC", "Neue Seite").orElseThrow();
        assertThat(created.parentId()).isEqualTo(rootId);
        assertThat(created.storage()).isEqualTo("<h2>Neu</h2>\n<p>Entwurf</p>\n");
        assertThat(meta(work).id()).isEqualTo(created.id());

        Files.writeString(file, "<h2>Neu</h2>\n<p>Fertig</p>\n", StandardCharsets.UTF_8);
        assertThat(run("push", file.toString())).as(err).isZero();
        assertThat(server.page(created.id()).version()).isEqualTo(2);
        assertThat(server.page(created.id()).storage()).contains("Fertig");
        assertThat(server.page(created.id()).parentId()).isEqualTo(rootId);
        assertThat(meta(work).version()).isEqualTo(2);
    }

    @Test
    void createRefusesExistingTitle() throws IOException {
        Path file = tmp.resolve("x.xhtml");
        Files.writeString(file, "<p>x</p>", StandardCharsets.UTF_8);
        assertThat(run("create", file.toString(), "--space", "DOC", "--title", "Handbuch")).isEqualTo(Main.ERROR);
        assertThat(server.requests("POST")).isEmpty();
        assertThat(tmp.resolve(PageStore.META)).doesNotExist();
    }

    // ---------------------------------------------------------------- import

    @Test
    void importXlsxWithSourceAttachment() throws IOException {
        Path xlsx = tmp.resolve("Preisliste.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream os = Files.newOutputStream(xlsx)) {
            XSSFSheet s = wb.createSheet("Preise");
            s.createRow(0).createCell(0).setCellValue("Artikel");
            s.getRow(0).createCell(1).setCellValue("Preis");
            s.createRow(1).createCell(0).setCellValue("Käse & Brot");
            s.getRow(1).createCell(1).setCellValue(12.5);
            wb.write(os);
        }
        Path local = tmp.resolve("lokal");
        assertThat(run("import", xlsx.toString(), "--space", "DOC", "--title", "Preisliste",
                "--parent", rootId, "--attach-source", "-o", local.toString())).as(err).isZero();

        PageState page = server.findPage("DOC", "Preisliste").orElseThrow();
        assertThat(page.parentId()).isEqualTo(rootId);
        assertThat(page.storage()).contains("<h2>Preise</h2>", "<th>Artikel</th>", "Käse &amp; Brot", "12.5");
        assertThat(StorageXml.validate(page.storage())).isEmpty();
        assertThat(server.attachmentBytes(page.id(), "Preisliste.xlsx")).isEqualTo(Files.readAllBytes(xlsx));

        // the local copy is linked, so the imported page can be edited and pushed right away
        Path dir = local.resolve(Slugs.folderName(page.id(), "Preisliste"));
        assertThat(meta(dir).id()).isEqualTo(page.id());
        Files.writeString(dir.resolve(PageStore.XHTML), page.storage() + "<p>Stand Oktober</p>", StandardCharsets.UTF_8);
        assertThat(run("push", dir.toString())).as(err).isZero();
        assertThat(server.page(page.id()).version()).isEqualTo(2);
    }

    @Test
    void importCsvWithSourceAttachment() throws IOException {
        Path csv = tmp.resolve("kontakte.csv");
        Files.writeString(csv, "Name;Ort\nMüller;Zürich\n\"Meier; Co\";Bern\n", StandardCharsets.UTF_8);
        assertThat(run("import", csv.toString(), "--space", "DOC", "--title", "Kontakte",
                "--attach-source", "-o", tmp.resolve("lokal").toString())).as(err).isZero();

        PageState page = server.findPage("DOC", "Kontakte").orElseThrow();
        assertThat(page.parentId()).isNull();
        assertThat(page.storage()).contains("<th>Name</th>", "<td>Zürich</td>", "<td>Meier; Co</td>");
        assertThat(server.attachmentBytes(page.id(), "kontakte.csv")).isEqualTo(Files.readAllBytes(csv));
        assertThat(server.requests("POST")).anySatisfy(r ->
                assertThat(r.header("X-Atlassian-Token")).isEqualTo("no-check"));
    }

    @Test
    void importPdfWithSourceAttachment() throws IOException {
        Path pdf = tmp.resolve("bericht.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage p = new PDPage();
            doc.addPage(p);
            try (PDPageContentStream cs = new PDPageContentStream(doc, p)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 20);
                cs.newLineAtOffset(72, 700);
                cs.showText("Jahresbericht");
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(0, -40);
                cs.showText("Der Umsatz stieg um 5 % gegenüber dem Vorjahr.");
                cs.endText();
            }
            doc.save(pdf.toFile());
        }
        assertThat(run("import", pdf.toString(), "--space", "DOC", "--title", "Bericht 2026",
                "--attach-source", "-m", "Original-PDF", "-o", tmp.resolve("lokal").toString())).as(err).isZero();

        PageState page = server.findPage("DOC", "Bericht 2026").orElseThrow();
        assertThat(page.storage()).contains("Jahresbericht", "Der Umsatz stieg um 5 % gegenüber dem Vorjahr.");
        assertThat(StorageXml.validate(page.storage())).isEmpty();
        assertThat(server.attachmentBytes(page.id(), "bericht.pdf")).isEqualTo(Files.readAllBytes(pdf));
        assertThat(server.attachments(page.id()).get(0).comment()).isEqualTo("Original-PDF");
    }

    @Test
    void importIntoExistingTitleFailsWithoutSideEffects() throws IOException {
        Path csv = tmp.resolve("a.csv");
        Files.writeString(csv, "a,b\n1,2\n", StandardCharsets.UTF_8);
        int pages = server.pages().size();
        assertThat(run("import", csv.toString(), "--space", "DOC", "--title", "Handbuch", "--attach-source",
                "-o", tmp.resolve("lokal").toString())).isEqualTo(Main.ERROR);
        assertThat(server.pages()).hasSize(pages);
        assertThat(server.attachments(rootId)).hasSize(1);
    }

    // ---------------------------------------------------------------- offline commands

    @Test
    void convertAndValidateNeedNoConfiguration() throws IOException {
        env.clear();
        env.put(Config.ENV_CONFIG, tmp.resolve("keine.properties").toString());
        Path csv = tmp.resolve("t.csv");
        Files.writeString(csv, "x,y\n1,2\n", StandardCharsets.UTF_8);
        Path xhtml = tmp.resolve("t.xhtml");

        assertThat(run("convert", csv.toString(), "-o", xhtml.toString())).as(err).isZero();
        assertThat(run("validate", xhtml.toString())).as(err).isZero();
        assertThat(server.requests()).isEmpty();
    }

    // ---------------------------------------------------------------- review regressions

    @Test
    void serverSideNormalisationSettlesAfterOnePush() throws IOException {
        server.normalizeStorageOnSave(st -> st.replace("<br/>", "<br />"));
        Path dir = exportRoot();
        Files.writeString(dir.resolve(PageStore.XHTML), "<p>Zeile<br/>neu</p>", StandardCharsets.UTF_8);

        assertThat(run("push", dir.toString())).as(err).isZero();
        assertThat(read(dir.resolve(PageStore.XHTML))).isEqualTo("<p>Zeile<br />neu</p>");

        server.clearRequests();
        assertThat(run("push", dir.toString())).as(err).isZero();
        assertThat(out).contains("Keine Änderung");
        assertThat(server.requests("PUT")).isEmpty();
        assertThat(run("diff", dir.toString())).as(err).isZero();
        assertThat(out).contains("Keine Unterschiede");
        assertThat(run("export", rootId, "-o", tmp.resolve("export").toString())).as(err).isZero();
    }

    @Test
    void reExportAfterVersionConflictKeepsLocalEdits() throws IOException {
        Path dir = exportRoot();
        Files.writeString(dir.resolve(PageStore.XHTML), "<p>lokal erweitert</p>", StandardCharsets.UTF_8);
        server.editPage(rootId, null, "<p>v2 vom Kollegen</p>");

        assertThat(run("push", dir.toString())).isEqualTo(Main.VERSION_CONFLICT);
        assertThat(run("export", rootId, "-o", tmp.resolve("export").toString())).isEqualTo(Main.ERROR);
        assertThat(err).contains("lokale Änderungen");
        assertThat(read(dir.resolve(PageStore.XHTML))).isEqualTo("<p>lokal erweitert</p>");
    }

    @Test
    void folderOfAnotherServerIsNotPushedOrDiffed() throws IOException {
        Path dir = exportRoot();
        PageMeta m = meta(dir);
        PageStore.writeMeta(dir.resolve(PageStore.META), new PageMeta(m.id(), m.title(), m.spaceKey(), m.version(),
                m.parentId(), "https://prod.example.ch/confluence", m.webUrl(), m.exportedAt(), m.contentHash(),
                m.attachments()));
        Files.writeString(dir.resolve(PageStore.XHTML), "<p>neu</p>", StandardCharsets.UTF_8);
        server.clearRequests();

        assertThat(run("push", dir.toString(), "--force")).isEqualTo(Main.ERROR);
        assertThat(err).contains("https://prod.example.ch/confluence", "--other-server");
        assertThat(run("diff", dir.toString())).isEqualTo(Main.ERROR);
        assertThat(server.requests("PUT")).isEmpty();

        assertThat(run("push", dir.toString(), "--other-server")).as(err).isZero();
        assertThat(meta(dir).baseUrl()).isEqualTo(server.url().toString());
    }

    @Test
    void createRefusesTitleThatDiffersOnlyInCase() throws IOException {
        Path file = tmp.resolve("neu.xhtml");
        Files.writeString(file, "<p>x</p>", StandardCharsets.UTF_8);
        assertThat(run("create", file.toString(), "--space", "DOC", "--title", "HANDBUCH")).isEqualTo(Main.ERROR);
        assertThat(err).contains("gibt es bereits eine Seite");
        assertThat(server.requests("POST")).isEmpty();
    }

    @Test
    void sanitizedAttachmentNamesRoundTripWithoutDuplicates() throws IOException {
        server.addAttachment(rootId, "Protokoll 12:30.txt", "text/plain", "Meier".getBytes(StandardCharsets.UTF_8));
        Path base = tmp.resolve("export");
        assertThat(run("export", rootId, "-o", base.toString(), "--attachments")).as(err).isZero();
        Path file = rootDir(base).resolve(PageStore.ATTACHMENTS).resolve("Protokoll 12_30.txt");
        assertThat(file).hasContent("Meier");

        assertThat(run("push", rootDir(base).toString(), "--attachments")).as(err).isZero();
        assertThat(out).doesNotContain("Anhang hochgeladen");

        Files.writeString(file, "Meyer", StandardCharsets.UTF_8); // same size
        assertThat(run("push", rootDir(base).toString(), "--attachments")).as(err).isZero();
        assertThat(out).contains("Anhang hochgeladen: Protokoll 12_30.txt");
        assertThat(server.attachments(rootId)).hasSize(2);
        assertThat(server.attachmentBytes(rootId, "Protokoll 12:30.txt")).isEqualTo("Meyer".getBytes(StandardCharsets.UTF_8));

        // a further export keeps the local file and does not complain
        assertThat(run("export", rootId, "-o", base.toString(), "--attachments")).as(err).isZero();
    }

    @Test
    void reExportDoesNotOverwriteLocallyChangedAttachment() throws IOException {
        Path base = tmp.resolve("export");
        assertThat(run("export", rootId, "-o", base.toString(), "--attachments")).as(err).isZero();
        Path bild = rootDir(base).resolve(PageStore.ATTACHMENTS).resolve("bild.png");
        Files.writeString(bild, "bearbeitet");

        assertThat(run("export", rootId, "-o", base.toString(), "--attachments")).isEqualTo(Main.ERROR);
        assertThat(err).contains("bild.png");
        assertThat(bild).hasContent("bearbeitet");
    }
}
