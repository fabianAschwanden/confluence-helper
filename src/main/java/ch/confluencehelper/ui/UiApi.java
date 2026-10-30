package ch.confluencehelper.ui;

import ch.confluencehelper.cli.CfhCommand;
import ch.confluencehelper.client.ConfluenceClient;
import ch.confluencehelper.client.ConfluenceException;
import ch.confluencehelper.client.Page;
import ch.confluencehelper.client.PageRefs;
import ch.confluencehelper.config.Config;
import ch.confluencehelper.config.ConfigException;
import ch.confluencehelper.config.ConfigFile;
import ch.confluencehelper.convert.ConvertOptions;
import ch.confluencehelper.convert.Converters;
import ch.confluencehelper.convert.ExcelConverter;
import ch.confluencehelper.convert.TableTooLargeException;
import ch.confluencehelper.storage.Problem;
import ch.confluencehelper.storage.StorageXml;
import ch.confluencehelper.sync.Creator;
import ch.confluencehelper.sync.Differ;
import ch.confluencehelper.sync.Exporter;
import ch.confluencehelper.sync.PageApi;
import ch.confluencehelper.sync.PageMeta;
import ch.confluencehelper.sync.PageStore;
import ch.confluencehelper.sync.Pusher;
import ch.confluencehelper.sync.Slugs;
import ch.confluencehelper.sync.SyncException;
import ch.confluencehelper.sync.ValidationFailedException;
import ch.confluencehelper.sync.VersionConflictException;
import ch.confluencehelper.workspace.Installation;
import ch.confluencehelper.workspace.WorkspaceSetup;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** The JSON endpoints of docs/UI-SPEC.md, built on the same services as the CLI. */
final class UiApi {

    private static final long MAX_JSON = 64L * 1024 * 1024;
    private static final Set<String> PATHS = Set.of("/ping", "/status", "/config", "/whoami", "/workspace", "/upload",
            "/convert", "/create", "/export", "/local", "/remote", "/diff", "/page", "/validate", "/push", "/open",
            "/shutdown", "/profile", "/refresh", "/workspace/init", "/open-ide");
    /** Folder extensions macOS treats as packages (apps, installers, plug-ins): never handed to Finder. */
    private static final Set<String> PACKAGE_EXTENSIONS = Set.of("app", "appex", "bundle", "framework", "plugin",
            "kext", "xpc", "pkg", "mpkg", "workflow", "action", "prefpane", "saver", "scptd", "wdgt",
            "qlgenerator", "mdimporter", "systemextension", "component", "osax");
    private static final PrintStream NO_OUTPUT = new PrintStream(OutputStream.nullOutputStream());

    private record Upload(Path file, String name, String kind, long size) {
    }

    @FunctionalInterface
    private interface Action {
        Object run() throws IOException;
    }

    private final UiServer.Options o;
    private final Path tempDir;
    private final PrintStream log;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final Map<String, Upload> uploads = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private volatile String profile;
    private volatile String urlOverride;
    private volatile Path workspace;
    private volatile String workspaceWarning;

    UiApi(UiServer.Options o, Path tempDir) {
        this.o = o;
        this.tempDir = tempDir;
        this.log = new PrintStream(new OnceLog(o.log), true, StandardCharsets.UTF_8);
        this.profile = blankToNull(o.profile);
        this.urlOverride = blankToNull(o.url);
        this.workspace = chooseWorkspace();
        autoSetup();
    }

    private Path chooseWorkspace() {
        if (o.workspace != null) {
            return usableWorkspace(o.workspace, " Mit --workspace einen anderen Ordner angeben.");
        }
        Path home = Path.of(System.getProperty("user.home"), "Confluence");
        String stored = o.uiProperties == null ? null : blankToNull(ConfigFile.read(o.uiProperties).getProperty("workspace"));
        if (stored != null) {
            // e.g. on a share that is not mounted: start anyway, so a new folder can be chosen in the UI
            try {
                return realDir(Path.of(stored));
            } catch (IOException | InvalidPathException e) {
                this.workspaceWarning = "Der zuletzt gewählte Arbeitsordner " + stored + " ist nicht verfügbar ("
                        + reason(e) + "). Vorübergehend wird " + home + " verwendet; über «Arbeitsordner» oben "
                        + "lässt sich ein anderer wählen.";
                o.log.println("Warnung: " + workspaceWarning + " (gespeichert in " + o.uiProperties + ")");
            }
        }
        return usableWorkspace(home, " Mit --workspace einen anderen Ordner angeben.");
    }

    /**
     * The UI owns ~/Confluence, so it sets that folder up for VS Code/IntelliJ by itself and keeps a folder
     * set up before current (e.g. after moving the cfh installation). Other folders only on request.
     * Files changed or deleted by the user stay as they are.
     */
    private void autoSetup() {
        if (!o.autoSetup) {
            return;
        }
        Path ws = workspace;
        boolean own;
        try {
            own = ws.equals(Path.of(System.getProperty("user.home"), "Confluence").toRealPath());
        } catch (IOException | InvalidPathException e) {
            own = false;
        }
        if (!own && !WorkspaceSetup.isSetUp(ws)) {
            return;
        }
        try {
            WorkspaceSetup.Report r = setup().init(ws, new WorkspaceSetup.Options(false, false));
            long changed = r.files().stream().filter(f -> f.status() == WorkspaceSetup.Status.CREATED
                    || f.status() == WorkspaceSetup.Status.UPDATED).count();
            if (changed > 0) {
                o.log.println("Arbeitsordner für VS Code/IntelliJ mit Copilot eingerichtet (" + changed + " Dateien).");
            }
        } catch (RuntimeException e) {
            o.log.println("Warnung: Einrichtung für Copilot im Arbeitsordner fehlgeschlagen: " + e.getMessage());
        }
    }

    private WorkspaceSetup setup() {
        return new WorkspaceSetup(o.installation != null ? o.installation : Installation.detect());
    }

    private static Path usableWorkspace(Path dir, String hint) {
        try {
            return realDir(dir);
        } catch (IOException | InvalidPathException e) {
            throw new ConfigException("Arbeitsordner " + dir + " ist nicht verfügbar (" + reason(e) + ")." + hint);
        }
    }

    private static Path realDir(Path dir) throws IOException {
        Files.createDirectories(dir);
        return dir.toRealPath();
    }

    private static String reason(Exception e) {
        return switch (e) {
            case AccessDeniedException a -> "kein Zugriff – Laufwerk nicht verbunden oder keine Berechtigung";
            case NoSuchFileException n -> "nicht vorhanden";
            case FileSystemException f when f.getReason() != null -> f.getReason();
            case InvalidPathException i -> "ungültiger Pfad";
            default -> String.valueOf(e.getMessage());
        };
    }

    Path workspace() {
        return workspace;
    }

    Object handle(String method, String path, HttpExchange ex) throws IOException {
        try {
            return route(method, path, ex);
        } catch (SyncException e) {
            throw reword(e);
        } catch (TableTooLargeException e) {
            throw UiError.input(e.sheet() ? e.headline() + "Belegter Bereich reicht vermutlich bis zu einer weit "
                    + "entfernten Zelle; Blatt bereinigen oder nur die nötigen Blätter auswählen." : e.getMessage());
        }
    }

    private Object route(String method, String path, HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        switch (method + " " + path) {
            case "GET /ping": return Map.of("ok", true);
            case "GET /status": return status();
            case "POST /config": { JsonNode b = body(ex); return locked(() -> config(b)); }
            case "POST /profile": { JsonNode b = body(ex); return locked(() -> switchProfile(b)); }
            case "POST /whoami": return whoami(body(ex));
            case "POST /workspace": { JsonNode b = body(ex); return locked(() -> setWorkspace(b)); }
            case "POST /upload": return upload(ex, q.get("name"));
            case "POST /convert": return convert(body(ex));
            case "POST /create": { JsonNode b = body(ex); return locked(() -> create(b)); }
            case "POST /export": { JsonNode b = body(ex); return locked(() -> export(b)); }
            case "GET /local": return local();
            case "POST /remote": return remote(body(ex));
            case "POST /diff": return diff(body(ex));
            case "GET /page": return page(q.get("dir"));
            case "PUT /page": { JsonNode b = body(ex); return locked(() -> savePage(b)); }
            case "POST /validate": return Map.of("problems", problems(StorageXml.validate(required(body(ex), "storage"))));
            case "POST /push": { JsonNode b = body(ex); return locked(() -> push(b)); }
            case "POST /refresh": { JsonNode b = body(ex); return locked(() -> refresh(b)); }
            case "POST /open": return open(body(ex));
            case "POST /workspace/init": { JsonNode b = body(ex); return locked(() -> workspaceInit(b)); }
            case "POST /open-ide": { JsonNode b = body(ex); return locked(() -> openIde(b)); }
            case "POST /shutdown": return Map.of();
            default:
                throw PATHS.contains(path) ? new UiError(405, "input", "Methode " + method + " nicht erlaubt")
                        : new UiError(404, "notfound", "Unbekannte Anfrage: /api" + path);
        }
    }

    private Object locked(Action action) throws IOException {
        writeLock.lock();
        try {
            return action.run();
        } finally {
            writeLock.unlock();
        }
    }

    /** A fresh client from the current configuration, so a configuration saved in the UI applies at once. */
    private PageApi api() {
        return CfhCommand.clientFactory(o.env, log).create(urlOverride, profile, o.verbose);
    }

    // ---------------------------------------------------------------- connection

    private Map<String, Object> status() {
        Path file = ConfigFile.defaultPath(o.env);
        Properties props;
        try {
            props = ConfigFile.read(file);
        } catch (ConfigException e) {
            props = new Properties();
        }
        String p = profile;
        String prefix = p == null ? "" : p + ".";
        Config cfg = null;
        String configError = null;
        try {
            cfg = Config.load(urlOverride, p, o.env, NO_OUTPUT);
        } catch (ConfigException e) {
            configError = e.getMessage();
        }
        boolean envOverride = p == null && Set.of(Config.ENV_URL, Config.ENV_TOKEN, Config.ENV_USER, Config.ENV_PASSWORD)
                .stream().anyMatch(k -> blankToNull(o.env.get(k)) != null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", version());
        m.put("configured", cfg != null);
        m.put("baseUrl", cfg != null ? cfg.baseUrl().toString()
                : firstNonBlank(urlOverride, p == null ? o.env.get(Config.ENV_URL) : null, props.getProperty(prefix + "url")));
        m.put("profile", p);
        m.put("profiles", ConfigFile.profiles(props));
        m.put("auth", cfg == null ? null : cfg.usesToken() ? "token" : "basic");
        m.put("username", cfg != null ? cfg.username() : blankToNull(props.getProperty(prefix + "username")));
        m.put("configFile", file.toString());
        m.put("workspace", workspace.toString());
        m.put("workspaceWarning", workspaceWarning);
        m.put("copilotSetup", WorkspaceSetup.isSetUp(workspace));
        m.put("envOverride", envOverride);
        m.put("configError", configError);
        m.put("connections", connections(props));
        return m;
    }

    /** URL, auth type and user of every profile in the file (null = top level); never a secret. */
    private static List<Map<String, Object>> connections(Properties props) {
        List<Map<String, Object>> list = new ArrayList<>();
        List<String> names = new ArrayList<>();
        names.add(null);
        names.addAll(ConfigFile.profiles(props));
        for (String name : names) {
            String prefix = name == null ? "" : name + ".";
            String user = blankToNull(props.getProperty(prefix + "username"));
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("profile", name);
            c.put("url", blankToNull(props.getProperty(prefix + "url")));
            c.put("auth", blankToNull(props.getProperty(prefix + "token")) != null ? "token" : user != null ? "basic" : null);
            c.put("username", user);
            list.add(c);
        }
        return list;
    }

    private Object switchProfile(JsonNode b) {
        String p = blankToNull(text(b, "profile"));
        if (p != null && !ConfigFile.profiles(ConfigFile.read(ConfigFile.defaultPath(o.env))).contains(p)) {
            throw UiError.input("Profil «" + p + "» gibt es nicht.");
        }
        profile = p;
        urlOverride = null;
        return status();
    }

    /** Without {@code url}: the active connection. With it: the given values, without saving them. */
    private Object whoami(JsonNode b) {
        if (blankToNull(text(b, "url")) == null) {
            return Map.of("user", api().currentUser());
        }
        Config cfg = ConfigFile.resolveConnection(ConfigFile.defaultPath(o.env), text(b, "profile"), text(b, "url"),
                text(b, "token"), text(b, "username"), text(b, "password"));
        return Map.of("user", PageApi.of(new ConfluenceClient(cfg, o.verbose, log)).currentUser());
    }

    private Object config(JsonNode b) {
        String p = blankToNull(text(b, "profile"));
        ConfigFile.saveConnection(ConfigFile.defaultPath(o.env), p, text(b, "url"), text(b, "token"),
                text(b, "username"), text(b, "password"));
        profile = p;
        urlOverride = null;
        return status();
    }

    private Object setWorkspace(JsonNode b) throws IOException {
        String raw = required(b, "path").strip();
        Path p;
        try {
            p = raw.equals("~") || raw.startsWith("~/") || raw.startsWith("~\\")
                    ? Path.of(System.getProperty("user.home")).resolve(raw.length() > 1 ? raw.substring(2) : "")
                    : Path.of(raw);
        } catch (InvalidPathException e) {
            throw UiError.input("Ungültiger Pfad: " + raw);
        }
        if (!p.isAbsolute()) {
            throw UiError.input("Bitte einen vollständigen Pfad angeben (z.B. " + Path.of(System.getProperty("user.home"), "Confluence") + ").");
        }
        Path real;
        try {
            real = realDir(p);
        } catch (IOException e) {
            throw UiError.input("Ordner " + p + " ist nicht verfügbar (" + reason(e) + ").");
        }
        if (o.uiProperties != null) {
            ConfigFile.update(o.uiProperties, Map.of("workspace", real.toString()), Set.of());
        }
        workspace = real;
        workspaceWarning = null;
        autoSetup();
        return status();
    }

    // ---------------------------------------------------------------- new page

    private Object upload(HttpExchange ex, String rawName) throws IOException {
        String name = Slugs.safeFileName(rawName, "");
        String ext = name.lastIndexOf('.') < 0 ? "" : name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String kind = switch (ext) {
            case "pdf" -> "pdf";
            case "xlsx", "xls", "xlsm" -> "excel";
            case "csv", "tsv" -> "csv";
            default -> throw UiError.input("Nicht unterstütztes Dateiformat" + (name.isEmpty() ? "" : ": " + name)
                    + " (unterstützt: PDF, Excel .xlsx/.xls/.xlsm, CSV/TSV)");
        };
        String length = ex.getRequestHeaders().getFirst("Content-Length");
        if (length != null && parseLong(length) > o.maxUpload) {
            throw tooLarge();
        }
        String id = HexFormat.of().formatHex(randomBytes(16));
        Path dir = Files.createDirectory(tempDir.resolve(id));
        Path file = dir.resolve(name);
        long size = 0;
        try (InputStream in = ex.getRequestBody(); OutputStream out = Files.newOutputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            for (int r; (r = in.read(buf)) > 0; ) {
                size += r;
                if (size > o.maxUpload) {
                    throw tooLarge();
                }
                out.write(buf, 0, r);
            }
        } catch (IOException | RuntimeException e) {
            UiServer.deleteTree(dir);
            throw e;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uploadId", id);
        m.put("name", name);
        m.put("kind", kind);
        m.put("size", size);
        if (kind.equals("excel")) {
            try {
                m.put("sheets", ExcelConverter.sheets(file));
            } catch (IOException e) {
                UiServer.deleteTree(dir);
                throw UiError.input(hideTemp(e.getMessage(), file, name));
            }
        }
        uploads.put(id, new Upload(file, name, kind, size));
        return m;
    }

    private UiError tooLarge() {
        return new UiError(413, "input", "Datei ist zu gross (höchstens " + o.maxUpload / (1024 * 1024) + " MB).");
    }

    private Object convert(JsonNode b) {
        Upload u = upload(required(b, "uploadId"));
        List<String> sheets = new ArrayList<>();
        b.path("sheets").forEach(s -> sheets.add(s.asText()));
        ConvertOptions options = new ConvertOptions(sheets, bool(b, "includeHidden", false), bool(b, "headerRow", true),
                text(b, "title"));
        String storage;
        try {
            storage = Converters.forFile(u.file()).toStorage(u.file(), options);
        } catch (IOException e) {
            throw UiError.input("Umwandlung fehlgeschlagen: " + hideTemp(e.getMessage(), u.file(), u.name()));
        }
        return Map.of("storage", storage, "problems", problems(StorageXml.validate(storage)));
    }

    private Object create(JsonNode b) {
        String storage = required(b, "storage");
        if (blankToNull(text(b, "spaceKey")) == null || blankToNull(text(b, "title")) == null) {
            throw UiError.input("Bitte Space-Key und Titel angeben.");
        }
        String attachId = blankToNull(text(b, "attachUploadId"));
        Upload attach = attachId == null ? null : upload(attachId);
        PageApi api = api();
        Creator.Result r = new Creator(api).createInto(storage, workspace, text(b, "spaceKey"), text(b, "title"),
                blankToNull(text(b, "parent")));
        Page page = r.page();
        List<String> lines = new ArrayList<>();
        lines.add("Seite «" + page.title() + "» angelegt (ID " + page.id() + ", Version " + page.version() + ").");
        lines.add("Lokale Kopie: " + rel(r.location().dir()));
        Map<String, Object> m = new LinkedHashMap<>();
        if (attach != null) {
            String message = blankToNull(text(b, "message"));
            try {
                api.upload(page.id(), attach.file(), message != null ? message : "Originaldatei des Imports");
                lines.add("Anhang hochgeladen: " + attach.name());
            } catch (RuntimeException e) {
                String error = "Die Seite wurde angelegt, aber das Hochladen von «" + attach.name()
                        + "» ist fehlgeschlagen: " + toError(e).getMessage();
                lines.add(error);
                m.put("attachmentError", error);
            }
        }
        m.put("id", page.id());
        m.put("title", page.title());
        m.put("version", page.version());
        m.put("webUrl", page.webUrl());
        m.put("dir", rel(r.location().dir()));
        m.put("problems", problems(r.warnings()));
        m.put("log", lines);
        return m;
    }

    // ---------------------------------------------------------------- export and local pages

    private Object export(JsonNode b) {
        String page = required(b, "page");
        Integer depth = b.hasNonNull("depth") ? b.get("depth").asInt() : null;
        if (depth != null && depth < 0) {
            throw UiError.input("Die Tiefe muss 0 oder grösser sein.");
        }
        boolean children = bool(b, "children", depth != null);
        int maxDepth = children ? (depth == null ? Integer.MAX_VALUE : depth) : 0;
        boolean attachments = bool(b, "attachments", false);
        PageApi api = api();
        List<Exporter.Result> results = new Exporter(api, Clock.systemUTC(), s -> { })
                .export(PageRefs.parse(page, api.baseUrl()), workspace, new Exporter.Options(attachments, children, maxDepth,
                        bool(b, "force", false)));
        List<Map<String, Object>> list = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (Exporter.Result r : results) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.page().id());
            m.put("title", r.page().title());
            m.put("version", r.page().version());
            m.put("dir", rel(r.dir()));
            m.put("attachments", r.attachments());
            m.put("level", r.level());
            list.add(m);
            lines.add("  ".repeat(r.level()) + "«" + r.page().title() + "» Version " + r.page().version() + " → "
                    + rel(r.dir()) + (attachments ? " (" + r.attachments() + " Anhänge)" : ""));
        }
        lines.add(results.size() == 1 ? "1 Seite exportiert." : results.size() + " Seiten exportiert.");
        return Map.of("results", list, "log", lines);
    }

    private Object local() throws IOException {
        Path ws = workspace;
        List<Map<String, Object>> pages = new ArrayList<>();
        Files.walkFileTree(ws, EnumSet.noneOf(java.nio.file.FileVisitOption.class), 16, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String n = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(ws) && (n.startsWith(".") || n.equals(PageStore.ATTACHMENTS))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path xhtml = dir.resolve(PageStore.XHTML);
                Path meta = dir.resolve(PageStore.META);
                if (Files.isRegularFile(meta, LinkOption.NOFOLLOW_LINKS)
                        && Files.isRegularFile(xhtml, LinkOption.NOFOLLOW_LINKS)) {
                    pages.add(localEntry(dir, xhtml, meta));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        pages.sort(Comparator.comparing(m -> (String) m.get("dir")));
        return Map.of("pages", pages);
    }

    private Map<String, Object> localEntry(Path dir, Path xhtml, Path metaFile) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dir", rel(dir));
        try {
            PageMeta meta = PageStore.readMeta(metaFile).orElseThrow();
            m.put("id", meta.id());
            m.put("title", meta.title());
            m.put("spaceKey", meta.spaceKey());
            m.put("version", meta.version());
            m.put("localChanged", localChanged(meta, xhtml));
            m.put("webUrl", meta.webUrl());
        } catch (RuntimeException e) {
            m.put("error", toError(e).getMessage());
        }
        return m;
    }

    private static boolean localChanged(PageMeta meta, Path xhtml) {
        return meta.contentHash() != null
                && !meta.contentHash().equals(PageStore.contentHash(PageStore.readXhtml(xhtml)));
    }

    private Object remote(JsonNode b) {
        PageStore.Location loc = PageStore.locate(pageDir(text(b, "dir")));
        PageMeta meta = PageStore.requireMeta(loc);
        PageApi api = api();
        PageStore.requireSameServer(meta, loc.meta(), api.baseUrl(), false);
        Page server = api.getPage(meta.id());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("localVersion", meta.version());
        m.put("serverVersion", server.version());
        m.put("localChanged", localChanged(meta, loc.xhtml()));
        m.put("serverChanged", server.version() != meta.version());
        return m;
    }

    private Object diff(JsonNode b) {
        PageStore.Location loc = PageStore.locate(pageDir(text(b, "dir")));
        PageMeta meta = PageStore.requireMeta(loc);
        String local = PageStore.readXhtml(loc.xhtml());
        PageApi api = api();
        PageStore.requireSameServer(meta, loc.meta(), api.baseUrl(), false);
        Page server = api.getPage(meta.id());
        Differ.Stats stats = Differ.stats(server.storage(), local);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("diff", Differ.unified(server.storage(), local,
                "server/" + PageStore.XHTML + " (Version " + server.version() + ")", "lokal/" + PageStore.XHTML));
        m.put("added", stats.added());
        m.put("removed", stats.removed());
        m.put("serverVersion", server.version());
        m.put("localVersion", meta.version());
        m.put("serverTitle", server.title());
        m.put("localTitle", meta.title());
        return m;
    }

    private Object page(String dir) {
        PageStore.Location loc = PageStore.locate(pageDir(dir));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("storage", PageStore.readXhtml(loc.xhtml()));
        m.put("hash", fileHash(loc.xhtml()));
        m.put("meta", PageStore.readMeta(loc.meta()).map(UiApi::metaMap).orElse(null));
        return m;
    }

    /**
     * With {@code hash} (from GET /page) the file is only written if nobody changed it in the meantime,
     * e.g. Claude Desktop working on the same folder; {@code force} overwrites anyway.
     */
    private Object savePage(JsonNode b) {
        PageStore.Location loc = PageStore.locate(pageDir(text(b, "dir")));
        JsonNode s = b.get("storage");
        if (s == null || !s.isTextual()) {
            throw UiError.input("Feld «storage» fehlt.");
        }
        String expected = blankToNull(text(b, "hash"));
        if (expected != null && !bool(b, "force", false) && !expected.equals(fileHash(loc.xhtml()))) {
            throw new UiError(409, "conflict", PageStore.XHTML + " wurde inzwischen ausserhalb dieses Editors "
                    + "geändert (z.B. in Claude Desktop oder in einem anderen Fenster).").with("reason", "changedOnDisk");
        }
        byte[] bytes = s.asText().getBytes(StandardCharsets.UTF_8);
        PageStore.writeXhtml(loc.xhtml(), s.asText());
        return Map.of("problems", problems(StorageXml.validate(s.asText())), "hash", sha256(bytes));
    }

    private static String fileHash(Path file) {
        try {
            return sha256(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Replaces page.xhtml with the server version; local edits are kept as page.xhtml.bak (export --force). */
    private Object refresh(JsonNode b) {
        PageStore.Location loc = PageStore.locate(pageDir(text(b, "dir")));
        PageMeta meta = PageStore.requireMeta(loc);
        PageApi api = api();
        PageStore.requireSameServer(meta, loc.meta(), api.baseUrl(), false);
        String before = PageStore.readXhtml(loc.xhtml());
        Exporter.Result r = new Exporter(api).exportInto(meta.id(), loc.dir(), new Exporter.Options(false, false, 0, true));
        Path bak = loc.xhtml().resolveSibling(PageStore.XHTML + ".bak");
        boolean backup = !Differ.sameContent(before, r.page().storage()) && Files.isRegularFile(bak)
                && PageStore.readXhtml(bak).equals(before);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.page().id());
        m.put("title", r.page().title());
        m.put("version", r.page().version());
        m.put("dir", rel(loc.dir()));
        m.put("backup", backup ? PageStore.XHTML + ".bak" : null);
        return m;
    }

    private Object push(JsonNode b) {
        Path dir = pageDir(text(b, "dir"));
        PageApi api = api();
        Pusher.Result r = new Pusher(api).push(dir, new Pusher.Options(blankToNull(text(b, "title")),
                blankToNull(text(b, "message")), bool(b, "dryRun", false), bool(b, "force", false),
                bool(b, "attachments", false)));
        String changes = "+%d/-%d Zeilen".formatted(r.stats().added(), r.stats().removed());
        List<String> lines = new ArrayList<>();
        Map<String, Object> m = new LinkedHashMap<>();
        switch (r.status()) {
            case DRY_RUN -> {
                m.put("status", "dry-run");
                lines.add("Probelauf – nichts hochgeladen.");
                lines.add(r.newVersion() == r.serverVersion() ? "Keine Änderung an Inhalt und Titel."
                        : "Würde Version " + r.newVersion() + " erstellen: " + changes + ".");
                Page server = api.getPage(r.pageId());
                m.put("diff", Differ.unified(server.storage(), PageStore.readXhtml(PageStore.locate(dir).xhtml()),
                        "server/" + PageStore.XHTML + " (Version " + server.version() + ")", "lokal/" + PageStore.XHTML));
            }
            case NO_CHANGE -> {
                m.put("status", "unchanged");
                lines.add("Keine Änderung – Seite bleibt auf Version " + r.newVersion() + ".");
            }
            case UPDATED -> {
                m.put("status", "updated");
                lines.add("Seite «" + r.newTitle() + "» aktualisiert: Version " + r.serverVersion() + " → "
                        + r.newVersion() + " (" + changes + ").");
            }
        }
        if (r.titleChanged()) {
            lines.add("Titel: «" + r.oldTitle() + "» → «" + r.newTitle() + "»");
        }
        for (Path p : r.uploads()) {
            lines.add((r.status() == Pusher.Status.DRY_RUN ? "Würde Anhang hochladen: " : "Anhang hochgeladen: ")
                    + p.getFileName());
        }
        m.put("version", r.newVersion());
        m.put("serverVersion", r.serverVersion());
        m.put("webUrl", r.webUrl());
        m.put("added", r.stats().added());
        m.put("removed", r.stats().removed());
        m.put("problems", problems(r.warnings()));
        m.put("log", lines);
        return m;
    }

    private Object open(JsonNode b) {
        String d = text(b, "dir");
        Path dir = d == null || d.isBlank() ? workspace : inWorkspace(d);
        if (looksLikePackage(dir)) {
            throw UiError.input("«" + dir.getFileName() + "» sieht wie ein Programmpaket aus und wird aus "
                    + "Sicherheitsgründen nicht geöffnet.");
        }
        if (!o.launcher.open(dir)) {
            throw new UiError(500, "other", "Ordner konnte nicht geöffnet werden: " + dir);
        }
        return Map.of("path", dir.toString());
    }

    // ---------------------------------------------------------------- IDE

    private Object workspaceInit(JsonNode b) {
        WorkspaceSetup.Report r = setup().init(workspace, new WorkspaceSetup.Options(bool(b, "force", false), true));
        List<Map<String, Object>> files = new ArrayList<>();
        for (WorkspaceSetup.FileResult f : r.files()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", f.path());
            m.put("status", f.status().name().toLowerCase(Locale.ROOT));
            m.put("note", f.note());
            files.add(m);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dir", workspace.toString());
        m.put("files", files);
        m.put("skipped", r.hasSkipped());
        m.put("log", r.lines());
        return m;
    }

    /** Opens the workspace as project (set up first if needed), optionally with a page's page.xhtml. */
    private Object openIde(JsonNode b) {
        Launcher.Ide ide = switch (String.valueOf(text(b, "ide"))) {
            case "vscode" -> Launcher.Ide.VSCODE;
            case "intellij" -> Launcher.Ide.INTELLIJ;
            default -> throw UiError.input("Unbekannte Entwicklungsumgebung: «ide» muss vscode oder intellij sein.");
        };
        String d = blankToNull(text(b, "dir"));
        Path file = d == null ? null : pageDir(d).resolve(PageStore.XHTML);
        Path ws = workspace;
        if (!WorkspaceSetup.isSetUp(ws)) {
            setup().init(ws, new WorkspaceSetup.Options(false, false));
        }
        if (!o.launcher.openIde(ide, ws, file)) {
            throw new UiError(400, "other", ide == Launcher.Ide.VSCODE
                    ? "VS Code wurde nicht gefunden. VS Code installieren oder darin die Befehlspalette öffnen und "
                    + "«Shell Command: Install 'code' command in PATH» ausführen."
                    : "IntelliJ IDEA wurde nicht gefunden. IntelliJ installieren oder darin unter Tools > "
                    + "«Create Command-line Launcher …» den Befehl «idea» einrichten.");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", ws.toString());
        m.put("file", file == null ? null : file.toString());
        m.put("ide", ide.label);
        return m;
    }

    // ---------------------------------------------------------------- paths

    /** A folder that Finder would launch or install instead of showing (app bundle, installer …). */
    static boolean looksLikePackage(Path dir) {
        String name = dir.getFileName() == null ? "" : dir.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot > 0 && PACKAGE_EXTENSIONS.contains(name.substring(dot + 1))
                || Files.exists(dir.resolve("Contents").resolve("Info.plist"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(dir.resolve("Contents").resolve("MacOS"), LinkOption.NOFOLLOW_LINKS);
    }

    /** A folder inside the workspace (after resolving symbolic links). */
    private Path inWorkspace(String dir) {
        if (dir == null || dir.isBlank()) {
            throw UiError.input("Ordner fehlt.");
        }
        Path ws = workspace;
        Path real;
        try {
            real = ws.resolve(dir.strip()).toRealPath();
        } catch (InvalidPathException | NoSuchFileException e) {
            throw UiError.input("Ordner nicht gefunden: " + dir);
        } catch (IOException e) {
            throw UiError.input("Ordner nicht lesbar: " + dir + " (" + e.getMessage() + ")");
        }
        if (!real.startsWith(ws)) {
            throw UiError.input("Ordner liegt ausserhalb des Arbeitsordners: " + dir);
        }
        if (!Files.isDirectory(real)) {
            throw UiError.input("Kein Ordner: " + dir);
        }
        return real;
    }

    /** A page folder inside the workspace whose page files are no symbolic links. */
    private Path pageDir(String dir) {
        Path real = inWorkspace(dir);
        Path xhtml = real.resolve(PageStore.XHTML);
        if (!Files.isRegularFile(xhtml, LinkOption.NOFOLLOW_LINKS)) {
            throw UiError.input("Im Ordner " + dir + " liegt keine " + PageStore.XHTML + ".");
        }
        if (Files.isSymbolicLink(real.resolve(PageStore.META))) {
            throw UiError.input(PageStore.META + " in " + dir + " ist ein symbolischer Link.");
        }
        return real;
    }

    /** Path relative to the workspace with {@code /} separators. */
    private String rel(Path p) {
        String r = workspace.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return r.isEmpty() ? "." : r;
    }

    private Upload upload(String id) {
        Upload u = id == null ? null : uploads.get(id);
        if (u == null || !Files.isRegularFile(u.file())) {
            throw UiError.input("Hochgeladene Datei nicht gefunden – bitte erneut hochladen.");
        }
        return u;
    }

    private static String hideTemp(String message, Path file, String name) {
        return message == null ? name : message.replace(file.toString(), name);
    }

    // ---------------------------------------------------------------- JSON

    static List<Map<String, Object>> problems(List<Problem> problems) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Problem p : problems) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", p.severity().name());
            m.put("line", p.line());
            m.put("column", p.column());
            m.put("message", p.message());
            list.add(m);
        }
        return list;
    }

    private static Map<String, Object> metaMap(PageMeta meta) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", meta.id());
        m.put("title", meta.title());
        m.put("spaceKey", meta.spaceKey());
        m.put("version", meta.version());
        m.put("parentId", meta.parentId());
        m.put("baseUrl", meta.baseUrl());
        m.put("webUrl", meta.webUrl());
        m.put("exportedAt", meta.exportedAt() == null ? null : meta.exportedAt().toString());
        m.put("attachments", meta.attachments().keySet());
        return m;
    }

    private static JsonNode body(HttpExchange ex) throws IOException {
        String length = ex.getRequestHeaders().getFirst("Content-Length");
        if (length != null && parseLong(length) > MAX_JSON) {
            throw new UiError(413, "input", "Anfrage ist zu gross.");
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (InputStream in = ex.getRequestBody()) {
            byte[] chunk = new byte[64 * 1024];
            for (int r; (r = in.read(chunk)) > 0; ) {
                if (buf.size() + r > MAX_JSON) {
                    throw new UiError(413, "input", "Anfrage ist zu gross.");
                }
                buf.write(chunk, 0, r);
            }
        }
        if (buf.size() == 0) {
            return UiServer.JSON.createObjectNode();
        }
        JsonNode n;
        try {
            n = UiServer.JSON.readTree(buf.toByteArray());
        } catch (JsonProcessingException e) {
            throw UiError.input("Ungültiges JSON in der Anfrage.");
        }
        if (n == null || !n.isObject()) {
            throw UiError.input("JSON-Objekt erwartet.");
        }
        return n;
    }

    private static String text(JsonNode b, String field) {
        JsonNode v = b.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String required(JsonNode b, String field) {
        String v = text(b, field);
        if (v == null) {
            throw UiError.input("Feld «" + field + "» fehlt.");
        }
        return v;
    }

    private static boolean bool(JsonNode b, String field, boolean dflt) {
        JsonNode v = b.get(field);
        return v == null || v.isNull() ? dflt : v.asBoolean(dflt);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return m;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            try {
                m.putIfAbsent(URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(eq < 0 ? "" : pair.substring(eq + 1), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw UiError.input("Ungültige Anfrageparameter.");
            }
        }
        return m;
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    private static String version() {
        String v = UiApi.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- errors

    /** UI wording for sync failures whose CLI message names commands and options the UI does not have. */
    private RuntimeException reword(SyncException e) {
        return switch (e.reason()) {
            case NONE -> e;
            case LOCAL_CHANGES -> new UiError(400, "other", "Im Ordner «" + rel(Path.of(e.detail("dir")))
                    + "» gibt es lokale Änderungen, die noch nicht zurückgeschrieben sind (Server: Version "
                    + e.detail("serverVersion") + "). Zuerst unter «Lokale Seiten» zurückschreiben oder "
                    + "«Lokale Änderungen überschreiben» aktivieren – die lokale Fassung wird dann als "
                    + PageStore.XHTML + ".bak gesichert.")
                    .with("reason", "localChanges").with("dir", rel(Path.of(e.detail("dir"))));
            case OTHER_SERVER -> otherServer(e);
            case SPACE_MISMATCH -> new UiError(400, "other", "Die Seite liegt auf dem Server im Space "
                    + e.detail("serverSpace") + ", lokal ist Space " + e.detail("localSpace") + " vermerkt. Falscher "
                    + "Server oder verschobene Seite? Mit «Unterschiede» prüfen. Nur wenn es sicher dieselbe Seite "
                    + "ist: «Trotzdem überschreiben».").with("reason", "spaceMismatch");
            case TITLE_EXISTS -> new UiError(400, "other", "Im Space " + e.detail("spaceKey")
                    + " gibt es bereits eine Seite «" + e.detail("title") + "» (ID " + e.detail("id")
                    + "). Titel sind pro Space eindeutig – bitte einen anderen Titel wählen.")
                    .with("reason", "titleExists");
        };
    }

    /** The page folder belongs to another server: name it and the profile that matches, if any. */
    private UiError otherServer(SyncException e) {
        String pageServer = e.detail("pageServer");
        String target = PageStore.normalizeBaseUrl(pageServer);
        String match = null;
        Properties props;
        try {
            props = ConfigFile.read(ConfigFile.defaultPath(o.env));
        } catch (ConfigException ignored) {
            props = new Properties();
        }
        for (Map<String, Object> c : connections(props)) {
            Object url = c.get("url");
            if (url != null && PageStore.normalizeBaseUrl(url.toString()).equals(target)) {
                match = c.get("profile") == null ? "" : c.get("profile").toString();
                break;
            }
        }
        Path file = Path.of(e.detail("file"));
        String where = file.getParent() == null ? "" : " in «" + rel(file.getParent()) + "»";
        String hint = match == null
                ? " Unter «Verbindung» die Adresse " + pageServer + " eintragen (mit dem Token für diesen Server)."
                : match.isEmpty() ? " Das Standardprofil passt dazu." : " Das Profil «" + match + "» passt dazu.";
        return new UiError(400, "other", "Die Seite" + where + " stammt von " + pageServer + ", verbunden ist aber "
                + e.detail("current") + "." + hint)
                .with("reason", "otherServer").with("serverUrl", pageServer).with("switchProfile", match);
    }

    /** Maps an exception to status and kind (docs/UI-SPEC.md); messages are user-facing, no stack traces. */
    static UiError toError(Throwable e) {
        return switch (e) {
            case UiError u -> u;
            case ValidationFailedException v -> new UiError(422, "validation", v.getMessage());
            // the CLI text names «cfh diff»/«--force»; the UI offers its own buttons for that
            case VersionConflictException v -> new UiError(409, "conflict", "Lokal Version %d, auf dem Server Version %d."
                    .formatted(v.localVersion(), v.serverVersion()));
            case SyncException s -> switch (s.exitCode()) {
                case SyncException.VALIDATION -> new UiError(422, "validation", s.getMessage());
                case SyncException.VERSION_CONFLICT -> new UiError(409, "conflict", s.getMessage());
                case SyncException.AUTH -> new UiError(403, "auth", s.getMessage());
                default -> new UiError(400, "other", s.getMessage());
            };
            case ConfluenceException c when c.isAuthProblem() -> new UiError(c.status(), "auth",
                    String.valueOf(c.getMessage()).contains("prüfen") ? c.getMessage() : c.getMessage()
                    + " – Token bzw. Benutzername/Passwort prüfen und ob du Lese-/Schreibrechte auf Seite und Space hast.");
            case ConfluenceException c when c.status() == 404 -> new UiError(404, "notfound", c.getMessage());
            case ConfluenceException c when c.status() == 409 -> new UiError(409, "conflict", c.getMessage());
            // not reachable or failing upstream: expected in daily use, so 502 instead of «unexpected» 500
            case ConfluenceException c when c.status() <= 0 -> new UiError(502, "other", c.getMessage()
                    + " – Adresse unter «Verbindung» prüfen und ob Confluence erreichbar ist (VPN, Proxy).");
            case ConfluenceException c -> new UiError(c.status() >= 500 ? 502 : 400, "other",
                    String.valueOf(c.getMessage()).contains("(HTTP " + c.status() + ")") ? c.getMessage()
                            : "Confluence meldet HTTP " + c.status() + ": " + c.getMessage());
            case ConfigException c -> new UiError(400, "config", c.getMessage());
            case IllegalArgumentException i -> UiError.input(i.getMessage());
            case UncheckedIOException u when u.getCause() instanceof NoSuchFileException n ->
                    UiError.input("Datei nicht gefunden: " + n.getFile());
            case UncheckedIOException u -> new UiError(500, "other", u.getMessage());
            case IOException io -> new UiError(500, "other", "Ein-/Ausgabefehler: " + io.getMessage());
            default -> new UiError(500, "other", "Interner Fehler: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " – " + e.getMessage()));
        };
    }

    /** Forwards log lines; repeated warnings (e.g. «Verbindung ohne HTTPS» on every request) only once. */
    private static final class OnceLog extends OutputStream {

        private final PrintStream target;
        private final Set<String> seen = ConcurrentHashMap.newKeySet();
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        OnceLog(PrintStream target) {
            this.target = target;
        }

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                String s = line.toString(StandardCharsets.UTF_8).stripTrailing();
                line.reset();
                if (!s.startsWith("Warnung") || seen.add(s)) {
                    target.println(s);
                }
            } else {
                line.write(b);
            }
        }
    }
}
