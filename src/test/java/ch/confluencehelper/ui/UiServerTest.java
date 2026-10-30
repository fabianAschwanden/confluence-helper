package ch.confluencehelper.ui;

import ch.confluencehelper.client.ConfluenceException;
import ch.confluencehelper.config.Config;
import ch.confluencehelper.sync.PageStore;
import ch.confluencehelper.sync.Slugs;
import ch.confluencehelper.sync.VersionConflictException;
import ch.confluencehelper.testing.FakeConfluenceServer;
import ch.confluencehelper.workspace.Installation;
import ch.confluencehelper.workspace.WorkspaceSetup;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP against {@link UiServer}, which talks to {@link FakeConfluenceServer} through the real client. */
class UiServerTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    FakeConfluenceServer fake;
    UiServer server;
    HttpClient http = HttpClient.newHttpClient();
    Path configFile;
    Path workspace;
    String rootId;
    String childId;
    List<Path> opened = new ArrayList<>();
    List<String> ideCalls = new ArrayList<>();
    boolean ideFound = true;
    ByteArrayOutputStream log = new ByteArrayOutputStream();

    record Res(int status, JsonNode json, HttpResponse<String> raw) {
    }

    @BeforeEach
    void setUp() throws IOException {
        fake = FakeConfluenceServer.start("/confluence");
        fake.addSpace("DOC");
        fake.setCurrentUser("anna.muster", "Anna Muster");
        rootId = fake.addPage("DOC", null, "Handbuch", "<h1>Handbuch</h1>\n<p>Grüezi</p>\n");
        childId = fake.addPage("DOC", rootId, "Kapitel Ä", "<p>Kind</p>");
        fake.addAttachment(rootId, "bild.png", "image/png", new byte[]{1, 2, 3});
        configFile = tmp.resolve("cfg/config.properties");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, "url=" + fake.url() + "\ntoken=" + fake.token() + "\n");
        workspace = Files.createDirectories(tmp.resolve("ws")).toRealPath();
        server = start(new UiServer.Options());
    }

    UiServer start(UiServer.Options o) throws IOException {
        return UiServer.start(o.env(Map.of(Config.ENV_CONFIG, configFile.toString()))
                .workspace(workspace)
                .uiProperties(tmp.resolve("cfg/ui.properties"))
                .log(new PrintStream(log, true, StandardCharsets.UTF_8))
                .launcher(new Launcher() {
                    @Override public boolean browse(URI uri) { return false; }
                    @Override public boolean open(Path dir) { opened.add(dir); return true; }
                    @Override public boolean openIde(Ide ide, Path project, Path file) {
                        ideCalls.add(ide + " " + project + " " + file);
                        return ideFound;
                    }
                }));
    }

    @AfterEach
    void tearDown() {
        server.stop();
        fake.close();
    }

    // ---------------------------------------------------------------- helpers

    URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    Res send(HttpRequest.Builder b) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode json = r.headers().firstValue("Content-Type").orElse("").startsWith("application/json")
                ? JSON.readTree(r.body()) : null;
        return new Res(r.statusCode(), json, r);
    }

    Res call(String method, String path, Object body) throws IOException, InterruptedException {
        HttpRequest.BodyPublisher pub = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
        return send(HttpRequest.newBuilder(uri("/api" + path)).header("X-CFH-Token", server.token())
                .method(method, pub));
    }

    JsonNode ok(String method, String path, Object body) throws IOException, InterruptedException {
        Res r = call(method, path, body);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.json();
    }

    JsonNode upload(String name, byte[] data) throws IOException, InterruptedException {
        Res r = send(HttpRequest.newBuilder(uri("/api/upload?name=" + URLEncoder.encode(name, StandardCharsets.UTF_8)))
                .header("X-CFH-Token", server.token()).POST(HttpRequest.BodyPublishers.ofByteArray(data)));
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        return r.json();
    }

    /** Sends a raw request line and headers, for what HttpClient refuses to send (Host, ../). */
    String raw(String requestLineAndHeaders) throws IOException {
        try (Socket s = new Socket("127.0.0.1", server.port())) {
            OutputStream out = s.getOutputStream();
            out.write((requestLineAndHeaders + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = s.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    Path exportRoot() throws IOException, InterruptedException {
        ok("POST", "/export", Map.of("page", rootId));
        return workspace.resolve(Slugs.folderName(rootId, "Handbuch"));
    }

    // ---------------------------------------------------------------- security

    @Test
    void apiNeedsTheSessionToken() throws Exception {
        Res missing = send(HttpRequest.newBuilder(uri("/api/status")).GET());
        assertThat(missing.status()).isEqualTo(401);
        assertThat(missing.json().path("kind").asText()).isEqualTo("session");

        Res wrong = send(HttpRequest.newBuilder(uri("/api/status")).header("X-CFH-Token", "falsch").GET());
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(wrong.json().path("kind").asText()).isEqualTo("session");
        assertThat(wrong.raw().body()).doesNotContain(server.token());
    }

    @Test
    void foreignHostOrOriginIsRejected() throws Exception {
        String res = raw("GET /api/ping HTTP/1.1\r\nHost: evil.example:" + server.port() + "\r\nX-CFH-Token: "
                + server.token() + "\r\n");
        assertThat(res).startsWith("HTTP/1.1 403");
        assertThat(raw("GET / HTTP/1.1\r\nHost: evil.example\r\n")).startsWith("HTTP/1.1 403");
        assertThat(raw("GET /api/ping HTTP/1.1\r\nHost: localhost:" + server.port() + "\r\nX-CFH-Token: "
                + server.token() + "\r\n")).startsWith("HTTP/1.1 200");

        Res origin = send(HttpRequest.newBuilder(uri("/api/ping")).header("X-CFH-Token", server.token())
                .header("Origin", "http://evil.example").GET());
        assertThat(origin.status()).isEqualTo(403);
    }

    @Test
    void staticFilesWithSecurityHeadersAndNoTraversal() throws Exception {
        Res index = send(HttpRequest.newBuilder(uri("/")).GET());
        assertThat(index.status()).isEqualTo(200);
        assertThat(index.raw().headers().firstValue("Content-Type")).hasValue("text/html; charset=utf-8");
        assertThat(index.raw().headers().firstValue("Content-Security-Policy")).hasValue(UiServer.CSP);
        assertThat(index.raw().headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(index.raw().headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
        assertThat(index.raw().headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(index.raw().body()).containsIgnoringCase("<html");

        assertThat(send(HttpRequest.newBuilder(uri("/gibtsnicht.html")).GET()).status()).isEqualTo(404);
        assertThat(send(HttpRequest.newBuilder(uri("/%2e%2e/pom.xml")).GET()).status()).isEqualTo(404);
        assertThat(send(HttpRequest.newBuilder(uri("/ch/confluencehelper/storage/xhtml-entities.ent")).GET()).status())
                .isEqualTo(404);
        String host = "Host: 127.0.0.1:" + server.port() + "\r\n";
        assertThat(raw("GET /../../ch/confluencehelper/storage/xhtml-entities.ent HTTP/1.1\r\n" + host)).startsWith("HTTP/1.1 404");
        assertThat(raw("GET /.hidden.html HTTP/1.1\r\n" + host)).startsWith("HTTP/1.1 404");
        assertThat(send(HttpRequest.newBuilder(uri("/index.html")).POST(HttpRequest.BodyPublishers.noBody())).status())
                .isEqualTo(405);
    }

    @Test
    void workspaceEscapesAreRejected() throws Exception {
        Path outside = Files.createDirectories(tmp.resolve("draussen"));
        Files.writeString(outside.resolve(PageStore.XHTML), "<p>geheim</p>");
        Files.createSymbolicLink(workspace.resolve("link"), outside);

        for (String dir : List.of("../draussen", outside.toString(), "link", "gibtsnicht", "")) {
            Res r = call("GET", "/page?dir=" + URLEncoder.encode(dir, StandardCharsets.UTF_8), null);
            assertThat(r.status()).as(dir).isEqualTo(400);
            assertThat(r.json().path("kind").asText()).isEqualTo("input");
            Res put = call("PUT", "/page", Map.of("dir", dir, "storage", "<p>x</p>"));
            assertThat(put.status()).as(dir).isEqualTo(400);
        }
        assertThat(Files.readString(outside.resolve(PageStore.XHTML))).isEqualTo("<p>geheim</p>");
    }

    // ---------------------------------------------------------------- connection

    @Test
    void statusAndPing() throws Exception {
        assertThat(ok("GET", "/ping", null).path("ok").asBoolean()).isTrue();
        JsonNode s = ok("GET", "/status", null);
        assertThat(s.path("configured").asBoolean()).isTrue();
        assertThat(s.path("baseUrl").asText()).isEqualTo(fake.url().toString());
        assertThat(s.path("auth").asText()).isEqualTo("token");
        assertThat(s.path("workspace").asText()).isEqualTo(workspace.toString());
        assertThat(s.path("configFile").asText()).isEqualTo(configFile.toString());
        assertThat(s.toString()).doesNotContain(fake.token());
        assertThat(ok("POST", "/whoami", Map.of()).path("user").asText()).isEqualTo("Anna Muster (anna.muster)");
    }

    @Test
    void configIsWrittenPrivatelyAndKeepsOtherEntries() throws Exception {
        Files.writeString(configFile, "# meine Notiz\nurl=https://alt.example\ntoken=alter-token\n"
                + "prod.url=https://prod.example\nprod.token=prod-geheim\n");

        JsonNode s = ok("POST", "/config", Map.of("url", fake.url() + "/", "token", fake.token()));
        assertThat(s.path("configured").asBoolean()).isTrue();
        assertThat(s.toString()).doesNotContain(fake.token()).doesNotContain("prod-geheim");
        assertThat(s.path("profiles").toString()).contains("prod");
        String file = Files.readString(configFile);
        assertThat(file).startsWith("# meine Notiz\n").contains("prod.token=prod-geheim")
                .contains("token=" + fake.token()).doesNotContain("alter-token").doesNotContain("alt.example");
        assertThat(Files.getPosixFilePermissions(configFile)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(ok("POST", "/whoami", Map.of()).path("user").asText()).contains("Anna Muster");

        // blank token keeps the stored one
        ok("POST", "/config", Map.of("url", fake.url().toString(), "token", ""));
        assertThat(Files.readString(configFile)).contains("token=" + fake.token());

        // a new profile becomes the active one
        JsonNode p = ok("POST", "/config", Map.of("profile", "test", "url", fake.url().toString(), "token", fake.token()));
        assertThat(p.path("profile").asText()).isEqualTo("test");
        assertThat(p.path("profiles").toString()).contains("prod").contains("test");
        assertThat(ok("POST", "/whoami", Map.of()).path("user").asText()).contains("Anna Muster");

        // basic auth replaces the token of that profile; the password is never echoed
        Res basic = call("POST", "/config", Map.of("profile", "test", "url", fake.url().toString(),
                "username", "anna", "password", "pw-geheim"));
        assertThat(basic.json().path("auth").asText()).isEqualTo("basic");
        assertThat(basic.raw().body()).doesNotContain("pw-geheim");
        assertThat(Files.readString(configFile)).contains("test.password=pw-geheim").doesNotContain("test.token");

        Res bad = call("POST", "/config", Map.of("url", "keine-url", "token", "x"));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.json().path("kind").asText()).isEqualTo("config");
    }

    @Test
    void errorsAreMappedToKinds() throws Exception {
        Files.delete(configFile);
        Res none = call("POST", "/whoami", Map.of());
        assertThat(none.status()).isEqualTo(400);
        assertThat(none.json().path("kind").asText()).isEqualTo("config");
        assertThat(ok("GET", "/status", null).path("configured").asBoolean()).isFalse();

        Files.writeString(configFile, "url=" + fake.url() + "\ntoken=" + fake.token() + "\n");
        fake.failNext(401, "Unauthorized");
        Res auth = call("POST", "/whoami", Map.of());
        assertThat(auth.status()).isEqualTo(401);
        assertThat(auth.json().path("kind").asText()).isEqualTo("auth");

        Res missing = call("POST", "/export", Map.of("page", "999999"));
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.json().path("kind").asText()).isEqualTo("notfound");

        Res input = call("POST", "/export", Map.of("page", "keine seite"));
        assertThat(input.status()).isEqualTo(400);
        assertThat(input.json().path("kind").asText()).isEqualTo("input");

        Res unknown = call("GET", "/gibtsnicht", null);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(call("GET", "/push", null).status()).isEqualTo(405);
    }

    @Test
    void workspaceCanBeChangedAndIsRemembered() throws Exception {
        Path other = tmp.resolve("anderer");
        JsonNode s = ok("POST", "/workspace", Map.of("path", other.toString()));
        assertThat(s.path("workspace").asText()).isEqualTo(other.toRealPath().toString());
        assertThat(Files.readString(tmp.resolve("cfg/ui.properties"))).contains("workspace=");
        assertThat(call("POST", "/workspace", Map.of("path", "relativ")).status()).isEqualTo(400);

        server.stop();
        server = UiServer.start(new UiServer.Options().env(Map.of(Config.ENV_CONFIG, configFile.toString()))
                .uiProperties(tmp.resolve("cfg/ui.properties")).launcher(Launcher.NONE));
        assertThat(server.workspace()).isEqualTo(other.toRealPath());
    }

    // ---------------------------------------------------------------- new page

    @Test
    void uploadAndConvertCsv() throws Exception {
        JsonNode up = upload("../Liste ä.csv", "Name;Ort\nAnna;Zürich\n".getBytes(StandardCharsets.UTF_8));
        assertThat(up.path("kind").asText()).isEqualTo("csv");
        assertThat(up.path("name").asText()).isEqualTo("Liste ä.csv");
        assertThat(up.has("sheets")).isFalse();

        JsonNode c = ok("POST", "/convert", Map.of("uploadId", up.path("uploadId").asText(), "title", "Liste"));
        assertThat(c.path("storage").asText()).contains("<h1>Liste</h1>").contains("<th>").contains("Zürich");
        assertThat(c.path("problems").isArray()).isTrue();

        Res bad = send(HttpRequest.newBuilder(uri("/api/upload?name=virus.exe")).header("X-CFH-Token", server.token())
                .POST(HttpRequest.BodyPublishers.ofString("x")));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(call("POST", "/convert", Map.of("uploadId", "gibtsnicht")).status()).isEqualTo(400);
    }

    @Test
    void uploadAndConvertExcelWithSheetSelection() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            fill(wb.createSheet("Umsatz"), "Monat", "Januar");
            fill(wb.createSheet("Geheim"), "Wert", "42");
            wb.setSheetVisibility(1, SheetVisibility.HIDDEN);
            fill(wb.createSheet("Kosten"), "Posten", "Miete");
            wb.write(bytes);
        }
        JsonNode up = upload("Zahlen.xlsx", bytes.toByteArray());
        assertThat(up.path("kind").asText()).isEqualTo("excel");
        assertThat(up.path("sheets").toString())
                .isEqualTo("[{\"name\":\"Umsatz\",\"hidden\":false},{\"name\":\"Geheim\",\"hidden\":true},"
                        + "{\"name\":\"Kosten\",\"hidden\":false}]");

        String all = ok("POST", "/convert", Map.of("uploadId", up.path("uploadId").asText())).path("storage").asText();
        assertThat(all).contains("Umsatz").contains("Kosten").doesNotContain("Geheim");
        String one = ok("POST", "/convert", Map.of("uploadId", up.path("uploadId").asText(), "sheets", List.of("Kosten")))
                .path("storage").asText();
        assertThat(one).contains("Miete").doesNotContain("Januar");
        Res missing = call("POST", "/convert", Map.of("uploadId", up.path("uploadId").asText(), "sheets", List.of("Nix")));
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.json().path("error").asText()).contains("Nix");
    }

    static void fill(Sheet sheet, String head, String value) {
        Row r0 = sheet.createRow(0);
        r0.createCell(0).setCellValue(head);
        sheet.createRow(1).createCell(0).setCellValue(value);
    }

    @Test
    void uploadTooLargeIsRejected() throws Exception {
        server.stop();
        server = start(new UiServer.Options().maxUpload(1000));
        Res r = send(HttpRequest.newBuilder(uri("/api/upload?name=gross.csv")).header("X-CFH-Token", server.token())
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[5000])));
        assertThat(r.status()).isEqualTo(413);
        assertThat(r.json().path("kind").asText()).isEqualTo("input");
        try (var files = Files.list(server.tempDir())) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void createPutsPageFolderIntoWorkspaceAndAttachesOriginal() throws Exception {
        JsonNode up = upload("Liste.csv", "A;B\n1;2\n".getBytes(StandardCharsets.UTF_8));
        String storage = ok("POST", "/convert", Map.of("uploadId", up.path("uploadId").asText())).path("storage").asText();

        JsonNode c = ok("POST", "/create", Map.of("storage", storage, "spaceKey", "DOC", "title", "Neue Liste",
                "parent", rootId, "attachUploadId", up.path("uploadId").asText()));
        String id = c.path("id").asText();
        assertThat(c.path("version").asInt()).isEqualTo(1);
        assertThat(c.path("dir").asText()).isEqualTo(Slugs.folderName(id, "Neue Liste"));
        assertThat(c.path("webUrl").asText()).contains(id);
        assertThat(c.path("log").toString()).contains("Anhang hochgeladen: Liste.csv");
        Path dir = workspace.resolve(c.path("dir").asText());
        assertThat(PageStore.readMeta(dir.resolve(PageStore.META)).orElseThrow().id()).isEqualTo(id);
        assertThat(Files.readString(dir.resolve(PageStore.XHTML))).isEqualTo(storage);
        assertThat(fake.page(id).parentId()).isEqualTo(rootId);
        assertThat(fake.attachmentBytes(id, "Liste.csv")).isEqualTo("A;B\n1;2\n".getBytes(StandardCharsets.UTF_8));

        Res dup = call("POST", "/create", Map.of("storage", storage, "spaceKey", "DOC", "title", "Neue Liste"));
        assertThat(dup.status()).isEqualTo(400);

        Res invalid = call("POST", "/create", Map.of("storage", "<p>offen", "spaceKey", "DOC", "title", "Kaputt"));
        assertThat(invalid.status()).isEqualTo(422);
        assertThat(invalid.json().path("kind").asText()).isEqualTo("validation");
        assertThat(invalid.json().path("problems").get(0).path("severity").asText()).isEqualTo("ERROR");
        assertThat(fake.findPage("DOC", "Kaputt")).isEmpty();

        assertThat(ok("POST", "/validate", Map.of("storage", "<p>offen")).path("problems").size()).isPositive();
    }

    // ---------------------------------------------------------------- export, edit, push

    @Test
    void exportWithChildrenAndAttachments() throws Exception {
        JsonNode e = ok("POST", "/export", Map.of("page", fake.url() + "/pages/viewpage.action?pageId=" + rootId,
                "attachments", true, "children", true));
        JsonNode results = e.path("results");
        assertThat(results.size()).isEqualTo(2);
        String rootDir = Slugs.folderName(rootId, "Handbuch");
        assertThat(results.get(0).path("dir").asText()).isEqualTo(rootDir);
        assertThat(results.get(0).path("attachments").asInt()).isEqualTo(1);
        assertThat(results.get(1).path("dir").asText()).isEqualTo(rootDir + "/" + Slugs.folderName(childId, "Kapitel Ä"));
        assertThat(workspace.resolve(rootDir).resolve("attachments/bild.png")).exists();
        assertThat(e.path("log").toString()).contains("2 Seiten exportiert.");

        ok("POST", "/open", Map.of("dir", rootDir));
        assertThat(opened).containsExactly(workspace.resolve(rootDir));
    }

    @Test
    void editDiffAndPush() throws Exception {
        Path dir = exportRoot();
        String rel = dir.getFileName().toString();

        JsonNode local = ok("GET", "/local", null);
        assertThat(local.path("pages").size()).isEqualTo(1);
        JsonNode entry = local.path("pages").get(0);
        assertThat(entry.path("dir").asText()).isEqualTo(rel);
        assertThat(entry.path("id").asText()).isEqualTo(rootId);
        assertThat(entry.path("spaceKey").asText()).isEqualTo("DOC");
        assertThat(entry.path("localChanged").asBoolean()).isFalse();

        JsonNode page = ok("GET", "/page?dir=" + URLEncoder.encode(rel, StandardCharsets.UTF_8), null);
        assertThat(page.path("storage").asText()).contains("Grüezi");
        assertThat(page.path("meta").path("version").asInt()).isEqualTo(1);

        String edited = "<h1>Handbuch</h1>\r\n<p>Grüezi mitenand</p>\n";
        JsonNode saved = ok("PUT", "/page", Map.of("dir", rel, "storage", edited));
        assertThat(saved.path("problems").size()).isZero();
        assertThat(Files.readAllBytes(dir.resolve(PageStore.XHTML))).isEqualTo(edited.getBytes(StandardCharsets.UTF_8));
        assertThat(ok("GET", "/local", null).path("pages").get(0).path("localChanged").asBoolean()).isTrue();

        JsonNode remote = ok("POST", "/remote", Map.of("dir", rel));
        assertThat(remote.path("localChanged").asBoolean()).isTrue();
        assertThat(remote.path("serverChanged").asBoolean()).isFalse();

        JsonNode diff = ok("POST", "/diff", Map.of("dir", rel));
        assertThat(diff.path("diff").asText()).contains("-<p>Grüezi</p>").contains("+<p>Grüezi mitenand</p>");
        assertThat(diff.path("added").asInt()).isEqualTo(1);
        assertThat(diff.path("serverVersion").asInt()).isEqualTo(1);

        JsonNode dry = ok("POST", "/push", Map.of("dir", rel, "dryRun", true));
        assertThat(dry.path("status").asText()).isEqualTo("dry-run");
        assertThat(dry.path("diff").asText()).contains("+<p>Grüezi mitenand</p>");
        assertThat(fake.page(rootId).version()).isEqualTo(1);

        JsonNode pushed = ok("POST", "/push", Map.of("dir", rel, "message", "aus der UI"));
        assertThat(pushed.path("status").asText()).isEqualTo("updated");
        assertThat(pushed.path("version").asInt()).isEqualTo(2);
        assertThat(fake.page(rootId).version()).isEqualTo(2);
        assertThat(fake.page(rootId).versionMessage()).isEqualTo("aus der UI");
        assertThat(fake.page(rootId).storage()).contains("Grüezi mitenand");
        assertThat(ok("POST", "/push", Map.of("dir", rel)).path("status").asText()).isEqualTo("unchanged");
    }

    @Test
    void pushConflictNeedsForce() throws Exception {
        Path dir = exportRoot();
        String rel = dir.getFileName().toString();
        ok("PUT", "/page", Map.of("dir", rel, "storage", "<p>lokal</p>"));
        fake.editPage(rootId, null, "<p>von jemand anderem</p>");

        assertThat(ok("POST", "/remote", Map.of("dir", rel)).path("serverChanged").asBoolean()).isTrue();
        Res conflict = call("POST", "/push", Map.of("dir", rel));
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.json().path("kind").asText()).isEqualTo("conflict");
        assertThat(fake.page(rootId).storage()).isEqualTo("<p>von jemand anderem</p>");

        JsonNode forced = ok("POST", "/push", Map.of("dir", rel, "force", true));
        assertThat(forced.path("version").asInt()).isEqualTo(3);
        assertThat(fake.page(rootId).storage()).isEqualTo("<p>lokal</p>");
    }

    @Test
    void exportRefusesToOverwriteLocalEditsWithoutForce() throws Exception {
        Path dir = exportRoot();
        ok("PUT", "/page", Map.of("dir", dir.getFileName().toString(), "storage", "<p>lokal</p>"));
        Res r = call("POST", "/export", Map.of("page", rootId));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.json().path("error").asText()).contains("lokale Änderungen");
        ok("POST", "/export", Map.of("page", rootId, "force", true));
        assertThat(dir.resolve(PageStore.XHTML + ".bak")).exists();
    }

    // ---------------------------------------------------------------- lifecycle

    @Test
    void shutdownStopsServerAndDeletesUploads() throws Exception {
        upload("a.csv", "x\n".getBytes(StandardCharsets.UTF_8));
        Path temp = server.tempDir();
        assertThat(temp).exists();
        assertThat(ok("POST", "/shutdown", Map.of()).size()).isZero();
        assertThat(server.awaitStop(Duration.ofSeconds(10))).isTrue();
        assertThat(temp).doesNotExist();
    }

    @Test
    void stopsAfterIdleTimeout() throws Exception {
        server.stop();
        server = start(new UiServer.Options().idleTimeout(Duration.ofMillis(300)));
        assertThat(server.awaitStop(Duration.ofSeconds(10))).isTrue();
        assertThat(server.isStopped()).isTrue();
    }

    @Test
    void confluenceErrorsMapToUiStatus() {
        var unreachable = UiApi.toError(new ConfluenceException("Verbindung zu wiki.firma.ch fehlgeschlagen: ConnectException",
                new IOException()));
        assertThat(unreachable.status).isEqualTo(502);
        assertThat(unreachable.kind).isEqualTo("other");
        assertThat(UiApi.toError(new ConfluenceException(503, "Wartung")).status).isEqualTo(502);
        assertThat(UiApi.toError(new ConfluenceException(400, "Ungültig")).status).isEqualTo(400);

        var conflict = UiApi.toError(new VersionConflictException("42", 2, 3));
        assertThat(conflict.status).isEqualTo(409);
        assertThat(conflict.getMessage()).isEqualTo("Lokal Version 2, auf dem Server Version 3.").doesNotContain("cfh");
    }

    @Test
    void openRefusesFoldersThatMacOsWouldLaunch() throws Exception {
        Path app = Files.createDirectories(workspace.resolve("Protokoll.app/Contents/MacOS"));
        Path bundle = app.getParent().getParent();
        Files.writeString(bundle.resolve("page.xhtml"), "<p/>");
        Files.writeString(bundle.resolve("page.json"), "{\"id\":\"1\"}");
        Path hidden = Files.createDirectories(workspace.resolve("Unauffaellig/Contents"));
        Files.writeString(hidden.resolve("Info.plist"), "<plist/>");

        for (String dir : List.of("Protokoll.app", "Unauffaellig")) {
            Res r = call("POST", "/open", Map.of("dir", dir));
            assertThat(r.status()).as(dir).isEqualTo(400);
            assertThat(r.json().path("error").asText()).contains("Programmpaket");
        }
        assertThat(opened).isEmpty();
        Path plain = exportRoot();
        ok("POST", "/open", Map.of("dir", plain.getFileName().toString()));
        assertThat(opened).containsExactly(plain);
    }

    @Test
    void systemLauncherOnlyRevealsFoldersOnMac() throws Exception {
        Path page = exportRoot();
        assertThat(SystemLauncher.openCommand(page, true))
                .containsExactly("open", "-R", page.resolve(PageStore.XHTML).toString());
        assertThat(SystemLauncher.openCommand(workspace, true)).containsExactly("open", "-R", workspace.toString());
        assertThat(SystemLauncher.openCommand(workspace, false)).containsExactly("explorer", workspace.toString());
    }

    @Test
    void storedTokenIsNeverSentToAnotherServer() throws Exception {
        String before = Files.readString(configFile);
        Res save = call("POST", "/config", Map.of("url", "http://127.0.0.1:9/confluence", "token", ""));
        assertThat(save.status()).isEqualTo(400);
        assertThat(save.json().path("kind").asText()).isEqualTo("config");
        assertThat(save.json().path("error").asText()).contains("Token neu eingeben");
        Res test = call("POST", "/whoami", Map.of("url", "http://127.0.0.1:9/confluence", "token", ""));
        assertThat(test.status()).isEqualTo(400);
        assertThat(Files.readString(configFile)).isEqualTo(before);
    }

    @Test
    void testingFormValuesDoesNotSaveThem() throws Exception {
        String before = Files.readString(configFile);
        Res wrong = call("POST", "/whoami", Map.of("url", fake.url().toString(), "token", "falsch"));
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(wrong.json().path("kind").asText()).isEqualTo("auth");
        assertThat(ok("POST", "/whoami", Map.of("url", fake.url().toString(), "token", "")).path("user").asText())
                .contains("Anna Muster");
        assertThat(Files.readString(configFile)).isEqualTo(before);
        assertThat(ok("POST", "/whoami", Map.of()).path("user").asText()).contains("Anna Muster");
    }

    @Test
    void profilesAreListedWithoutSecretsAndCanBeSwitchedWithoutWriting() throws Exception {
        Files.writeString(configFile, "url=" + fake.url() + "\ntoken=" + fake.token() + "\n"
                + "zweit.url=https://zweit.example.ch\nzweit.username=anna\nzweit.password=pw-geheim\n");
        String before = Files.readString(configFile);
        JsonNode s = ok("GET", "/status", null);
        assertThat(s.toString()).doesNotContain(fake.token()).doesNotContain("pw-geheim");
        JsonNode c = s.path("connections");
        assertThat(c.size()).isEqualTo(2);
        assertThat(c.get(0).path("profile").isNull()).isTrue();
        assertThat(c.get(0).path("url").asText()).isEqualTo(fake.url().toString());
        assertThat(c.get(0).path("auth").asText()).isEqualTo("token");
        assertThat(c.get(1).path("profile").asText()).isEqualTo("zweit");
        assertThat(c.get(1).path("url").asText()).isEqualTo("https://zweit.example.ch");
        assertThat(c.get(1).path("auth").asText()).isEqualTo("basic");
        assertThat(c.get(1).path("username").asText()).isEqualTo("anna");

        JsonNode z = ok("POST", "/profile", Map.of("profile", "zweit"));
        assertThat(z.path("profile").asText()).isEqualTo("zweit");
        assertThat(z.path("baseUrl").asText()).isEqualTo("https://zweit.example.ch");
        assertThat(ok("POST", "/profile", Map.of()).path("profile").isNull()).isTrue();
        assertThat(call("POST", "/profile", Map.of("profile", "gibtsnicht")).status()).isEqualTo(400);
        // saving the default profile with zweit's URL and no token would send the default token there
        assertThat(call("POST", "/config", Map.of("url", "https://zweit.example.ch", "token", "")).status())
                .isEqualTo(400);
        assertThat(Files.readString(configFile)).isEqualTo(before);
    }

    @Test
    void savingThePageDetectsChangesMadeOutsideTheEditor() throws Exception {
        Path dir = exportRoot();
        String rel = dir.getFileName().toString();
        String hash = ok("GET", "/page?dir=" + rel, null).path("hash").asText();
        assertThat(hash).hasSize(64);
        Files.writeString(dir.resolve(PageStore.XHTML), "<p>Stand A</p>\n<p>Absatz von Claude Desktop</p>\n");

        Res stale = call("PUT", "/page", Map.of("dir", rel, "storage", "<p>aus der UI</p>", "hash", hash));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.json().path("kind").asText()).isEqualTo("conflict");
        assertThat(stale.json().path("reason").asText()).isEqualTo("changedOnDisk");
        assertThat(dir.resolve(PageStore.XHTML)).content(StandardCharsets.UTF_8).contains("Claude Desktop");

        String fresh = ok("GET", "/page?dir=" + rel, null).path("hash").asText();
        JsonNode saved = ok("PUT", "/page", Map.of("dir", rel, "storage", "<p>neu</p>", "hash", fresh));
        assertThat(ok("GET", "/page?dir=" + rel, null).path("hash").asText()).isEqualTo(saved.path("hash").asText());
        ok("PUT", "/page", Map.of("dir", rel, "storage", "<p>trotzdem</p>", "hash", fresh, "force", true));
        assertThat(dir.resolve(PageStore.XHTML)).hasContent("<p>trotzdem</p>");
    }

    @Test
    void uiErrorsNameUiControlsNotCliOptions() throws Exception {
        Path dir = exportRoot();
        String rel = dir.getFileName().toString();

        // export over local edits
        ok("PUT", "/page", Map.of("dir", rel, "storage", "<p>lokal</p>"));
        Res local = call("POST", "/export", Map.of("page", rootId));
        assertThat(local.json().path("reason").asText()).isEqualTo("localChanges");
        assertThat(local.json().path("dir").asText()).isEqualTo(rel);
        assertThat(local.json().path("error").asText()).contains("«" + rel + "»", "Lokale Änderungen überschreiben")
                .doesNotContain("cfh").doesNotContain("--").doesNotContain(workspace.toString());

        // rename to a title that exists in the space
        Res title = call("POST", "/push", Map.of("dir", rel, "title", "Kapitel Ä"));
        assertThat(title.status()).isEqualTo(400);
        assertThat(title.json().path("reason").asText()).isEqualTo("titleExists");
        assertThat(title.json().path("error").asText()).contains("anderen Titel wählen").doesNotContain("cfh");

        // create with an existing title
        Res create = call("POST", "/create", Map.of("storage", "<p/>", "spaceKey", "DOC", "title", "Handbuch"));
        assertThat(create.json().path("reason").asText()).isEqualTo("titleExists");
        assertThat(create.json().path("error").asText()).doesNotContain("cfh");
        assertThat(call("POST", "/create", Map.of("storage", "<p/>", "spaceKey", "DOC")).json().path("error").asText())
                .doesNotContain("--title");

        // page folder from another server address (localhost instead of 127.0.0.1)
        Path meta = dir.resolve(PageStore.META);
        String other = fake.url().toString().replace("127.0.0.1", "localhost");
        Files.writeString(meta, Files.readString(meta).replace(fake.url().toString(), other));
        Res remote = call("POST", "/remote", Map.of("dir", rel));
        assertThat(remote.status()).isEqualTo(400);
        assertThat(remote.json().path("reason").asText()).isEqualTo("otherServer");
        assertThat(remote.json().path("serverUrl").asText()).isEqualTo(other);
        assertThat(remote.json().has("switchProfile")).isFalse();
        assertThat(remote.json().path("error").asText()).contains(other).doesNotContain("--");
        Files.writeString(configFile, Files.readString(configFile) + "lokal.url=" + other + "\nlokal.token=x\n");
        assertThat(call("POST", "/diff", Map.of("dir", rel)).json().path("switchProfile").asText()).isEqualTo("lokal");

        // CSV too large: no Excel option in the hint
        String csv = "a\n" + "\n".repeat(1000) + ";".repeat(1000) + "b\n";
        JsonNode up = upload("gross.csv", csv.getBytes(StandardCharsets.UTF_8));
        Res big = call("POST", "/convert", Map.of("uploadId", up.path("uploadId").asText()));
        assertThat(big.status()).isEqualTo(400);
        assertThat(big.json().path("error").asText()).contains("Tabelle zu gross").doesNotContain("--sheet");

        // Confluence 4xx text is not prefixed twice
        assertThat(UiApi.toError(new ConfluenceException(400, "Confluence-Fehler (HTTP 400). Server: kaputt"))
                .getMessage()).isEqualTo("Confluence-Fehler (HTTP 400). Server: kaputt");
    }

    @Test
    void exportRefusesUrlsOfAnotherServer() throws Exception {
        Res r = call("POST", "/export", Map.of("page", "https://anderes.wiki.ch/pages/viewpage.action?pageId=" + rootId));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.json().path("error").asText()).contains("anderes.wiki.ch");
        assertThat(Files.list(workspace).count()).isZero();
        Res parent = call("POST", "/create", Map.of("storage", "<p/>", "spaceKey", "DOC", "title", "Neu",
                "parent", "https://anderes.wiki.ch/pages/viewpage.action?pageId=" + rootId));
        assertThat(parent.status()).isEqualTo(400);
        assertThat(fake.page(rootId).title()).isEqualTo("Handbuch");
    }

    @Test
    void refreshKeepsLocalEditsAsBackupAndAllowsANormalPush() throws Exception {
        Path dir = exportRoot();
        String rel = dir.getFileName().toString();
        ok("PUT", "/page", Map.of("dir", rel, "storage", "<p>lokal</p>"));
        fake.editPage(rootId, null, "<p>von jemand anderem</p>");
        assertThat(call("POST", "/push", Map.of("dir", rel)).status()).isEqualTo(409);

        JsonNode r = ok("POST", "/refresh", Map.of("dir", rel));
        assertThat(r.path("version").asInt()).isEqualTo(2);
        assertThat(r.path("backup").asText()).isEqualTo("page.xhtml.bak");
        assertThat(r.path("dir").asText()).isEqualTo(rel);
        assertThat(dir.resolve("page.xhtml.bak")).hasContent("<p>lokal</p>");
        assertThat(dir.resolve(PageStore.XHTML)).hasContent("<p>von jemand anderem</p>");

        ok("PUT", "/page", Map.of("dir", rel, "storage", "<p>von jemand anderem</p><p>lokal</p>"));
        assertThat(ok("POST", "/push", Map.of("dir", rel)).path("version").asInt()).isEqualTo(3);
        assertThat(ok("POST", "/refresh", Map.of("dir", rel)).path("backup").isNull()).isTrue();
    }

    @Test
    void unavailableStoredWorkspaceFallsBackInsteadOfFailing() throws Exception {
        server.stop();
        Path blocker = Files.writeString(tmp.resolve("keinordner"), "x");
        Path props = tmp.resolve("cfg/ui.properties");
        Files.writeString(props, "workspace=" + blocker.resolve("Confluence") + "\n");
        String home = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString());
        try {
            server = UiServer.start(new UiServer.Options().env(Map.of(Config.ENV_CONFIG, configFile.toString()))
                    .uiProperties(props).log(new PrintStream(log, true, StandardCharsets.UTF_8)).launcher(Launcher.NONE));
            assertThat(server.workspace()).isEqualTo(tmp.resolve("home/Confluence").toRealPath());
            JsonNode s = ok("GET", "/status", null);
            assertThat(s.path("workspaceWarning").asText()).contains(blocker.resolve("Confluence").toString());
            JsonNode changed = ok("POST", "/workspace", Map.of("path", workspace.toString()));
            assertThat(changed.path("workspaceWarning").isNull()).isTrue();
        } finally {
            System.setProperty("user.home", home);
        }
        server.stop();
        UiServer.Options explicit = new UiServer.Options().env(Map.of(Config.ENV_CONFIG, configFile.toString()))
                .workspace(blocker.resolve("Confluence")).launcher(Launcher.NONE);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> UiServer.start(explicit))
                .hasMessageContaining(blocker.resolve("Confluence").toString()).hasMessageContaining("--workspace");
    }

    // ---------------------------------------------------------------- VS Code / IntelliJ

    @Test
    void workspaceIsNotSetUpUnasked() throws Exception {
        assertThat(workspace.resolve(".github")).doesNotExist();
        assertThat(ok("GET", "/status", null).path("copilotSetup").asBoolean()).isFalse();
    }

    @Test
    void workspaceInitWritesProjectFilesAndReportsUserChanges() throws Exception {
        Path rootDir = exportRoot();
        JsonNode r = ok("POST", "/workspace/init", Map.of());
        assertThat(r.path("dir").asText()).isEqualTo(workspace.toString());
        assertThat(r.path("skipped").asBoolean()).isTrue(); // no cfh.jar in tests: no IntelliJ run configurations
        assertThat(r.path("files").findValuesAsText("path")).contains(".github/copilot-instructions.md",
                ".vscode/tasks.json", "AGENTS.md");
        assertThat(r.path("log").toString()).contains("angelegt:");
        assertThat(workspace.resolve(".github/copilot-instructions.md")).exists();
        assertThat(ok("GET", "/status", null).path("copilotSetup").asBoolean()).isTrue();
        // setup folders are no pages
        assertThat(ok("GET", "/local", null).path("pages").findValuesAsText("dir"))
                .containsExactly(workspace.relativize(rootDir).toString().replace('\\', '/'));

        Files.writeString(workspace.resolve(".vscode/settings.json"), "{}");
        JsonNode again = ok("POST", "/workspace/init", Map.of());
        assertThat(again.path("files").toString()).contains("\"path\":\".vscode/settings.json\",\"status\":\"skipped\"");
        assertThat(workspace.resolve(".vscode/settings.json")).hasContent("{}");
        JsonNode forced = ok("POST", "/workspace/init", Map.of("force", true));
        assertThat(forced.path("files").toString()).contains("\"status\":\"replaced\"");
        assertThat(workspace.resolve(".vscode/settings.json.bak")).hasContent("{}");
    }

    @Test
    void openIdeOpensTheWorkspaceAndSetsItUpFirst() throws Exception {
        JsonNode r = ok("POST", "/open-ide", Map.of("ide", "vscode"));
        assertThat(r.path("ide").asText()).isEqualTo("VS Code");
        assertThat(ideCalls).containsExactly("VSCODE " + workspace + " null");
        assertThat(WorkspaceSetup.isSetUp(workspace)).isTrue();

        Path rootDir = exportRoot();
        String rel = workspace.relativize(rootDir).toString();
        ok("POST", "/open-ide", Map.of("ide", "intellij", "dir", rel));
        assertThat(ideCalls).last().isEqualTo("INTELLIJ " + workspace + " " + rootDir.resolve("page.xhtml"));

        assertThat(call("POST", "/open-ide", Map.of("ide", "notepad")).status()).isEqualTo(400);
        Res outside = call("POST", "/open-ide", Map.of("ide", "vscode", "dir", "../"));
        assertThat(outside.status()).isEqualTo(400);
        assertThat(outside.json().path("error").asText()).contains("ausserhalb");
        assertThat(call("POST", "/open-ide", Map.of("ide", "vscode", "dir", ".github")).status()).isEqualTo(400);

        ideFound = false;
        Res missing = call("POST", "/open-ide", Map.of("ide", "intellij"));
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.json().path("error").asText()).contains("IntelliJ IDEA wurde nicht gefunden");
        assertThat(call("GET", "/open-ide", null).status()).isEqualTo(405);
    }

    @Test
    void folderSetUpBeforeIsRefreshedOnStart() throws Exception {
        server.stop();
        Files.createDirectories(workspace.resolve(".cfh"));
        Files.writeString(workspace.resolve(WorkspaceSetup.MANIFEST), "{\"files\":{}}");
        Path repo = tmp.resolve("inst");
        server = start(new UiServer.Options().installation(new Installation(repo.resolve("bin/cfh").toString(),
                "cfh.cmd", repo.resolve("target/cfh.jar"), repo)));
        assertThat(workspace.resolve(".vscode/tasks.json")).content().contains(repo.resolve("bin/cfh").toString());
        assertThat(workspace.resolve(".run/cfh-pruefen.run.xml")).exists();
    }

    @Test
    void ideCommandsAreArgumentListsWithoutShell() {
        Path home = Path.of("/Users/anna");
        Path project = Path.of("/Users/anna/Confluence");
        Path file = project.resolve("1-a b/page.xhtml");
        Map<String, String> env = Map.of("PATH", "relative/bin" + java.io.File.pathSeparator + "/usr/local/bin");
        java.util.Set<Path> present = new java.util.HashSet<>();
        SystemLauncher.Probe probe = new SystemLauncher.Probe() {
            @Override public boolean exists(Path p) { return present.contains(p); }
            @Override public List<Path> children(Path dir) {
                return present.stream().filter(p -> dir.equals(p.getParent())).sorted().toList();
            }
        };
        Launcher.Ide vs = Launcher.Ide.VSCODE;
        Launcher.Ide ij = Launcher.Ide.INTELLIJ;

        assertThat(SystemLauncher.ideCommand(vs, project, file, true, false, probe, env, home)).isNull();
        present.add(Path.of("relative/bin/code"));
        assertThat(SystemLauncher.ideCommand(vs, project, file, true, false, probe, env, home)).isNull();
        present.add(Path.of("/Applications/Visual Studio Code.app"));
        assertThat(SystemLauncher.ideCommand(vs, project, file, true, false, probe, env, home))
                .containsExactly("open", "-a", "/Applications/Visual Studio Code.app", project.toString());
        present.add(Path.of("/usr/local/bin/code"));
        assertThat(SystemLauncher.ideCommand(vs, project, file, true, false, probe, env, home))
                .containsExactly("/usr/local/bin/code", project.toString(), file.toString());

        present.add(home.resolve("Applications/IntelliJ IDEA Ultimate.app"));
        assertThat(SystemLauncher.ideCommand(ij, project, file, true, false, probe, env, home))
                .containsExactly("open", "-na", home.resolve("Applications/IntelliJ IDEA Ultimate.app").toString(),
                        "--args", project.toString());
        assertThat(SystemLauncher.ideCommand(ij, project, file, false, false, probe, env, home)).isNull();
        present.add(Path.of("/usr/local/bin/intellij-idea-community"));
        assertThat(SystemLauncher.ideCommand(ij, project, null, false, false, probe, env, home))
                .containsExactly("/usr/local/bin/intellij-idea-community", project.toString());

        // Windows: the .exe, never a .cmd that cmd.exe would parse
        Path local = Path.of("/win/AppData/Local");
        Path programs = Path.of("/win/Program Files");
        Map<String, String> win = Map.of("LOCALAPPDATA", local.toString(), "ProgramFiles", programs.toString());
        present.add(programs.resolve("Microsoft VS Code/Code.exe"));
        assertThat(SystemLauncher.ideCommand(vs, project, file, false, true, probe, win, home))
                .containsExactly(programs.resolve("Microsoft VS Code/Code.exe").toString(), project.toString(), file.toString());
        present.add(programs.resolve("JetBrains/IntelliJ IDEA 2025.3"));
        present.add(programs.resolve("JetBrains/IntelliJ IDEA 2026.2"));
        present.add(programs.resolve("JetBrains/IntelliJ IDEA 2025.3/bin/idea64.exe"));
        present.add(programs.resolve("JetBrains/IntelliJ IDEA 2026.2/bin/idea64.exe"));
        assertThat(SystemLauncher.ideCommand(ij, project, file, false, true, probe, win, home))
                .containsExactly(programs.resolve("JetBrains/IntelliJ IDEA 2026.2/bin/idea64.exe").toString(),
                        project.toString());
    }
}
