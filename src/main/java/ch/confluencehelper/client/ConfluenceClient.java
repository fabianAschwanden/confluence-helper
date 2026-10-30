package ch.confluencehelper.client;

import ch.confluencehelper.config.Config;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Confluence Data Center REST API v1 client. Needs only normal read/write permissions.
 * No retries; credentials never appear in logs or exception messages.
 */
public class ConfluenceClient {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    /** Attachment transfers may legitimately take longer than API calls. */
    static final Duration TRANSFER_TIMEOUT = Duration.ofMinutes(10);
    private static final int PAGE_SIZE = 100;
    private static final String PAGE_EXPAND = "body.storage,version,space,ancestors";

    private final URI baseUrl;
    private final String base;
    private final String authHeader;
    private final boolean verbose;
    private final PrintStream log;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public ConfluenceClient(Config config, boolean verbose) {
        this(config, verbose, System.err);
    }

    /** @param log receives verbose request lines (method and path only) */
    public ConfluenceClient(Config config, boolean verbose, PrintStream log) {
        String b = config.baseUrl().toString();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        this.base = b;
        this.baseUrl = URI.create(b);
        this.authHeader = authHeader(config);
        this.verbose = verbose;
        this.log = log;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .proxy(ProxySelector.getDefault())
                // never follow redirects: the Authorization header must not travel to another host
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private static String authHeader(Config config) {
        if (config.token() != null) {
            return "Bearer " + config.token();
        }
        if (config.username() != null && config.password() != null) {
            String raw = config.username() + ":" + config.password();
            return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }
        return null;
    }

    public URI baseUrl() {
        return baseUrl;
    }

    /** Display name and username of the authenticated user, e.g. "Max Muster (mmuster)". */
    public String currentUser() {
        JsonNode user = getJson("/rest/api/user/current");
        if ("anonymous".equals(user.path("type").asText())) {
            throw new ConfluenceException(401, "Nicht angemeldet (anonymer Zugriff): Token bzw. Benutzername/Passwort prüfen.");
        }
        String name = user.path("displayName").asText("");
        String username = user.path("username").asText("");
        if (name.isEmpty()) {
            return username;
        }
        return username.isEmpty() ? name : name + " (" + username + ")";
    }

    /** Page including storage body, version, space and parent. */
    public Page getPage(String id) {
        JsonNode node = getJson("/rest/api/content/" + pathSegment(id) + "?expand=" + PAGE_EXPAND);
        return toPage(node, null, null);
    }

    /**
     * Page by space and title, including storage body. Like Confluence itself, titles are compared
     * case-insensitively (and Unicode-normalised); an exact match wins over a case-insensitive one.
     */
    public Optional<Page> findPage(String spaceKey, String title) {
        JsonNode res = getJson("/rest/api/content?type=page&spaceKey=" + param(spaceKey)
                + "&title=" + param(title) + "&expand=" + PAGE_EXPAND);
        String wanted = nfc(title);
        JsonNode exact = null;
        List<JsonNode> similar = new ArrayList<>();
        for (JsonNode n : res.path("results")) {
            String t = nfc(n.path("title").asText());
            if (t.equals(wanted)) {
                exact = n;
                break;
            }
            if (t.equalsIgnoreCase(wanted) || t.toLowerCase(Locale.ROOT).equals(wanted.toLowerCase(Locale.ROOT))) {
                similar.add(n);
            }
        }
        JsonNode hit = exact != null ? exact : similar.size() == 1 ? similar.getFirst() : null;
        return hit == null ? Optional.empty() : Optional.of(toPage(hit, linkBase(res), null));
    }

    static String nfc(String s) {
        return s == null ? "" : Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    /** getPage or findPage; throws ConfluenceException(404) if not found. */
    public Page resolve(PageRef ref) {
        if (ref.hasId()) {
            return getPage(ref.id());
        }
        return findPage(ref.spaceKey(), ref.title()).orElseThrow(() -> new ConfluenceException(404,
                "Seite nicht gefunden oder keine Leserechte: " + ref.spaceKey() + " / " + ref.title()));
    }

    /** Direct child pages (all, paged internally), without storage body. */
    public List<Page> children(String pageId) {
        List<Page> pages = new ArrayList<>();
        for (Paged p : paged("/rest/api/content/" + pathSegment(pageId) + "/child/page?expand=version,space")) {
            pages.add(toPage(p.node(), p.linkBase(), pageId));
        }
        return pages;
    }

    public Page createPage(String spaceKey, String parentId, String title, String storage) {
        ObjectNode body = json.createObjectNode();
        body.put("type", "page");
        body.put("title", title);
        body.putObject("space").put("key", spaceKey);
        if (parentId != null) {
            body.putArray("ancestors").addObject().put("id", parentId);
        }
        putStorage(body, storage);
        JsonNode res = send("POST", "/rest/api/content?expand=" + PAGE_EXPAND, jsonBody(body),
                "application/json", REQUEST_TIMEOUT, false);
        return toPage(res, null, null);
    }

    /** @param newVersion current version + 1 */
    public Page updatePage(String id, String title, int newVersion, String storage, String message) {
        ObjectNode body = json.createObjectNode();
        body.put("id", id);
        body.put("type", "page");
        body.put("title", title);
        ObjectNode version = body.putObject("version");
        version.put("number", newVersion);
        if (message != null && !message.isBlank()) {
            version.put("message", message);
        }
        putStorage(body, storage);
        JsonNode res = send("PUT", "/rest/api/content/" + pathSegment(id) + "?expand=" + PAGE_EXPAND,
                jsonBody(body), "application/json", REQUEST_TIMEOUT, true);
        return toPage(res, null, null);
    }

    public List<Attachment> attachments(String pageId) {
        List<Attachment> result = new ArrayList<>();
        for (Paged p : paged("/rest/api/content/" + pathSegment(pageId) + "/child/attachment?expand=version")) {
            result.add(toAttachment(p.node()));
        }
        return result;
    }

    public void download(Attachment attachment, Path target) {
        URI uri = downloadUri(attachment.downloadPath());
        HttpRequest request = request(uri, TRANSFER_TIMEOUT).GET().build();
        logRequest("GET", uri);
        try {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = Files.createTempFile(parent, ".download-", ".part");
            try {
                HttpResponse<Path> res = http.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
                logResponse(res.statusCode());
                if (res.statusCode() >= 300) {
                    throw error(res.statusCode(), Files.readString(tmp, StandardCharsets.UTF_8), false);
                }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw transportError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConfluenceException("Abgebrochen", e);
        }
    }

    /** Uploads a new attachment, or a new version if one with the same file name exists. */
    public Attachment upload(String pageId, Path file, String comment) {
        return upload(pageId, file, file.getFileName().toString(), null, comment);
    }

    /**
     * Uploads {@code file} as attachment named {@code title}: as a new version of {@code attachmentId}
     * if given, else of the attachment with that title (compared Unicode-normalised), else as a new one.
     */
    public Attachment upload(String pageId, Path file, String title, String attachmentId, String comment) {
        String fileName = title == null || title.isBlank() ? file.getFileName().toString() : title;
        String attId = attachmentId;
        if (attId == null) {
            JsonNode existing = getJson("/rest/api/content/" + pathSegment(pageId)
                    + "/child/attachment?filename=" + param(fileName) + "&expand=version");
            for (JsonNode n : existing.path("results")) {
                if (nfc(fileName).equals(nfc(n.path("title").asText()))) {
                    attId = n.path("id").asText();
                    break;
                }
            }
        }
        String path = "/rest/api/content/" + pathSegment(pageId) + "/child/attachment"
                + (attId == null ? "" : "/" + pathSegment(attId) + "/data") + "?expand=version";

        String boundary = "cfh-" + UUID.randomUUID();
        String mediaType;
        try {
            mediaType = Optional.ofNullable(Files.probeContentType(file)).orElse("application/octet-stream");
        } catch (IOException e) {
            mediaType = "application/octet-stream";
        }
        StringBuilder head = new StringBuilder();
        if (comment != null && !comment.isBlank()) {
            head.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"comment\"\r\n")
                    .append("Content-Type: text/plain; charset=UTF-8\r\n\r\n")
                    .append(comment).append("\r\n");
        }
        head.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                .append(fileName.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\r\n")
                .append("Content-Type: ").append(mediaType).append("\r\n\r\n");
        String tail = "\r\n--" + boundary + "--\r\n";
        HttpRequest.BodyPublisher body;
        try {
            body = HttpRequest.BodyPublishers.concat(
                    HttpRequest.BodyPublishers.ofByteArray(head.toString().getBytes(StandardCharsets.UTF_8)),
                    HttpRequest.BodyPublishers.ofFile(file),
                    HttpRequest.BodyPublishers.ofByteArray(tail.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new ConfluenceException("Datei kann nicht gelesen werden: " + file, e);
        }
        JsonNode res = send("POST", path, body, "multipart/form-data; boundary=" + boundary,
                TRANSFER_TIMEOUT, false);
        JsonNode att = res.has("results") ? res.path("results").path(0) : res;
        if (att.isMissingNode()) {
            throw new ConfluenceException(0, "Unerwartete Antwort beim Hochladen von " + fileName);
        }
        return toAttachment(att);
    }

    // ---------------------------------------------------------------- mapping

    private Page toPage(JsonNode n, String linkBase, String knownParentId) {
        String parentId = knownParentId;
        JsonNode ancestors = n.path("ancestors");
        if (ancestors.isArray() && !ancestors.isEmpty()) {
            parentId = ancestors.get(ancestors.size() - 1).path("id").asText();
        }
        JsonNode storage = n.path("body").path("storage").path("value");
        String b = textOrNull(n.path("_links").path("base"));
        if (b == null) {
            b = linkBase != null ? linkBase : base;
        }
        String webui = textOrNull(n.path("_links").path("webui"));
        return new Page(
                n.path("id").asText(),
                n.path("title").asText(),
                textOrNull(n.path("space").path("key")),
                n.path("version").path("number").asInt(0),
                parentId,
                storage.isTextual() ? storage.asText() : null,
                webui == null ? null : b + webui);
    }

    private static Attachment toAttachment(JsonNode n) {
        String mediaType = textOrNull(n.path("extensions").path("mediaType"));
        if (mediaType == null) {
            mediaType = textOrNull(n.path("metadata").path("mediaType"));
        }
        return new Attachment(
                n.path("id").asText(),
                n.path("title").asText(),
                mediaType,
                n.path("extensions").path("fileSize").asLong(0),
                n.path("version").path("number").asInt(0),
                textOrNull(n.path("_links").path("download")));
    }

    private static String textOrNull(JsonNode n) {
        return n.isValueNode() && !n.isNull() ? n.asText() : null;
    }

    private static String linkBase(JsonNode response) {
        return textOrNull(response.path("_links").path("base"));
    }

    /**
     * Resolves {@code _links.download}. In Data Center it is relative to {@code _links.base}
     * (which includes the context path); a path that already starts with the context path is
     * accepted too. Absolute URLs are only allowed on the configured host.
     */
    URI downloadUri(String downloadPath) {
        if (downloadPath == null || downloadPath.isBlank()) {
            throw new ConfluenceException(0, "Anhang ohne Download-Link");
        }
        String p = downloadPath.replace(" ", "%20");
        if (p.startsWith("http://") || p.startsWith("https://")) {
            URI abs = URI.create(p);
            if (!abs.getHost().equalsIgnoreCase(baseUrl.getHost()) || abs.getPort() != baseUrl.getPort()) {
                throw new ConfluenceException(0, "Download-Link zeigt auf fremden Server: " + abs.getHost());
            }
            return abs;
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        String context = baseUrl.getRawPath() == null ? "" : baseUrl.getRawPath();
        if (!context.isEmpty() && (p.startsWith(context + "/"))) {
            String origin = base.substring(0, base.length() - context.length());
            return URI.create(origin + p);
        }
        return URI.create(base + p);
    }

    // ---------------------------------------------------------------- HTTP

    private record Paged(JsonNode node, String linkBase) {
    }

    /** Follows start/limit paging until a short or empty page without {@code _links.next}. */
    private List<Paged> paged(String pathWithQuery) {
        List<Paged> all = new ArrayList<>();
        int start = 0;
        while (true) {
            JsonNode res = getJson(pathWithQuery + "&limit=" + PAGE_SIZE + "&start=" + start);
            JsonNode results = res.path("results");
            String lb = linkBase(res);
            for (JsonNode n : results) {
                all.add(new Paged(n, lb));
            }
            int size = res.path("size").asInt(results.size());
            boolean hasNext = res.path("_links").hasNonNull("next");
            if (size == 0 || results.isEmpty() || (!hasNext && size < res.path("limit").asInt(PAGE_SIZE))) {
                return all;
            }
            start += size;
        }
    }

    private JsonNode getJson(String pathWithQuery) {
        return send("GET", pathWithQuery, HttpRequest.BodyPublishers.noBody(), null, REQUEST_TIMEOUT, false);
    }

    private HttpRequest.BodyPublisher jsonBody(JsonNode body) {
        try {
            return HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void putStorage(ObjectNode body, String storage) {
        ObjectNode s = body.putObject("body").putObject("storage");
        s.put("value", storage);
        s.put("representation", "storage");
    }

    private HttpRequest.Builder request(URI uri, Duration timeout) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", "confluence-helper");
        if (authHeader != null) {
            try {
                b.header("Authorization", authHeader);
            } catch (IllegalArgumentException e) {
                // the JDK's message would contain the full header value, i.e. the secret
                throw new ConfluenceException(0, "Ungültige Zeichen im Token bzw. Benutzernamen/Passwort "
                        + "(z.B. unsichtbare Leerzeichen aus Copy & Paste). Zugangsdaten neu eintragen.");
            }
        }
        return b;
    }

    private JsonNode send(String method, String pathWithQuery, HttpRequest.BodyPublisher body,
                          String contentType, Duration timeout, boolean isUpdate) {
        URI uri = URI.create(base + pathWithQuery);
        HttpRequest.Builder b = request(uri, timeout).method(method, body);
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        if (contentType != null && contentType.startsWith("multipart/")) {
            b.header("X-Atlassian-Token", "no-check");
        }
        logRequest(method, uri);
        HttpResponse<String> res;
        try {
            res = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw transportError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConfluenceException("Abgebrochen", e);
        }
        logResponse(res.statusCode());
        if (res.statusCode() >= 300) {
            throw error(res.statusCode(), res.body(), isUpdate);
        }
        try {
            String text = res.body();
            return text == null || text.isBlank() ? json.createObjectNode() : json.readTree(text);
        } catch (IOException e) {
            throw new ConfluenceException(res.statusCode(),
                    "Unerwartete Antwort (kein JSON) von " + uri.getRawPath() + ": Basis-URL prüfen.");
        }
    }

    private ConfluenceException error(int status, String body, boolean isUpdate) {
        String serverMessage = serverMessage(body);
        String suffix = serverMessage == null ? "" : " Server: " + serverMessage;
        String msg = switch (status) {
            case 401 -> "Anmeldung fehlgeschlagen (HTTP 401): Token bzw. Benutzername/Passwort prüfen.";
            case 403 -> "Keine Berechtigung (HTTP 403): Lese-/Schreibrechte auf Seite bzw. Space prüfen.";
            case 404 -> "Seite nicht gefunden oder keine Leserechte (HTTP 404).";
            case 409 -> isUpdate
                    ? "Versionskonflikt (HTTP 409): Die Seite wurde inzwischen auf dem Server geändert."
                    : "Konflikt (HTTP 409).";
            default -> status >= 300 && status < 400
                    ? "Unerwartete Weiterleitung (HTTP " + status + "): Basis-URL prüfen (https, Kontextpfad)."
                    : "Confluence-Fehler (HTTP " + status + ").";
        };
        return new ConfluenceException(status, msg + suffix);
    }

    private String serverMessage(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode n = json.readTree(body);
            String m = textOrNull(n.path("message"));
            return m == null || m.isBlank() ? null : m;
        } catch (IOException e) {
            return null; // HTML error page etc.
        }
    }

    private ConfluenceException transportError(IOException e) {
        String host = baseUrl.getHost();
        if (e instanceof HttpTimeoutException) {
            return new ConfluenceException("Zeitüberschreitung bei der Verbindung zu " + host, e);
        }
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return new ConfluenceException("Verbindung zu " + host + " fehlgeschlagen: " + detail, e);
    }

    private void logRequest(String method, URI uri) {
        if (verbose) {
            String q = uri.getRawQuery();
            log.println("> " + method + " " + uri.getRawPath() + (q == null ? "" : "?" + q));
        }
    }

    private void logResponse(int status) {
        if (verbose) {
            log.println("< " + status);
        }
    }

    /** Encodes a query parameter value (space as %20, '+' and '&' escaped). */
    static String param(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Encodes an id for use as a path segment. */
    private static String pathSegment(String value) {
        return param(value);
    }
}
