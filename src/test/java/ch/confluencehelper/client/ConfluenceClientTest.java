package ch.confluencehelper.client;

import ch.confluencehelper.config.Config;
import ch.confluencehelper.testing.FakeConfluenceServer;
import ch.confluencehelper.testing.FakeConfluenceServer.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class ConfluenceClientTest {

    @TempDir
    Path tmp;

    private FakeConfluenceServer fake;

    @AfterEach
    void stop() {
        if (fake != null) {
            fake.close();
        }
    }

    private ConfluenceClient start(String context) {
        fake = FakeConfluenceServer.start(context);
        fake.addSpace("DOC");
        return new ConfluenceClient(fake.config(), false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/confluence"})
    void currentUserWithBearerTokenAndContextPath(String context) {
        ConfluenceClient client = start(context);
        fake.setCurrentUser("mmuster", "Max Muster");

        assertThat(client.currentUser()).isEqualTo("Max Muster (mmuster)");
        assertThat(client.baseUrl()).isEqualTo(fake.url());
        RecordedRequest r = fake.requests().get(0);
        assertThat(r.rawPath()).isEqualTo(context + "/rest/api/user/current");
        assertThat(r.header("Authorization")).isEqualTo("Bearer " + fake.token());
        assertThat(r.header("Accept")).isEqualTo("application/json");
    }

    @Test
    void baseUrlWithTrailingSlashIsJoinedCleanly() {
        start("/confluence");
        ConfluenceClient client = new ConfluenceClient(
                new Config(URI.create(fake.url() + "/"), fake.token(), null, null), false);
        client.currentUser();
        assertThat(fake.requests().get(0).rawPath()).isEqualTo("/confluence/rest/api/user/current");
    }

    @Test
    void basicAuthFallback() {
        start("");
        fake.enableBasicAuth("mmuster", "pässwort");
        ConfluenceClient client = new ConfluenceClient(
                new Config(fake.url(), null, "mmuster", "pässwort"), false);
        assertThat(client.currentUser()).isEqualTo("Fake User (fake.user)");
        assertThat(fake.requests().get(0).header("Authorization")).startsWith("Basic ");
    }

    @Test
    void wrongTokenGives401WithoutLeakingToken() {
        start("");
        ConfluenceClient client = new ConfluenceClient(
                new Config(fake.url(), "wrong-secret-token", null, null), false);
        ConfluenceException e = catchThrowableOfType(ConfluenceException.class, client::currentUser);
        assertThat(e.status()).isEqualTo(401);
        assertThat(e.isAuthProblem()).isTrue();
        assertThat(e.getMessage()).contains("Token").doesNotContain("wrong-secret-token");
    }

    @Test
    void forbiddenCarriesServerMessage() {
        ConfluenceClient client = start("");
        String id = fake.addPage("DOC", null, "Home", "<p/>");
        fake.failNext(403, "User not permitted to view content");
        ConfluenceException e = catchThrowableOfType(ConfluenceException.class, () -> client.getPage(id));
        assertThat(e.status()).isEqualTo(403);
        assertThat(e.isAuthProblem()).isTrue();
        assertThat(e.getMessage()).contains("Keine Berechtigung", "User not permitted to view content");
    }

    @Test
    void getPageWithAncestorsVersionAndWebUrl() {
        ConfluenceClient client = start("/confluence");
        String root = fake.addPage("DOC", null, "Root", "<p>root</p>");
        String mid = fake.addPage("DOC", root, "Mid", "<p>mid</p>");
        String leaf = fake.addPage("DOC", mid, "Leaf", "<p>Grüezi &amp; <ac:emoticon ac:name=\"smile\" /></p>");
        fake.editPage(leaf, null, null);

        Page p = client.getPage(leaf);
        assertThat(p.id()).isEqualTo(leaf);
        assertThat(p.title()).isEqualTo("Leaf");
        assertThat(p.spaceKey()).isEqualTo("DOC");
        assertThat(p.version()).isEqualTo(2);
        assertThat(p.parentId()).isEqualTo(mid);
        assertThat(p.storage()).isEqualTo("<p>Grüezi &amp; <ac:emoticon ac:name=\"smile\" /></p>");
        assertThat(p.webUrl()).isEqualTo(fake.url() + "/pages/viewpage.action?pageId=" + leaf);
        assertThat(fake.requests().get(0).param("expand")).isEqualTo("body.storage,version,space,ancestors");

        assertThat(client.getPage(root).parentId()).isNull();
    }

    @Test
    void missingPageGives404() {
        ConfluenceClient client = start("");
        ConfluenceException e = catchThrowableOfType(ConfluenceException.class, () -> client.getPage("999"));
        assertThat(e.status()).isEqualTo(404);
        assertThat(e.getMessage()).contains("Seite nicht gefunden oder keine Leserechte", "No content found");
    }

    @Test
    void wrongContextPathGives404() {
        start("/confluence");
        ConfluenceClient client = new ConfluenceClient(
                new Config(URI.create("http://127.0.0.1:" + fake.url().getPort()), fake.token(), null, null), false);
        assertThatThrownBy(client::currentUser).isInstanceOf(ConfluenceException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Plan & Budget + Q1", "Übersicht Ä ö é", "a=b?c#d/e", "100% sicher"})
    void findPageEncodesTitle(String title) {
        ConfluenceClient client = start("/confluence");
        fake.addPage("DOC", null, "Plan", "<p/>");
        String id = fake.addPage("DOC", null, title, "<p>x</p>");

        Optional<Page> found = client.findPage("DOC", title);
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(id);
        assertThat(found.get().storage()).isEqualTo("<p>x</p>");
        RecordedRequest r = fake.requests().get(0);
        assertThat(r.param("title")).isEqualTo(title);
        assertThat(r.param("spaceKey")).isEqualTo("DOC");
        assertThat(r.query()).doesNotContain(" ").doesNotContain("+");
    }

    @Test
    void findPageMatchesTitlesCaseInsensitivelyLikeConfluence() {
        ConfluenceClient client = start("");
        String id = fake.addPage("DOC", null, "Projektplan", "<p>x</p>");

        assertThat(client.findPage("DOC", "projektplan")).map(Page::id).contains(id);
        assertThat(client.resolve(PageRefs.parse(fake.url() + "/display/DOC/PROJEKTPLAN")).id()).isEqualTo(id);
    }

    @Test
    void uploadAddsVersionToGivenAttachmentUnderItsOriginalName() throws Exception {
        ConfluenceClient client = start("");
        String pageId = fake.addPage("DOC", null, "Seite", "<p/>");
        String attId = fake.addAttachment(pageId, "Plan: Q3.pdf", "application/pdf", "alt".getBytes());
        Path local = Files.writeString(tmp.resolve("Plan_ Q3.pdf"), "neu");

        Attachment a = client.upload(pageId, local, "Plan: Q3.pdf", attId, null);

        assertThat(a.id()).isEqualTo(attId);
        assertThat(fake.attachments(pageId)).hasSize(1);
        assertThat(fake.attachmentBytes(pageId, "Plan: Q3.pdf")).isEqualTo("neu".getBytes());
    }

    @Test
    void findPageNotFound() {
        ConfluenceClient client = start("");
        assertThat(client.findPage("DOC", "Gibt es nicht")).isEmpty();
    }

    @Test
    void resolveByIdAndTitle() {
        ConfluenceClient client = start("");
        String id = fake.addPage("DOC", null, "Title Mit Plus", "<p/>");
        assertThat(client.resolve(PageRef.ofId(id)).title()).isEqualTo("Title Mit Plus");
        assertThat(client.resolve(PageRefs.parse("https://x/display/DOC/Title+Mit+Plus")).id()).isEqualTo(id);
        ConfluenceException e = catchThrowableOfType(ConfluenceException.class,
                () -> client.resolve(PageRef.ofTitle("DOC", "Fehlt")));
        assertThat(e.status()).isEqualTo(404);
    }

    @Test
    void childrenArePagedCompletely() {
        ConfluenceClient client = start("/confluence");
        String parent = fake.addPage("DOC", null, "Parent", "<p/>");
        for (int i = 0; i < 230; i++) {
            fake.addPage("DOC", parent, "Child " + i, "<p>" + i + "</p>");
        }
        fake.addPage("DOC", null, "Other", "<p/>");
        fake.clearRequests();

        List<Page> kids = client.children(parent);
        assertThat(kids).hasSize(230);
        assertThat(kids.get(0).title()).isEqualTo("Child 0");
        assertThat(kids.get(229).title()).isEqualTo("Child 229");
        assertThat(kids).allSatisfy(k -> {
            assertThat(k.parentId()).isEqualTo(parent);
            assertThat(k.storage()).isNull();
            assertThat(k.version()).isEqualTo(1);
            assertThat(k.spaceKey()).isEqualTo("DOC");
            assertThat(k.webUrl()).startsWith(fake.url() + "/pages/viewpage.action?pageId=");
        });
        assertThat(fake.requests()).extracting(r -> r.param("start")).containsExactly("0", "100", "200");
    }

    @Test
    void childrenOfLeafIsEmpty() {
        ConfluenceClient client = start("");
        String id = fake.addPage("DOC", null, "Leaf", "<p/>");
        assertThat(client.children(id)).isEmpty();
    }

    @Test
    void createPageUnderParent() {
        ConfluenceClient client = start("/confluence");
        String parent = fake.addPage("DOC", null, "Parent", "<p/>");

        Page p = client.createPage("DOC", parent, "Neue Seite: Ä & +", "<h1>Hallo</h1>");
        assertThat(p.id()).isNotBlank();
        assertThat(p.version()).isEqualTo(1);
        assertThat(p.parentId()).isEqualTo(parent);
        assertThat(p.storage()).isEqualTo("<h1>Hallo</h1>");
        assertThat(p.webUrl()).isEqualTo(fake.url() + "/pages/viewpage.action?pageId=" + p.id());

        FakeConfluenceServer.PageState s = fake.page(p.id());
        assertThat(s.title()).isEqualTo("Neue Seite: Ä & +");
        assertThat(s.parentId()).isEqualTo(parent);
        assertThat(s.storage()).isEqualTo("<h1>Hallo</h1>");
        RecordedRequest post = fake.requests("POST").get(0);
        assertThat(post.header("Content-Type")).startsWith("application/json");
        assertThat(post.bodyText()).contains("\"representation\":\"storage\"", "\"type\":\"page\"");
    }

    @Test
    void createTopLevelPageSendsNoAncestors() {
        ConfluenceClient client = start("");
        Page p = client.createPage("DOC", null, "Top", "<p/>");
        assertThat(p.parentId()).isNull();
        assertThat(fake.requests("POST").get(0).bodyText()).doesNotContain("ancestors");
    }

    @Test
    void createDuplicateTitleFailsWithServerMessage() {
        ConfluenceClient client = start("");
        fake.addPage("DOC", null, "Doppelt", "<p/>");
        ConfluenceException e = catchThrowableOfType(ConfluenceException.class,
                () -> client.createPage("DOC", null, "Doppelt", "<p/>"));
        assertThat(e.status()).isEqualTo(400);
        assertThat(e.getMessage()).contains("A page with this title already exists");
    }

    @Test
    void updatePageIncrementsVersionAndKeepsParent() {
        ConfluenceClient client = start("/confluence");
        String parent = fake.addPage("DOC", null, "Parent", "<p/>");
        String id = fake.addPage("DOC", parent, "Seite", "<p>alt</p>");

        Page p = client.updatePage(id, "Seite neu", 2, "<p>neu</p>", "via cfh");
        assertThat(p.version()).isEqualTo(2);
        assertThat(p.title()).isEqualTo("Seite neu");
        assertThat(p.storage()).isEqualTo("<p>neu</p>");

        FakeConfluenceServer.PageState s = fake.page(id);
        assertThat(s.version()).isEqualTo(2);
        assertThat(s.parentId()).isEqualTo(parent);
        assertThat(s.versionMessage()).isEqualTo("via cfh");
        assertThat(fake.requests("PUT").get(0).bodyText()).doesNotContain("ancestors");
    }

    @Test
    void updateWithStaleVersionIsConflict() {
        ConfluenceClient client = start("");
        String id = fake.addPage("DOC", null, "Seite", "<p>alt</p>");
        fake.editPage(id, null, "<p>fremd</p>");

        ConfluenceException e = catchThrowableOfType(ConfluenceException.class,
                () -> client.updatePage(id, "Seite", 2, "<p>neu</p>", null));
        assertThat(e.status()).isEqualTo(409);
        assertThat(e.getMessage()).contains("Versionskonflikt", "Current version is: 2");
        assertThat(fake.page(id).storage()).isEqualTo("<p>fremd</p>");
        assertThat(fake.requests("PUT")).hasSize(1); // no retry
    }

    @Test
    void attachmentsArePaged() {
        ConfluenceClient client = start("");
        String id = fake.addPage("DOC", null, "Seite", "<p/>");
        for (int i = 0; i < 120; i++) {
            fake.addAttachment(id, "file" + i + ".txt", "text/plain", ("data" + i).getBytes(StandardCharsets.UTF_8));
        }
        List<Attachment> list = client.attachments(id);
        assertThat(list).hasSize(120);
        Attachment a = list.get(5);
        assertThat(a.title()).isEqualTo("file5.txt");
        assertThat(a.mediaType()).isEqualTo("text/plain");
        assertThat(a.size()).isEqualTo(5);
        assertThat(a.version()).isEqualTo(1);
        assertThat(a.downloadPath()).startsWith("/download/attachments/" + id + "/file5.txt?version=1");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void downloadResolvesAgainstContextPath(boolean linksIncludeContext) throws Exception {
        ConfluenceClient client = start("/confluence");
        fake.downloadLinksIncludeContext(linksIncludeContext);
        String id = fake.addPage("DOC", null, "Seite", "<p/>");
        byte[] data = new byte[70_000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        fake.addAttachment(id, "Bericht 2024 Ü+.pdf", "application/pdf", data);

        Attachment a = client.attachments(id).get(0);
        assertThat(a.downloadPath().startsWith("/confluence/")).isEqualTo(linksIncludeContext);
        Path target = tmp.resolve("out/Bericht 2024 Ü+.pdf");
        client.download(a, target);

        assertThat(Files.readAllBytes(target)).isEqualTo(data);
        RecordedRequest r = fake.requests().get(fake.requests().size() - 1);
        assertThat(r.rawPath()).startsWith("/confluence/download/attachments/" + id + "/");
        assertThat(r.header("Authorization")).isEqualTo("Bearer " + fake.token());
        try (var files = Files.list(target.getParent())) {
            assertThat(files).hasSize(1); // no temp file left
        }
    }

    @Test
    void downloadErrorLeavesNoFile() {
        ConfluenceClient client = start("");
        Attachment ghost = new Attachment("att1", "x.txt", "text/plain", 1, 1, "/download/attachments/1/x.txt?version=1");
        Path target = tmp.resolve("x.txt");
        ConfluenceException e = catchThrowableOfType(ConfluenceException.class, () -> client.download(ghost, target));
        assertThat(e.status()).isEqualTo(404);
        assertThat(target).doesNotExist();
    }

    @Test
    void downloadUriRejectsForeignHost() {
        ConfluenceClient client = new ConfluenceClient(
                new Config(URI.create("https://wiki.firma.ch/confluence"), "t", null, null), false);
        assertThat(client.downloadUri("/download/attachments/1/a.txt?version=1"))
                .isEqualTo(URI.create("https://wiki.firma.ch/confluence/download/attachments/1/a.txt?version=1"));
        assertThat(client.downloadUri("/confluence/download/attachments/1/a.txt"))
                .isEqualTo(URI.create("https://wiki.firma.ch/confluence/download/attachments/1/a.txt"));
        assertThat(client.downloadUri("https://wiki.firma.ch/confluence/download/attachments/1/a.txt"))
                .hasHost("wiki.firma.ch");
        assertThatThrownBy(() -> client.downloadUri("https://evil.example/download/attachments/1/a.txt"))
                .isInstanceOf(ConfluenceException.class);
    }

    @Test
    void uploadCreatesThenVersionsAttachment() throws Exception {
        ConfluenceClient client = start("/confluence");
        String id = fake.addPage("DOC", null, "Seite", "<p/>");
        Path file = tmp.resolve("Offerte Müller v1.csv");
        Files.writeString(file, "a;b\n1;2\n", StandardCharsets.UTF_8);

        Attachment first = client.upload(id, file, "Erstimport");
        assertThat(first.title()).isEqualTo("Offerte Müller v1.csv");
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.size()).isEqualTo(8);
        assertThat(fake.attachments(id)).singleElement().satisfies(a -> {
            assertThat(a.comment()).isEqualTo("Erstimport");
            assertThat(a.version()).isEqualTo(1);
        });

        Files.writeString(file, "a;b\n1;2\n3;4\n", StandardCharsets.UTF_8);
        Attachment second = client.upload(id, file, null);
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.version()).isEqualTo(2);
        assertThat(fake.attachments(id)).hasSize(1);
        assertThat(fake.attachmentBytes(id, "Offerte Müller v1.csv"))
                .isEqualTo("a;b\n1;2\n3;4\n".getBytes(StandardCharsets.UTF_8));
        assertThat(fake.attachmentBytes(id, "Offerte Müller v1.csv", 1))
                .isEqualTo("a;b\n1;2\n".getBytes(StandardCharsets.UTF_8));

        List<RecordedRequest> posts = fake.requests("POST");
        assertThat(posts).hasSize(2);
        assertThat(posts.get(0).path()).isEqualTo("/rest/api/content/" + id + "/child/attachment");
        assertThat(posts.get(1).path()).isEqualTo("/rest/api/content/" + id + "/child/attachment/" + first.id() + "/data");
        assertThat(posts).allSatisfy(p -> {
            assertThat(p.header("X-Atlassian-Token")).isEqualTo("no-check");
            assertThat(p.header("Content-Type")).startsWith("multipart/form-data; boundary=");
        });
    }

    @Test
    void verboseLogsMethodAndPathButNoHeaders() {
        start("/confluence");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ConfluenceClient client = new ConfluenceClient(fake.config(), true,
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String id = fake.addPage("DOC", null, "Seite", "<p/>");
        client.getPage(id);

        String log = bytes.toString(StandardCharsets.UTF_8);
        assertThat(log).contains("> GET /confluence/rest/api/content/" + id + "?expand=", "< 200");
        assertThat(log).doesNotContain(fake.token()).doesNotContainIgnoringCase("authorization");
    }

    @Test
    void notVerboseLogsNothing() {
        start("");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ConfluenceClient client = new ConfluenceClient(fake.config(), false,
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        client.currentUser();
        assertThat(bytes.size()).isZero();
    }

    @Test
    void connectionRefusedIsTransportError() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        ConfluenceClient client = new ConfluenceClient(
                new Config(URI.create("http://127.0.0.1:" + port), "secret-token", null, null), false);
        ConfluenceException e = catchThrowableOfType(ConfluenceException.class, client::currentUser);
        assertThat(e.status()).isZero();
        assertThat(e.getMessage()).contains("127.0.0.1").doesNotContain("secret-token");
    }

    @Test
    void paramEncoding() {
        assertThat(ConfluenceClient.param("A & B + C/ä")).isEqualTo("A%20%26%20B%20%2B%20C%2F%C3%A4");
    }
}
