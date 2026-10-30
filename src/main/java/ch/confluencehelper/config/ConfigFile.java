package ch.confluencehelper.config;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Reads and updates properties files such as {@code config.properties} in place: comments, blank lines,
 * unrelated keys and other profiles stay as they are. Files are written atomically with permission 600.
 */
public final class ConfigFile {

    private static final Pattern PROFILE = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Set<String> CONNECTION_KEYS = Set.of("url", "token", "username", "password");

    private ConfigFile() {
    }

    /** The config file {@link Config#load} reads: {@code $CFH_CONFIG} from {@code env} or the default location. */
    public static Path defaultPath(Map<String, String> env) {
        return Config.defaultFile(env);
    }

    /** All properties of {@code file}; empty if it does not exist. */
    public static Properties read(Path file) {
        Properties props = new Properties();
        if (!Files.isRegularFile(file)) {
            return props;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(r);
        } catch (IOException e) {
            throw new ConfigException("Konfigurationsdatei kann nicht gelesen werden: " + file + " (" + e.getMessage() + ")");
        }
        return props;
    }

    /** Profile names defined in {@code props} ({@code <name>.url}, {@code <name>.token} …), sorted. */
    public static List<String> profiles(Properties props) {
        Set<String> names = new TreeSet<>();
        for (String key : props.stringPropertyNames()) {
            int dot = key.lastIndexOf('.');
            if (dot > 0 && CONNECTION_KEYS.contains(key.substring(dot + 1))) {
                names.add(key.substring(0, dot));
            }
        }
        return List.copyOf(names);
    }

    /**
     * Stores connection settings (top level when {@code profile} is blank). With a {@code username} the
     * connection uses Basic auth and a stored token is removed; otherwise a token is used and stored
     * username/password are removed. A blank {@code token}/{@code password} keeps the stored one, but only
     * while the URL points to the same server (scheme, host, port) as the stored URL of that profile.
     *
     * @throws ConfigException if the URL, the profile name or the resulting credentials are invalid
     */
    public static void saveConnection(Path file, String profile, String url, String token, String username,
                                      String password) {
        Prepared p = prepare(file, profile, url, token, username, password);
        update(file, p.set(), p.remove());
    }

    /** The connection {@link #saveConnection} would store, without writing anything (to test it first). */
    public static Config resolveConnection(Path file, String profile, String url, String token, String username,
                                           String password) {
        return prepare(file, profile, url, token, username, password).config();
    }

    private record Prepared(Config config, Map<String, String> set, Set<String> remove) {
    }

    private static Prepared prepare(Path file, String profile, String url, String token, String username,
                                    String password) {
        String p = Config.blankToNull(profile);
        if (p != null && !PROFILE.matcher(p).matches()) {
            throw new ConfigException("Ungültiger Profilname «" + p + "» (erlaubt: Buchstaben, Ziffern, - und _)");
        }
        String u = Config.blankToNull(url);
        if (u == null) {
            throw new ConfigException("Confluence-URL fehlt.");
        }
        String normalizedUrl = Config.parseUrl(u, new PrintStream(PrintStream.nullOutputStream())).toString();
        String prefix = p == null ? "" : p + ".";
        Properties existing = read(file);
        // a stored secret only ever goes to the server it was saved for
        String storedUrl = Config.blankToNull(existing.getProperty(prefix + "url"));
        boolean sameServer = storedUrl != null && sameServer(storedUrl, normalizedUrl);
        String user = Config.blankToNull(username);
        Map<String, String> set = new LinkedHashMap<>();
        Set<String> remove = new TreeSet<>();
        set.put(prefix + "url", normalizedUrl);
        String tok = null;
        String pw = null;
        String kept = null;
        if (user != null) {
            pw = Config.blankToNull(password);
            if (pw == null) {
                kept = Config.blankToNull(existing.getProperty(prefix + "password"));
                pw = sameServer ? kept : null;
            }
            set.put(prefix + "username", user);
            if (pw != null) {
                set.put(prefix + "password", pw);
            }
            remove.add(prefix + "token");
        } else {
            tok = Config.blankToNull(token);
            if (tok == null) {
                kept = Config.blankToNull(existing.getProperty(prefix + "token"));
                tok = sameServer ? kept : null;
            }
            if (tok != null) {
                set.put(prefix + "token", tok);
            }
            remove.add(prefix + "username");
            remove.add(prefix + "password");
        }
        if (tok == null && (user == null || pw == null)) {
            String what = user == null ? "Personal Access Token" : "Passwort";
            throw new ConfigException(kept != null
                    ? "Bei geänderter Adresse bitte " + (user == null ? "den " : "das ") + what + " neu eingeben ("
                    + (user == null ? "der" : "das") + " gespeicherte wird nur an " + storedUrl + " gesendet)."
                    : what + " fehlt.");
        }
        Config config = new Config(URI.create(normalizedUrl), tok, user, pw); // character checks
        return new Prepared(config, set, remove);
    }

    /** Same scheme, host and port (default ports included). */
    static boolean sameServer(String a, String b) {
        try {
            URI x = URI.create(a.strip());
            URI y = URI.create(b.strip());
            return x.getScheme() != null && x.getScheme().equalsIgnoreCase(y.getScheme())
                    && x.getHost() != null && x.getHost().equalsIgnoreCase(y.getHost())
                    && port(x) == port(y);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int port(URI u) {
        return u.getPort() >= 0 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    /**
     * Sets and removes keys, keeping everything else (including comments and layout) unchanged. New keys
     * are appended. Creates the file and its parent folders if needed.
     */
    public static void update(Path file, Map<String, String> set, Set<String> remove) {
        List<String> lines;
        try {
            lines = Files.isRegularFile(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
        } catch (IOException e) {
            throw new ConfigException("Konfigurationsdatei kann nicht gelesen werden: " + file + " (" + e.getMessage() + ")");
        }
        Map<String, String> pending = new LinkedHashMap<>(set);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            List<String> logical = new ArrayList<>(List.of(lines.get(i)));
            // comments never continue, even when they end in a backslash
            while (!isComment(logical.get(0)) && continues(logical.get(logical.size() - 1)) && i + 1 < lines.size()) {
                logical.add(lines.get(++i));
            }
            String key = keyOf(String.join("\n", logical));
            if (key == null || !set.containsKey(key) && !remove.contains(key)) {
                out.addAll(logical);
            } else if (pending.containsKey(key)) {
                out.add(escapeKey(key) + "=" + escapeValue(pending.remove(key)));
            }
            // else: removed key or later duplicate of a key already written
        }
        pending.forEach((k, v) -> out.add(escapeKey(k) + "=" + escapeValue(v)));
        StringBuilder sb = new StringBuilder();
        out.forEach(l -> sb.append(l).append('\n'));
        writePrivate(file, sb.toString());
    }

    /** A line ending in an odd number of backslashes continues on the next line. */
    private static boolean continues(String line) {
        int n = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            n++;
        }
        return n % 2 == 1;
    }

    private static boolean isComment(String line) {
        String trimmed = line.stripLeading();
        return trimmed.startsWith("#") || trimmed.startsWith("!");
    }

    /** Key of a logical properties line, or null for comments and blank lines. */
    private static String keyOf(String logicalLine) {
        if (logicalLine.isBlank() || isComment(logicalLine)) {
            return null;
        }
        Properties p = new Properties();
        try {
            p.load(new StringReader(logicalLine));
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
        return p.stringPropertyNames().stream().findFirst().orElse(null);
    }

    private static String escapeKey(String key) {
        return escape(key, true);
    }

    private static String escapeValue(String value) {
        return escape(value, false);
    }

    private static String escape(String s, boolean key) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\f' -> sb.append("\\f");
                case '=', ':', '#', '!' -> sb.append(key || i == 0 ? "\\" + c : String.valueOf(c));
                case ' ' -> sb.append(key || i == 0 ? "\\ " : " ");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Atomic write via a temp file that is created with permission 600 (on POSIX file systems). */
    private static void writePrivate(Path file, String content) {
        try {
            Path target = Files.isSymbolicLink(file) ? file.toRealPath() : file.toAbsolutePath();
            Path dir = target.getParent();
            boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
            if (!Files.isDirectory(dir)) {
                if (posix) {
                    Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                } else {
                    Files.createDirectories(dir);
                }
            }
            Path tmp = posix
                    ? Files.createTempFile(dir, ".cfh-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                    : Files.createTempFile(dir, ".cfh-", ".tmp");
            try {
                Files.writeString(tmp, content, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Kann " + file + " nicht schreiben: " + e.getMessage(), e);
        }
    }
}
