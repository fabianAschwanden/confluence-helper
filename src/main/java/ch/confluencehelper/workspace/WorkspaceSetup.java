package ch.confluencehelper.workspace;

import ch.confluencehelper.storage.StorageXml;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Turns a working folder into a project for VS Code and IntelliJ with GitHub Copilot: instructions,
 * prompt files, AGENTS.md, VS Code settings and tasks, IntelliJ run configurations, .editorconfig.
 * Page folders are never touched. Generated files are tracked by hash in {@value #MANIFEST}, so a re-run
 * updates what is still as generated and keeps what the user changed.
 */
public final class WorkspaceSetup {

    public static final int TEMPLATE_VERSION = 1;
    public static final String MANIFEST = ".cfh/workspace.json";

    private static final String ROOT = "ch/confluencehelper/workspace/";
    private static final List<String> PROMPTS = List.of("seite-erweitern", "tabelle-ergaenzen", "zusammenfassung-oben",
            "aus-datei-uebernehmen", "pruefen-und-diff");
    private static final List<String> RUN_CONFIGS = List.of("cfh-pruefen", "cfh-unterschiede", "cfh-probelauf",
            "cfh-zurueckschreiben", "cfh-exportieren");
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public enum Status {
        CREATED("angelegt"), UPDATED("aktualisiert"), UNCHANGED("unverändert"), REPLACED("ersetzt"),
        SKIPPED("übersprungen");

        public final String label;

        Status(String label) {
            this.label = label;
        }
    }

    /** @param path relative to the working folder, {@code /} separated; @param note reason or backup name */
    public record FileResult(String path, Status status, String note) {
    }

    public record Report(Path dir, List<FileResult> files) {

        public boolean hasSkipped() {
            return files.stream().anyMatch(f -> f.status() == Status.SKIPPED);
        }

        /** One line per file, German, for the CLI and the UI log. */
        public List<String> lines() {
            List<String> out = new ArrayList<>();
            for (FileResult f : files) {
                out.add("%-14s %s%s".formatted(f.status().label + ":", f.path(),
                        f.note() == null ? "" : " (" + f.note() + ")"));
            }
            return out;
        }
    }

    /**
     * @param force           replace files changed by the user, keeping their version as {@code .bak}
     * @param recreateDeleted create generated files again that the user deleted (explicit init; not on UI start)
     */
    public record Options(boolean force, boolean recreateDeleted) {
    }

    private final Installation installation;
    private final boolean windows;

    public WorkspaceSetup(Installation installation) {
        this(installation, System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
    }

    WorkspaceSetup(Installation installation, boolean windows) {
        this.installation = installation;
        this.windows = windows;
    }

    public static boolean isSetUp(Path dir) {
        return Files.isRegularFile(dir.resolve(MANIFEST), LinkOption.NOFOLLOW_LINKS);
    }

    /** The block for Claude Desktop's project instructions; docs/claude-desktop.md contains it verbatim. */
    public static String claudeDesktopBlock() {
        return resource("claude-desktop-intro.md") + resource("storage-rules.md");
    }

    /** Target path (relative, {@code /}) → content, in the order they are reported. */
    public Map<String, String> render() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(".github/copilot-instructions.md", fill("copilot-instructions.md"));
        files.put(".github/instructions/confluence-storage.instructions.md", fill("confluence-storage.instructions.md"));
        for (String p : PROMPTS) {
            files.put(".github/prompts/" + p + ".prompt.md", fill("prompts/" + p + ".prompt.md"));
        }
        files.put("AGENTS.md", fill("AGENTS.md"));
        files.put(".vscode/settings.json", fill("vscode-settings.json"));
        files.put(".vscode/tasks.json", fill("vscode-tasks.json"));
        files.put(".vscode/extensions.json", fill("vscode-extensions.json"));
        if (installation.hasJar()) {
            for (String r : RUN_CONFIGS) {
                files.put(".run/" + r + ".run.xml", fill("run/" + r + ".run.xml"));
            }
        }
        files.put(".editorconfig", fill("editorconfig"));
        return files;
    }

    public Report init(Path dir, Options options) {
        Path root = dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Ordner nicht gefunden: " + dir);
        }
        Map<String, String> known = readManifest(root);
        Map<String, String> hashes = new TreeMap<>(known);
        List<FileResult> results = new ArrayList<>();
        for (Map.Entry<String, String> e : render().entrySet()) {
            results.add(apply(root, e.getKey(), e.getValue().getBytes(StandardCharsets.UTF_8), known, hashes, options));
        }
        if (!installation.hasJar()) {
            results.add(new FileResult(".run/", Status.SKIPPED, "IntelliJ-Run-Konfigurationen brauchen cfh aus "
                    + "target/cfh.jar; mit bin/cfh starten"));
        }
        if (!linkOnPath(root, root.resolve(MANIFEST))) {
            ObjectNode n = JSON.createObjectNode();
            n.put("templateVersion", TEMPLATE_VERSION);
            hashes.forEach(n.putObject("files")::put);
            try {
                write(root.resolve(MANIFEST), JSON.writeValueAsBytes(n));
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        return new Report(root, results);
    }

    private static FileResult apply(Path root, String rel, byte[] content, Map<String, String> known,
                                    Map<String, String> hashes, Options options) {
        Path target = root.resolve(rel);
        if (linkOnPath(root, target)) {
            return new FileResult(rel, Status.SKIPPED, "symbolischer Link, nicht angefasst");
        }
        String hash = sha256(content);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (known.containsKey(rel) && !options.recreateDeleted() && !options.force()) {
                return new FileResult(rel, Status.SKIPPED, "von dir gelöscht");
            }
            write(target, content);
            hashes.put(rel, hash);
            return new FileResult(rel, Status.CREATED, null);
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return new FileResult(rel, Status.SKIPPED, "keine normale Datei");
        }
        String current = sha256(read(target));
        if (current.equals(hash)) {
            hashes.put(rel, hash);
            return new FileResult(rel, Status.UNCHANGED, null);
        }
        if (current.equals(known.get(rel))) {
            write(target, content);
            hashes.put(rel, hash);
            return new FileResult(rel, Status.UPDATED, null);
        }
        if (!options.force()) {
            return new FileResult(rel, Status.SKIPPED, known.containsKey(rel)
                    ? "von dir geändert; --force ersetzt sie und sichert deine Fassung als .bak"
                    : "bestehende Datei, nicht von cfh; --force ersetzt sie und sichert sie als .bak");
        }
        Path backup = backupName(target);
        try {
            Files.copy(target, backup, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new UncheckedIOException("Kann " + backup + " nicht anlegen: " + e.getMessage(), e);
        }
        write(target, content);
        hashes.put(rel, hash);
        return new FileResult(rel, Status.REPLACED, "deine Fassung: " + root.relativize(backup).toString().replace('\\', '/'));
    }

    // ---------------------------------------------------------------- templates

    private String fill(String resource) {
        String text = resource(resource);
        String ext = resource.substring(resource.lastIndexOf('.') + 1);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("STORAGE_RULES", resource("storage-rules.md"));
        values.put("CFH_SHELL", installation.shellCommand(windows));
        values.put("CFH", installation.command());
        values.put("CFH_WINDOWS", installation.commandWindows());
        // Windows tasks start java.exe directly: cmd.exe would parse & | % ^ in arguments passed to cfh.cmd
        values.put("WIN_COMMAND", installation.hasJar()
                ? installation.javaHome().resolve("bin").resolve("java.exe").toString() : installation.commandWindows());
        values.put("CFH_APPROVE", autoApprovePattern(installation));
        values.put("JAR", installation.jar() == null ? "" : installation.jar().toString());
        values.put("JAVA_HOME", installation.javaHome() == null ? "" : installation.javaHome().toString());
        for (Map.Entry<String, String> v : values.entrySet()) {
            String value = switch (ext) {
                case "json" -> new String(JsonStringEncoder.getInstance().quoteAsString(v.getValue()));
                case "xml" -> StorageXml.escapeAttr(v.getValue());
                default -> v.getValue();
            };
            text = text.replace("{{" + v.getKey() + "}}", value);
        }
        JsonStringEncoder enc = JsonStringEncoder.getInstance();
        return text.replace("{{WIN_JAR_ARGS}}", installation.hasJar()
                ? "\"-jar\", \"" + new String(enc.quoteAsString(installation.jar().toString())) + "\", " : "");
    }

    private static final String ARG_BAD = "\\s\"'`$;&|<>(){}\\[\\]@#,!*?%^=";

    /**
     * Regex (JavaScript and Java) for the cfh calls VS Code's agent may run without asking: this launcher
     * exactly, {@code validate} or {@code diff}, one page argument, no option (so no {@code --url} or
     * {@code --other-server}), no environment prefix and no shell syntax. VS Code neither anchors regex
     * rules nor stops at options, hence {@code ^…$} and the restricted argument.
     */
    static String autoApprovePattern(Installation inst) {
        Set<String> launchers = new LinkedHashSet<>();
        String sh = inst.command();
        String win = inst.commandWindows();
        launchers.add(inst.shellCommand(false));
        if (sh.indexOf('\'') < 0) {
            launchers.add("'" + sh + "'");
        }
        if (!sh.matches(".*[\"$`\\\\!].*")) {
            launchers.add("\"" + sh + "\"");
        }
        if (win.matches("[A-Za-z0-9._:\\\\/-]+")) {
            launchers.add(win);
        }
        if (!win.matches(".*[\"$`].*")) {
            launchers.add("\"" + win + "\"");
        }
        if (win.indexOf('\'') < 0) {
            launchers.add("'" + win + "'");
        }
        List<String> alternatives = launchers.stream().map(WorkspaceSetup::regexQuote).toList();
        String arg = "(?:'[^'\\r\\n-][^'\\r\\n]*'|\"[^\"$`!\\r\\n-][^\"$`!\\r\\n]*\"|[^" + ARG_BAD + "\\\\-][^"
                + ARG_BAD + "]*)";
        return "^(?:&[ \\t]+)?(?:" + String.join("|", alternatives) + ")[ \\t]+(?:validate|diff)[ \\t]+" + arg
                + "[ \\t]*$";
    }

    /** Escapes regex syntax the same way for JavaScript and Java (no \Q…\E, which JavaScript lacks). */
    private static String regexQuote(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if ("\\^$.|?*+()[]{}/-".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    static String resource(String name) {
        try (InputStream in = WorkspaceSetup.class.getClassLoader().getResourceAsStream(ROOT + name)) {
            if (in == null) {
                throw new IllegalStateException("Vorlage fehlt: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- files

    private static Map<String, String> readManifest(Path root) {
        Path file = root.resolve(MANIFEST);
        Map<String, String> m = new TreeMap<>();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return m;
        }
        try {
            JsonNode files = JSON.readTree(file.toFile()).path("files");
            files.properties().forEach(e -> {
                if (e.getValue().isTextual()) {
                    m.put(e.getKey(), e.getValue().asText());
                }
            });
        } catch (IOException e) {
            // unreadable: every existing file then counts as the user's own and stays untouched
        }
        return m;
    }

    /** Whether {@code target} or a folder between {@code root} and it is a symbolic link. */
    private static boolean linkOnPath(Path root, Path target) {
        for (Path p = target; p != null && !p.equals(root); p = p.getParent()) {
            if (Files.isSymbolicLink(p)) {
                return true;
            }
        }
        return false;
    }

    private static Path backupName(Path target) {
        Path backup = target.resolveSibling(target.getFileName() + ".bak");
        for (int i = 2; Files.exists(backup, LinkOption.NOFOLLOW_LINKS); i++) {
            backup = target.resolveSibling(target.getFileName() + "." + i + ".bak");
        }
        return backup;
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Kann " + file + " nicht lesen: " + e.getMessage(), e);
        }
    }

    private static void write(Path file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), ".cfh-", ".tmp");
            try {
                Files.write(tmp, bytes);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Kann " + file + " nicht schreiben: " + e.getMessage(), e);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
