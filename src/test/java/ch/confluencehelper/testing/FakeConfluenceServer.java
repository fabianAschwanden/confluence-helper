package ch.confluencehelper.testing;

import ch.confluencehelper.config.Config;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory fake of the Confluence Data Center REST API v1 subset used by confluence-helper
 * (see docs/SPEC.md), for unit and end-to-end tests.
 *
 * <pre>{@code
 * try (FakeConfluenceServer fake = FakeConfluenceServer.start("/confluence")) {
 *     fake.addSpace("DOC");
 *     String id = fake.addPage("DOC", null, "Home", "<p>Hi</p>");
 *     ConfluenceClient client = new ConfluenceClient(fake.config(), false);
 *     ...
 *     assertThat(fake.page(id).version()).isEqualTo(2);
 *     assertThat(fake.requests()).extracting(RecordedRequest::method).contains("PUT");
 * }
 * }</pre>
 *
 * <p>Behaviour mirrors Data Center where it matters for the client:
 * <ul>
 *   <li>Listens on 127.0.0.1, random port, optional context path ({@code /confluence});
 *       requests outside the context path get 404.</li>
 *   <li>Every request needs {@code Authorization: Bearer <token()>} (or Basic credentials set
 *       via {@link #enableBasicAuth}); otherwise 401.</li>
 *   <li>{@code expand} is honoured for {@code body.storage}, {@code version}, {@code space} and
 *       {@code ancestors}; unexpanded fields are omitted like on the real server.</li>
 *   <li>{@code _links.base} includes the context path, {@code _links.webui} and
 *       {@code _links.download} are relative to it (see {@link #downloadLinksIncludeContext}).</li>
 *   <li>POST content: title unique per space (400), PUT: {@code version.number} must be
 *       current + 1 (409), {@code ancestors} on PUT moves the page.</li>
 *   <li>Attachments: POST needs {@code X-Atlassian-Token: no-check} (403), same file name on
 *       {@code /child/attachment} gives 400, {@code /child/attachment/{attId}/data} adds a version.</li>
 *   <li>Paging with {@code start}/{@code limit} (default 25) and {@code _links.next}.</li>
 * </ul>
 * All public methods are thread-safe. Errors use the DC JSON shape
 * {@code {"statusCode":..,"message":..,"reason":..}}.
 */
public final class FakeConfluenceServer implements AutoCloseable {

    /** Snapshot of a page as stored by the fake. {@code parentId} is null for top-level pages. */
    public record PageState(String id, String spaceKey, String title, int version, String parentId,
                            String storage, String versionMessage) {
    }

    /** Snapshot of the latest version of an attachment. */
    public record AttachmentState(String id, String pageId, String fileName, String mediaType,
                                  int version, String comment, byte[] data) {
    }

    /**
     * A request received by the fake (also unauthenticated ones).
     *
     * @param path    raw path relative to the context path (e.g. {@code /rest/api/content/1})
     * @param rawPath raw path as received, including the context path
     * @param query   raw (still encoded) query string or null
     * @param headers case-insensitive header map
     */
    public record RecordedRequest(String method, String path, String rawPath, String query,
                                  Map<String, List<String>> headers, byte[] body) {

        public String header(String name) {
            List<String> values = headers.get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        /** Decoded query parameter ({@code +} = space, as a servlet container would decode it). */
        public String param(String name) {
            return parseQuery(query).get(name);
        }

        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private static final Pattern CONTENT = Pattern.compile("/rest/api/content/([^/]+)");
    private static final Pattern CHILD_PAGE = Pattern.compile("/rest/api/content/([^/]+)/child/page");
    private static final Pattern CHILD_ATT = Pattern.compile("/rest/api/content/([^/]+)/child/attachment");
    private static final Pattern ATT_DATA = Pattern.compile("/rest/api/content/([^/]+)/child/attachment/([^/]+)/data");
    private static final Pattern DOWNLOAD = Pattern.compile("/download/attachments/([^/]+)/([^/]+)");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;
    private final String contextPath;
    private final String token = "fake-token-" + Long.toHexString(System.nanoTime());

    private final Map<String, String> spaces = new LinkedHashMap<>();
    private final Map<String, PageData> pages = new LinkedHashMap<>();
    private final Map<String, AttData> attachments = new LinkedHashMap<>();
    private UnaryOperator<String> storageNormalizer;
    private final List<RecordedRequest> requests = new ArrayList<>();
    private long nextId = 65537;
    private String basicUser;
    private String basicPassword;
    private String username = "fake.user";
    private String displayName = "Fake User";
    private boolean downloadLinksIncludeContext;
    private int failStatus;
    private String failMessage;

    private static final class PageData {
        String id;
        String spaceKey;
        String title;
        String storage;
        String parentId;
        int version;
        String message;
        Instant when;
    }

    private static final class AttData {
        String id;
        String pageId;
        String fileName;
        String mediaType;
        final List<byte[]> data = new ArrayList<>();
        final List<String> comments = new ArrayList<>();
        Instant when;
    }

    private FakeConfluenceServer(String contextPath) throws IOException {
        this.contextPath = normalizeContext(contextPath);
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    /** Starts a fake without context path. */
    public static FakeConfluenceServer start() {
        return start("");
    }

    /** Starts a fake; {@code contextPath} like {@code "/confluence"}, or empty/null for none. */
    public static FakeConfluenceServer start(String contextPath) {
        try {
            return new FakeConfluenceServer(contextPath);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String normalizeContext(String c) {
        if (c == null || c.isBlank() || c.equals("/")) {
            return "";
        }
        String s = c.startsWith("/") ? c : "/" + c;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** Base URL including the context path, without trailing slash, e.g. {@code http://127.0.0.1:4711/confluence}. */
    public URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + contextPath);
    }

    /** The only accepted Personal Access Token. */
    public String token() {
        return token;
    }

    /** Config with {@link #url()} and {@link #token()} (Bearer auth). */
    public Config config() {
        return new Config(url(), token, null, null);
    }

    public String contextPath() {
        return contextPath;
    }

    /** Additionally accept HTTP Basic auth with these credentials. */
    public synchronized FakeConfluenceServer enableBasicAuth(String user, String password) {
        this.basicUser = user;
        this.basicPassword = password;
        return this;
    }

    /** User returned by {@code /rest/api/user/current}; default {@code fake.user} / {@code Fake User}. */
    public synchronized FakeConfluenceServer setCurrentUser(String username, String displayName) {
        this.username = username;
        this.displayName = displayName;
        return this;
    }

    /** If true, {@code _links.download} is emitted with the context path prefixed (non-standard variant). */
    public synchronized FakeConfluenceServer downloadLinksIncludeContext(boolean include) {
        this.downloadLinksIncludeContext = include;
        return this;
    }

    /**
     * Rewrites storage on create/update like Confluence does (e.g. {@code <br>} becomes {@code <br />});
     * null = store verbatim (default).
     */
    public synchronized FakeConfluenceServer normalizeStorageOnSave(UnaryOperator<String> normalizer) {
        this.storageNormalizer = normalizer;
        return this;
    }

    /** The next authenticated request is answered with this error status and message (one-shot). */
    public synchronized FakeConfluenceServer failNext(int status, String message) {
        this.failStatus = status;
        this.failMessage = message;
        return this;
    }

    public synchronized void addSpace(String key) {
        spaces.putIfAbsent(key, key + " Space");
    }

    /**
     * Adds a page at version 1.
     *
     * @param parentId null for a top-level page
     * @return the new page id
     */
    public synchronized String addPage(String spaceKey, String parentId, String title, String storage) {
        if (!spaces.containsKey(spaceKey)) {
            throw new IllegalArgumentException("Unknown space " + spaceKey);
        }
        if (parentId != null && !pages.containsKey(parentId)) {
            throw new IllegalArgumentException("Unknown parent " + parentId);
        }
        if (byTitle(spaceKey, title) != null) {
            throw new IllegalArgumentException("Duplicate title " + title);
        }
        PageData p = new PageData();
        p.id = String.valueOf(nextId++);
        p.spaceKey = spaceKey;
        p.parentId = parentId;
        p.title = title;
        p.storage = storage;
        p.version = 1;
        p.when = Instant.now();
        pages.put(p.id, p);
        return p.id;
    }

    /** Simulates an edit by another user: sets title/storage (null = unchanged) and increments the version. */
    public synchronized void editPage(String id, String title, String storage) {
        PageData p = requirePage(id);
        if (title != null) {
            p.title = title;
        }
        if (storage != null) {
            p.storage = storage;
        }
        p.version++;
        p.message = null;
        p.when = Instant.now();
    }

    /** Adds an attachment (version 1) or a new version if the file name already exists; returns its id. */
    public synchronized String addAttachment(String pageId, String fileName, String mediaType, byte[] data) {
        requirePage(pageId);
        AttData a = attachmentByName(pageId, fileName);
        if (a == null) {
            a = new AttData();
            a.id = "att" + nextId++;
            a.pageId = pageId;
            a.fileName = fileName;
            attachments.put(a.id, a);
        }
        a.mediaType = mediaType;
        a.data.add(data.clone());
        a.comments.add(null);
        a.when = Instant.now();
        return a.id;
    }

    /** @throws IllegalArgumentException if the page does not exist */
    public synchronized PageState page(String id) {
        return state(requirePage(id));
    }

    public synchronized Optional<PageState> findPage(String spaceKey, String title) {
        return Optional.ofNullable(byTitle(spaceKey, title)).map(this::state);
    }

    /** All pages in insertion order. */
    public synchronized List<PageState> pages() {
        return pages.values().stream().map(this::state).toList();
    }

    public synchronized List<PageState> childrenOf(String parentId) {
        return pages.values().stream().filter(p -> parentId.equals(p.parentId)).map(this::state).toList();
    }

    public synchronized List<AttachmentState> attachments(String pageId) {
        return attachments.values().stream().filter(a -> a.pageId.equals(pageId)).map(this::state).toList();
    }

    /** Latest bytes of the attachment with this file name, or null. */
    public synchronized byte[] attachmentBytes(String pageId, String fileName) {
        AttData a = attachmentByName(pageId, fileName);
        return a == null ? null : a.data.get(a.data.size() - 1).clone();
    }

    /** Bytes of a specific attachment version (1-based), or null. */
    public synchronized byte[] attachmentBytes(String pageId, String fileName, int version) {
        AttData a = attachmentByName(pageId, fileName);
        return a == null || version < 1 || version > a.data.size() ? null : a.data.get(version - 1).clone();
    }

    /** All received requests, oldest first. */
    public synchronized List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    /** Received requests with this method, e.g. {@code "PUT"}. */
    public synchronized List<RecordedRequest> requests(String method) {
        return requests.stream().filter(r -> r.method().equals(method)).toList();
    }

    public synchronized void clearRequests() {
        requests.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---------------------------------------------------------------- state helpers

    private PageState state(PageData p) {
        return new PageState(p.id, p.spaceKey, p.title, p.version, p.parentId, p.storage, p.message);
    }

    private AttachmentState state(AttData a) {
        int v = a.data.size();
        return new AttachmentState(a.id, a.pageId, a.fileName, a.mediaType, v, a.comments.get(v - 1),
                a.data.get(v - 1).clone());
    }

    private PageData requirePage(String id) {
        PageData p = pages.get(id);
        if (p == null) {
            throw new IllegalArgumentException("Unknown page " + id);
        }
        return p;
    }

    private String normalize(String storage) {
        return storageNormalizer == null ? storage : storageNormalizer.apply(storage);
    }

    /** Confluence Server/DC compares page titles case-insensitively. */
    private PageData byTitle(String spaceKey, String title) {
        return pages.values().stream()
                .filter(p -> p.spaceKey.equals(spaceKey) && p.title.equalsIgnoreCase(title))
                .findFirst().orElse(null);
    }

    private AttData attachmentByName(String pageId, String fileName) {
        return attachments.values().stream()
                .filter(a -> a.pageId.equals(pageId) && a.fileName.equals(fileName))
                .findFirst().orElse(null);
    }

    // ---------------------------------------------------------------- HTTP handling

    private static final class HttpError extends RuntimeException {
        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            byte[] body;
            try (InputStream in = ex.getRequestBody()) {
                body = in.readAllBytes();
            }
            String rawPath = ex.getRequestURI().getRawPath();
            String query = ex.getRequestURI().getRawQuery();
            String method = ex.getRequestMethod();
            Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            ex.getRequestHeaders().forEach((k, v) -> headers.put(k, List.copyOf(v)));
            String path = rawPath;
            boolean inContext = contextPath.isEmpty() || rawPath.equals(contextPath) || rawPath.startsWith(contextPath + "/");
            if (inContext) {
                path = rawPath.substring(contextPath.length());
            }
            synchronized (this) {
                requests.add(new RecordedRequest(method, path, rawPath, query,
                        Collections.unmodifiableMap(headers), body));
                try {
                    if (!inContext) {
                        throw new HttpError(404, "Not Found");
                    }
                    if (!authorized(headers.get("Authorization"))) {
                        throw new HttpError(401, "This request requires authentication");
                    }
                    if (failStatus != 0) {
                        int s = failStatus;
                        failStatus = 0;
                        throw new HttpError(s, failMessage);
                    }
                    route(ex, method, path, parseQuery(query), headers, body);
                } catch (HttpError e) {
                    sendError(ex, e.status, e.getMessage());
                } catch (RuntimeException e) {
                    sendError(ex, 500, "Fake error: " + e);
                }
            }
        }
    }

    private boolean authorized(List<String> auth) {
        if (auth == null || auth.size() != 1) {
            return false;
        }
        String a = auth.get(0);
        if (a.equals("Bearer " + token)) {
            return true;
        }
        if (basicUser != null && a.startsWith("Basic ")) {
            String decoded = new String(Base64.getDecoder().decode(a.substring(6)), StandardCharsets.UTF_8);
            return decoded.equals(basicUser + ":" + basicPassword);
        }
        return false;
    }

    private void route(HttpExchange ex, String method, String path, Map<String, String> q,
                       Map<String, List<String>> headers, byte[] body) throws IOException {
        Set<String> expand = new HashSet<>(Arrays.asList(q.getOrDefault("expand", "").split(",")));
        Matcher m;
        if (path.equals("/rest/api/user/current")) {
            requireMethod(method, "GET");
            ObjectNode u = json.createObjectNode();
            u.put("type", "known");
            u.put("username", username);
            u.put("userKey", "ff8080817b0a0001");
            u.putObject("profilePicture").put("path", "/images/icons/profilepics/default.svg");
            u.put("displayName", displayName);
            u.set("_links", links("/rest/api/user/current"));
            sendJson(ex, 200, u);
        } else if (path.equals("/rest/api/content")) {
            if (method.equals("GET")) {
                searchContent(ex, q, expand);
            } else {
                requireMethod(method, "POST");
                createContent(ex, readJson(body), expand);
            }
        } else if ((m = ATT_DATA.matcher(path)).matches()) {
            requireMethod(method, "POST");
            uploadAttachment(ex, decode(m.group(1)), decode(m.group(2)), headers, body);
        } else if ((m = CHILD_ATT.matcher(path)).matches()) {
            if (method.equals("GET")) {
                listAttachments(ex, decode(m.group(1)), q);
            } else {
                requireMethod(method, "POST");
                uploadAttachment(ex, decode(m.group(1)), null, headers, body);
            }
        } else if ((m = CHILD_PAGE.matcher(path)).matches()) {
            requireMethod(method, "GET");
            PageData parent = pageOr404(decode(m.group(1)));
            List<PageData> kids = pages.values().stream().filter(p -> parent.id.equals(p.parentId)).toList();
            sendPaged(ex, path, q, kids.stream().map(p -> (JsonNode) content(p, expand)).toList());
        } else if ((m = CONTENT.matcher(path)).matches()) {
            String id = decode(m.group(1));
            if (method.equals("GET")) {
                sendJson(ex, 200, content(pageOr404(id), expand));
            } else {
                requireMethod(method, "PUT");
                updateContent(ex, id, readJson(body), expand);
            }
        } else if ((m = DOWNLOAD.matcher(path)).matches()) {
            requireMethod(method, "GET");
            AttData a = attachmentByName(decode(m.group(1)), decode(m.group(2)));
            if (a == null) {
                throw new HttpError(404, "Attachment not found");
            }
            int v = q.containsKey("version") ? Integer.parseInt(q.get("version")) : a.data.size();
            if (v < 1 || v > a.data.size()) {
                throw new HttpError(404, "Attachment version not found");
            }
            byte[] data = a.data.get(v - 1);
            ex.getResponseHeaders().set("Content-Type", a.mediaType);
            ex.sendResponseHeaders(200, data.length == 0 ? -1 : data.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(data);
            }
        } else {
            throw new HttpError(404, "null for uri: " + url() + path);
        }
    }

    private static void requireMethod(String actual, String expected) {
        if (!actual.equals(expected)) {
            throw new HttpError(405, "Method " + actual + " not allowed");
        }
    }

    private PageData pageOr404(String id) {
        PageData p = pages.get(id);
        if (p == null) {
            throw new HttpError(404, "No content found with id: ContentId{id=" + id + "}");
        }
        return p;
    }

    private void searchContent(HttpExchange ex, Map<String, String> q, Set<String> expand) throws IOException {
        String type = q.getOrDefault("type", "page");
        List<JsonNode> hits = new ArrayList<>();
        if (type.equals("page")) {
            for (PageData p : pages.values()) {
                if ((!q.containsKey("spaceKey") || p.spaceKey.equals(q.get("spaceKey")))
                        && (!q.containsKey("title") || p.title.equalsIgnoreCase(q.get("title")))) {
                    hits.add(content(p, expand));
                }
            }
        }
        sendPaged(ex, "/rest/api/content", q, hits);
    }

    private void createContent(HttpExchange ex, JsonNode req, Set<String> expand) throws IOException {
        if (!"page".equals(req.path("type").asText())) {
            throw new HttpError(400, "Only type page is supported by the fake");
        }
        String spaceKey = req.path("space").path("key").asText(null);
        if (spaceKey == null || !spaces.containsKey(spaceKey)) {
            throw new HttpError(404, "No space with key : " + spaceKey);
        }
        String title = req.path("title").asText("");
        if (title.isBlank()) {
            throw new HttpError(400, "Content title must not be empty");
        }
        if (byTitle(spaceKey, title) != null) {
            throw new HttpError(400, "A page with this title already exists: A page already exists with the title "
                    + title + " in the space with key " + spaceKey);
        }
        String parentId = parentFrom(req, spaceKey);
        JsonNode storage = req.path("body").path("storage");
        if (!"storage".equals(storage.path("representation").asText()) || !storage.path("value").isTextual()) {
            throw new HttpError(400, "Body representation must be storage");
        }
        PageData p = new PageData();
        p.id = String.valueOf(nextId++);
        p.spaceKey = spaceKey;
        p.title = title;
        p.parentId = parentId;
        p.storage = normalize(storage.path("value").asText());
        p.version = 1;
        p.message = req.path("version").path("message").asText(null);
        p.when = Instant.now();
        pages.put(p.id, p);
        sendJson(ex, 200, content(p, fullExpand(expand)));
    }

    private void updateContent(HttpExchange ex, String id, JsonNode req, Set<String> expand) throws IOException {
        PageData p = pageOr404(id);
        JsonNode version = req.path("version").path("number");
        if (!version.isInt()) {
            throw new HttpError(400, "Version number is required for update");
        }
        if (version.asInt() != p.version + 1) {
            throw new HttpError(409, "Version must be incremented on update. Current version is: " + p.version);
        }
        String title = req.path("title").asText("");
        if (title.isBlank()) {
            throw new HttpError(400, "Content title must not be empty");
        }
        PageData other = byTitle(p.spaceKey, title);
        if (other != null && other != p) {
            throw new HttpError(400, "A page with this title already exists: A page already exists with the title "
                    + title + " in the space with key " + p.spaceKey);
        }
        String newParent = p.parentId;
        if (req.has("ancestors")) {
            newParent = parentFrom(req, p.spaceKey);
            for (String a = newParent; a != null; a = pages.get(a).parentId) {
                if (a.equals(p.id)) {
                    throw new HttpError(400, "Cannot move a page below itself");
                }
            }
        }
        JsonNode storage = req.path("body").path("storage");
        if (storage.path("value").isTextual()) {
            if (!"storage".equals(storage.path("representation").asText())) {
                throw new HttpError(400, "Body representation must be storage");
            }
            p.storage = normalize(storage.path("value").asText());
        }
        p.title = title;
        p.parentId = newParent;
        p.version++;
        p.message = req.path("version").path("message").asText(null);
        p.when = Instant.now();
        sendJson(ex, 200, content(p, fullExpand(expand)));
    }

    private String parentFrom(JsonNode req, String spaceKey) {
        JsonNode ancestors = req.path("ancestors");
        if (!ancestors.isArray() || ancestors.isEmpty()) {
            return null;
        }
        String parentId = ancestors.get(ancestors.size() - 1).path("id").asText();
        PageData parent = pages.get(parentId);
        if (parent == null) {
            throw new HttpError(404, "No content found with id: ContentId{id=" + parentId + "}");
        }
        if (!parent.spaceKey.equals(spaceKey)) {
            throw new HttpError(400, "Parent page must be in the same space");
        }
        return parentId;
    }

    private void listAttachments(HttpExchange ex, String pageId, Map<String, String> q) throws IOException {
        PageData p = pageOr404(pageId);
        String filename = q.get("filename");
        List<JsonNode> list = attachments.values().stream()
                .filter(a -> a.pageId.equals(p.id) && (filename == null || a.fileName.equals(filename)))
                .map(a -> (JsonNode) attachment(a))
                .toList();
        sendPaged(ex, "/rest/api/content/" + pageId + "/child/attachment", q, list);
    }

    private void uploadAttachment(HttpExchange ex, String pageId, String attId,
                                  Map<String, List<String>> headers, byte[] body) throws IOException {
        List<String> xsrf = headers.get("X-Atlassian-Token");
        if (xsrf == null || !xsrf.contains("no-check")) {
            throw new HttpError(403, "XSRF check failed");
        }
        PageData p = pageOr404(pageId);
        List<String> ct = headers.get("Content-Type");
        Map<String, Part> parts = parseMultipart(ct == null ? "" : ct.get(0), body);
        Part file = parts.get("file");
        if (file == null || file.fileName == null) {
            throw new HttpError(400, "No file part named 'file' in multipart request");
        }
        Part commentPart = parts.get("comment");
        String comment = commentPart == null ? null : new String(commentPart.data, StandardCharsets.UTF_8);
        String mediaType = file.contentType == null ? "application/octet-stream" : file.contentType;

        AttData a;
        if (attId == null) {
            if (attachmentByName(p.id, file.fileName) != null) {
                throw new HttpError(400, "Cannot add a new attachment with same file name as an existing object: "
                        + file.fileName + ". Try updating instead.");
            }
            a = new AttData();
            a.id = "att" + nextId++;
            a.pageId = p.id;
            a.fileName = file.fileName;
            attachments.put(a.id, a);
        } else {
            a = attachments.get(attId);
            if (a == null || !a.pageId.equals(p.id)) {
                throw new HttpError(404, "No attachment found with id: " + attId);
            }
        }
        a.mediaType = mediaType;
        a.data.add(file.data);
        a.comments.add(comment);
        a.when = Instant.now();
        if (attId == null) {
            ObjectNode res = json.createObjectNode();
            res.putArray("results").add(attachment(a));
            res.put("size", 1);
            res.set("_links", links("/rest/api/content/" + p.id + "/child/attachment"));
            sendJson(ex, 200, res);
        } else {
            sendJson(ex, 200, attachment(a));
        }
    }

    // ---------------------------------------------------------------- JSON builders

    private static Set<String> fullExpand(Set<String> requested) {
        Set<String> s = new HashSet<>(requested);
        s.addAll(List.of("body.storage", "version", "space"));
        return s;
    }

    private ObjectNode links(String self) {
        ObjectNode l = json.createObjectNode();
        l.put("base", url().toString());
        l.put("context", contextPath);
        l.put("self", url() + self);
        return l;
    }

    private ObjectNode content(PageData p, Set<String> expand) {
        ObjectNode n = json.createObjectNode();
        n.put("id", p.id);
        n.put("type", "page");
        n.put("status", "current");
        n.put("title", p.title);
        if (expand.contains("space")) {
            ObjectNode s = n.putObject("space");
            s.put("id", Math.abs(p.spaceKey.hashCode()));
            s.put("key", p.spaceKey);
            s.put("name", spaces.get(p.spaceKey));
            s.put("type", "global");
            s.putObject("_links").put("webui", "/display/" + p.spaceKey);
        }
        if (expand.contains("version")) {
            ObjectNode v = n.putObject("version");
            ObjectNode by = v.putObject("by");
            by.put("type", "known");
            by.put("username", username);
            by.put("displayName", displayName);
            v.put("when", p.when.toString());
            v.put("message", p.message == null ? "" : p.message);
            v.put("number", p.version);
            v.put("minorEdit", false);
        }
        if (expand.contains("ancestors")) {
            ArrayNode arr = n.putArray("ancestors");
            List<PageData> chain = new ArrayList<>();
            for (String a = p.parentId; a != null; a = pages.get(a).parentId) {
                chain.add(0, pages.get(a));
            }
            for (PageData a : chain) {
                ObjectNode an = arr.addObject();
                an.put("id", a.id);
                an.put("type", "page");
                an.put("status", "current");
                an.put("title", a.title);
                an.putObject("_links").put("webui", "/pages/viewpage.action?pageId=" + a.id);
            }
        }
        if (expand.contains("body.storage")) {
            ObjectNode s = n.putObject("body").putObject("storage");
            s.put("value", p.storage);
            s.put("representation", "storage");
        }
        ObjectNode l = links("/rest/api/content/" + p.id);
        l.put("webui", "/pages/viewpage.action?pageId=" + p.id);
        l.put("edit", "/pages/resumedraft.action?draftId=" + p.id);
        l.put("tinyui", "/x/" + Base64.getUrlEncoder().withoutPadding().encodeToString(p.id.getBytes(StandardCharsets.UTF_8)));
        n.set("_links", l);
        return n;
    }

    private ObjectNode attachment(AttData a) {
        int v = a.data.size();
        ObjectNode n = json.createObjectNode();
        n.put("id", a.id);
        n.put("type", "attachment");
        n.put("status", "current");
        n.put("title", a.fileName);
        ObjectNode ver = n.putObject("version");
        ver.put("when", a.when.toString());
        ver.put("number", v);
        ver.put("minorEdit", false);
        ObjectNode meta = n.putObject("metadata");
        meta.put("mediaType", a.mediaType);
        if (a.comments.get(v - 1) != null) {
            meta.put("comment", a.comments.get(v - 1));
        }
        ObjectNode ext = n.putObject("extensions");
        ext.put("mediaType", a.mediaType);
        ext.put("fileSize", a.data.get(v - 1).length);
        if (a.comments.get(v - 1) != null) {
            ext.put("comment", a.comments.get(v - 1));
        }
        ObjectNode l = n.putObject("_links");
        l.put("webui", "/pages/viewpage.action?pageId=" + a.pageId + "&preview=/" + a.pageId + "/" + a.id
                + "/" + encode(a.fileName));
        String download = "/download/attachments/" + a.pageId + "/" + encode(a.fileName)
                + "?version=" + v + "&modificationDate=" + a.when.toEpochMilli() + "&api=v2";
        l.put("download", (downloadLinksIncludeContext ? contextPath : "") + download);
        l.put("self", url() + "/rest/api/content/" + a.id);
        return n;
    }

    private void sendPaged(HttpExchange ex, String path, Map<String, String> q, List<JsonNode> all) throws IOException {
        int start = Integer.parseInt(q.getOrDefault("start", "0"));
        int limit = Math.max(1, Integer.parseInt(q.getOrDefault("limit", "25")));
        int from = Math.min(start, all.size());
        int to = Math.min(from + limit, all.size());
        ObjectNode res = json.createObjectNode();
        ArrayNode results = res.putArray("results");
        all.subList(from, to).forEach(results::add);
        res.put("start", start);
        res.put("limit", limit);
        res.put("size", to - from);
        ObjectNode l = links(path);
        if (to < all.size()) {
            Map<String, String> next = new LinkedHashMap<>(q);
            next.put("limit", String.valueOf(limit));
            next.put("start", String.valueOf(to));
            StringBuilder sb = new StringBuilder(path).append('?');
            next.forEach((k, v) -> sb.append(encode(k)).append('=').append(encode(v)).append('&'));
            sb.setLength(sb.length() - 1);
            l.put("next", sb.toString());
        }
        res.set("_links", l);
        sendJson(ex, 200, res);
    }

    private void sendError(HttpExchange ex, int status, String message) throws IOException {
        ObjectNode n = json.createObjectNode();
        n.put("statusCode", status);
        ObjectNode data = n.putObject("data");
        data.put("authorized", status != 401 && status != 403);
        data.put("valid", true);
        data.putArray("errors");
        data.put("successful", false);
        n.put("message", message);
        n.put("reason", switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 409 -> "Conflict";
            default -> "Error";
        });
        sendJson(ex, status, n);
    }

    private void sendJson(HttpExchange ex, int status, JsonNode node) throws IOException {
        byte[] bytes = json.writeValueAsBytes(node);
        ex.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private JsonNode readJson(byte[] body) {
        try {
            return json.readTree(body);
        } catch (IOException e) {
            throw new HttpError(400, "Malformed JSON: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- encoding / multipart

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Decodes a path segment ({@code +} stays literal, as in URI paths). */
    private static String decode(String segment) {
        return URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    static Map<String, String> parseQuery(String query) {
        Map<String, String> m = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return m;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            m.putIfAbsent(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    private record Part(String fileName, String contentType, byte[] data) {
    }

    private static Map<String, Part> parseMultipart(String contentType, byte[] body) {
        Matcher bm = Pattern.compile("boundary=\"?([^\";]+)\"?").matcher(contentType);
        if (!contentType.startsWith("multipart/form-data") || !bm.find()) {
            throw new HttpError(415, "Expected multipart/form-data");
        }
        byte[] delimiter = ("--" + bm.group(1)).getBytes(StandardCharsets.ISO_8859_1);
        Map<String, Part> parts = new LinkedHashMap<>();
        int pos = indexOf(body, delimiter, 0);
        if (pos < 0) {
            throw new HttpError(400, "Malformed multipart body");
        }
        while (true) {
            int afterDelim = pos + delimiter.length;
            if (afterDelim + 1 < body.length && body[afterDelim] == '-' && body[afterDelim + 1] == '-') {
                break; // closing delimiter
            }
            int headStart = afterDelim + 2; // skip CRLF
            int headEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), headStart);
            int next = indexOf(body, ("\r\n" + new String(delimiter, StandardCharsets.ISO_8859_1))
                    .getBytes(StandardCharsets.ISO_8859_1), headEnd);
            if (headEnd < 0 || next < 0) {
                throw new HttpError(400, "Malformed multipart body");
            }
            String head = new String(body, headStart, headEnd - headStart, StandardCharsets.UTF_8);
            String name = null;
            String fileName = null;
            String partType = null;
            for (String line : head.split("\r\n")) {
                String lower = line.toLowerCase();
                if (lower.startsWith("content-disposition:")) {
                    name = headerParam(line, "name");
                    fileName = headerParam(line, "filename");
                } else if (lower.startsWith("content-type:")) {
                    partType = line.substring(line.indexOf(':') + 1).trim();
                }
            }
            byte[] data = Arrays.copyOfRange(body, headEnd + 4, next);
            if (name != null) {
                parts.put(name, new Part(fileName, partType, data));
            }
            pos = next + 2;
        }
        return parts;
    }

    private static String headerParam(String line, String param) {
        Matcher m = Pattern.compile("(?:^|;)\\s*" + param + "=\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(line);
        return m.find() ? m.group(1).replaceAll("\\\\(.)", "$1") : null;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
