package ch.confluencehelper.ui;

import ch.confluencehelper.sync.ValidationFailedException;
import ch.confluencehelper.workspace.Installation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Local web UI server (docs/UI-SPEC.md): static files from the classpath and a JSON API, bound to
 * 127.0.0.1 only, guarded by a per-start token header and a Host check.
 */
public final class UiServer {

    /** Start parameters; the defaults are those of {@code cfh ui}. */
    public static final class Options {
        int port;
        Path workspace;
        Path uiProperties = Path.of(System.getProperty("user.home"), ".config", "confluence-helper", "ui.properties");
        Map<String, String> env = System.getenv();
        String url;
        String profile;
        boolean verbose;
        Duration idleTimeout = Duration.ofMinutes(10);
        long maxUpload = 100L * 1024 * 1024;
        PrintStream log = System.err;
        Launcher launcher = Launcher.system();
        Installation installation;
        boolean autoSetup = true;

        /** 0 = random free port. */
        public Options port(int port) { this.port = port; return this; }
        /** Working folder; null = from ui.properties, else {@code ~/Confluence}. */
        public Options workspace(Path workspace) { this.workspace = workspace; return this; }
        public Options uiProperties(Path file) { this.uiProperties = file; return this; }
        /** Environment for {@code CONFLUENCE_*} and {@code CFH_CONFIG}. */
        public Options env(Map<String, String> env) { this.env = Map.copyOf(env); return this; }
        /** Like the global {@code --url}; dropped once a configuration is saved in the UI. */
        public Options url(String url) { this.url = url; return this; }
        public Options profile(String profile) { this.profile = profile; return this; }
        public Options verbose(boolean verbose) { this.verbose = verbose; return this; }
        public Options idleTimeout(Duration timeout) { this.idleTimeout = timeout; return this; }
        public Options maxUpload(long bytes) { this.maxUpload = bytes; return this; }
        /** Receives config warnings, the verbose HTTP log and internal errors. */
        public Options log(PrintStream log) { this.log = log; return this; }
        public Options launcher(Launcher launcher) { this.launcher = launcher; return this; }
        /** cfh paths written into IDE task files; null = this installation. */
        public Options installation(Installation installation) { this.installation = installation; return this; }
        /** Set up ~/Confluence (or a folder set up before) for VS Code/IntelliJ at start and on switching. */
        public Options autoSetup(boolean autoSetup) { this.autoSetup = autoSetup; return this; }
    }

    static final String TOKEN_HEADER = "X-CFH-Token";
    static final String CSP = "default-src 'self'; frame-src 'self' data: blob:; img-src 'self' data:; "
            + "style-src 'self'; script-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'";
    private static final String STATIC_ROOT = "ch/confluencehelper/ui/static/";
    private static final Pattern STATIC_PATH = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*");
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "json", "application/json; charset=utf-8",
            "txt", "text/plain; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon",
            "woff2", "font/woff2");
    static final ObjectMapper JSON = new ObjectMapper();

    private final Options options;
    private final String token;
    private final Path tempDir;
    private final HttpServer http;
    private final ExecutorService pool;
    private final ScheduledExecutorService idleCheck;
    private final UiApi api;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final Thread hook = new Thread(this::stop, "cfh-ui-shutdown");
    private volatile long lastRequest = System.nanoTime();
    private final AtomicInteger active = new AtomicInteger();

    private UiServer(Options o) throws IOException {
        this.options = o;
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        this.token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        this.tempDir = privateTempDir();
        try {
            this.api = new UiApi(o, tempDir);
            this.http = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), o.port), 0);
        } catch (IOException | RuntimeException e) {
            deleteTree(tempDir);
            throw e;
        }
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(8, r -> daemon(r, "cfh-ui-" + n.incrementAndGet()));
        this.idleCheck = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "cfh-ui-idle"));
        http.setExecutor(pool);
        http.createContext("/", this::handle);
    }

    /** Starts the server; stop it with {@link #stop()} (also happens on idle timeout, «Beenden» and JVM exit). */
    public static UiServer start(Options options) throws IOException {
        UiServer s = new UiServer(options);
        s.http.start();
        long period = Math.clamp(options.idleTimeout.toMillis() / 4, 20, 15_000);
        s.idleCheck.scheduleAtFixedRate(s::checkIdle, period, period, TimeUnit.MILLISECONDS);
        Runtime.getRuntime().addShutdownHook(s.hook);
        return s;
    }

    public int port() {
        return http.getAddress().getPort();
    }

    public String token() {
        return token;
    }

    /** Address to open, with the token in the fragment. */
    public URI url() {
        return URI.create("http://127.0.0.1:" + port() + "/#t=" + token);
    }

    public Path workspace() {
        return api.workspace();
    }

    Path tempDir() {
        return tempDir;
    }

    /** Opens {@link #url()} in the browser. @return false if no browser could be started */
    public boolean openBrowser() {
        return options.launcher.browse(url());
    }

    public boolean isStopped() {
        return stopped.getCount() == 0;
    }

    /** Blocks until the server has stopped. */
    public void awaitStop() throws InterruptedException {
        stopped.await();
    }

    public boolean awaitStop(Duration timeout) throws InterruptedException {
        return stopped.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Stops the server and deletes uploaded files; idempotent. */
    public void stop() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        try {
            http.stop(1);
            pool.shutdownNow();
            idleCheck.shutdown();
            deleteTree(tempDir);
            if (Thread.currentThread() != hook) {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException e) {
                    // JVM is shutting down already
                }
            }
        } finally {
            stopped.countDown();
        }
    }

    private void stopLater() {
        Thread t = new Thread(this::stop, "cfh-ui-stop");
        t.setDaemon(false);
        t.start();
    }

    private void checkIdle() {
        if (!stopping.get() && active.get() == 0 && System.nanoTime() - lastRequest > options.idleTimeout.toNanos()) {
            options.log.println("Keine Anfrage seit " + options.idleTimeout.toMinutes() + " Minuten – beendet.");
            stop();
        }
    }

    // ---------------------------------------------------------------- HTTP

    private void handle(HttpExchange ex) {
        active.incrementAndGet();
        try (ex) {
            lastRequest = System.nanoTime();
            Headers h = ex.getResponseHeaders();
            h.set("Content-Security-Policy", CSP);
            h.set("X-Content-Type-Options", "nosniff");
            h.set("Referrer-Policy", "no-referrer");
            h.set("Cache-Control", "no-store");
            if (!hostAllowed(ex)) {
                sendJson(ex, 403, error("session", "Zugriff verweigert (ungültiger Host oder Ursprung)."));
                return;
            }
            String path = ex.getRequestURI().getRawPath();
            if (path.equals("/api") || path.startsWith("/api/")) {
                handleApi(ex, path.substring(4));
            } else {
                serveStatic(ex, path);
            }
        } catch (IOException | RuntimeException e) {
            // client went away or response already started; nothing more to send
            if (options.verbose) {
                e.printStackTrace(options.log);
            }
        } finally {
            lastRequest = System.nanoTime();
            active.decrementAndGet();
        }
    }

    /** DNS rebinding and cross-site protection: only our own origin may talk to us. */
    private boolean hostAllowed(HttpExchange ex) {
        String host = ex.getRequestHeaders().getFirst("Host");
        int port = port();
        if (host == null || !(host.equalsIgnoreCase("127.0.0.1:" + port) || host.equalsIgnoreCase("localhost:" + port))) {
            return false;
        }
        String origin = ex.getRequestHeaders().getFirst("Origin");
        return origin == null || origin.equalsIgnoreCase("http://127.0.0.1:" + port)
                || origin.equalsIgnoreCase("http://localhost:" + port);
    }

    private void handleApi(HttpExchange ex, String path) throws IOException {
        String given = ex.getRequestHeaders().getFirst(TOKEN_HEADER);
        if (given == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                given.getBytes(StandardCharsets.UTF_8))) {
            sendJson(ex, 401, error("session", "Sitzung ungültig. Bitte die Adresse aus dem Konsolenfenster "
                    + "neu öffnen oder die Oberfläche neu starten."));
            return;
        }
        Object result;
        int status = 200;
        try {
            result = api.handle(ex.getRequestMethod(), path, ex);
        } catch (Exception | StackOverflowError | LinkageError e) {
            UiError err = UiApi.toError(e);
            if (err.status == 500) {
                options.log.println("Fehler bei " + ex.getRequestMethod() + " /api" + path + ": " + e);
                if (options.verbose) {
                    e.printStackTrace(options.log);
                }
            }
            Map<String, Object> body = error(err.kind, err.getMessage());
            err.extra.forEach(body::putIfAbsent);
            if (e instanceof ValidationFailedException v) {
                body.put("problems", UiApi.problems(v.problems()));
            }
            result = body;
            status = err.status;
        }
        sendJson(ex, status, result);
        if (status == 200 && path.equals("/shutdown")) {
            stopLater();
        }
    }

    static Map<String, Object> error(String kind, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message == null || message.isBlank() ? "Unbekannter Fehler" : message);
        m.put("kind", kind);
        return m;
    }

    private void serveStatic(HttpExchange ex, String rawPath) throws IOException {
        String method = ex.getRequestMethod();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            sendText(ex, 405, "Methode nicht erlaubt");
            return;
        }
        String rel = rawPath.equals("/") ? "index.html" : rawPath.substring(1);
        String ext = rel.lastIndexOf('.') < 0 ? "" : rel.substring(rel.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String type = TYPES.get(ext);
        byte[] body = null;
        if (type != null && STATIC_PATH.matcher(rel).matches()) {
            try (InputStream in = UiServer.class.getClassLoader().getResourceAsStream(STATIC_ROOT + rel)) {
                body = in == null ? null : in.readAllBytes();
            } catch (IOException e) {
                body = null; // a directory on the file-system classpath
            }
        }
        if (body == null) {
            sendText(ex, 404, "Nicht gefunden");
            return;
        }
        ex.getResponseHeaders().set("Content-Type", type);
        if (method.equals("HEAD")) {
            ex.sendResponseHeaders(200, -1);
            return;
        }
        ex.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static void sendText(HttpExchange ex, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    private static Path privateTempDir() throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return Files.createTempDirectory("cfh-ui-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        return Files.createTempDirectory("cfh-ui-");
    }

    static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException | UncheckedIOException e) {
            // best effort; the OS cleans its temp folder eventually
        }
    }
}
