package ch.confluencehelper.config;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Connection settings. {@link #toString()} never reveals token or password.
 *
 * <p>Sources by descending priority: CLI options ({@code --url}, {@code --profile}), environment
 * variables, properties file ({@code ~/.config/confluence-helper/config.properties} or
 * {@code $CFH_CONFIG}). When a profile is selected, the profile's keys in the file are used and
 * the {@code CONFLUENCE_*} environment variables are ignored, so credentials of different servers
 * are never mixed.
 */
public record Config(URI baseUrl, String token, String username, String password) {

    public static final String ENV_URL = "CONFLUENCE_URL";
    public static final String ENV_TOKEN = "CONFLUENCE_TOKEN";
    public static final String ENV_USER = "CONFLUENCE_USER";
    public static final String ENV_PASSWORD = "CONFLUENCE_PASSWORD";
    public static final String ENV_CONFIG = "CFH_CONFIG";

    public Config {
        token = blankToNull(token);
        username = blankToNull(username);
        password = blankToNull(password);
        // the values never go into a message: the JDK would echo an invalid header value in full
        if (token != null && !token.chars().allMatch(c -> c >= 0x21 && c <= 0x7e)) {
            throw new ConfigException("Der Token enthält ungültige Zeichen (z.B. ein unsichtbares Leerzeichen "
                    + "aus Copy & Paste). Token erneut kopieren und ohne Zusatzzeichen eintragen.");
        }
        if (username != null && !visibleCharacters(username) || password != null && !visibleCharacters(password)) {
            throw new ConfigException("Benutzername oder Passwort enthält ungültige (unsichtbare) Zeichen, "
                    + "z.B. aus Copy & Paste. Bitte neu eintragen.");
        }
    }

    /** No control, formatting (zero-width) or exotic space characters; a plain space is allowed. */
    private static boolean visibleCharacters(String s) {
        return s.chars().noneMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
                || c != ' ' && Character.isSpaceChar(c));
    }

    /** Loads from file, environment and override, see docs/SPEC.md. @throws ConfigException if incomplete */
    public static Config load(String urlOverride, String profile) {
        return load(urlOverride, profile, System.getenv(), System.err);
    }

    /**
     * Loads with an explicit environment instead of {@link System#getenv()}; the config file is
     * {@code $CFH_CONFIG} from {@code env} or the default location. Lets embedders and tests run
     * the CLI against a different server without touching the process environment.
     */
    public static Config load(String urlOverride, String profile, Map<String, String> env, PrintStream warnings) {
        return load(urlOverride, profile, env, null, warnings);
    }

    /**
     * Testable variant.
     *
     * @param file config file; {@code null} = {@code $CFH_CONFIG} from {@code env} or the default location
     * @param warnings receives warnings (insecure permissions, plain http)
     */
    static Config load(String urlOverride, String profile, Map<String, String> env, Path file,
                       PrintStream warnings) {
        Path path = file != null ? file : defaultFile(env);
        Properties props = readProperties(path, warnings);
        String p = blankToNull(profile);

        String url;
        String token;
        String user;
        String password;
        if (p != null) {
            if (props == null) {
                throw new ConfigException("Profil '" + p + "' angegeben, aber Konfigurationsdatei fehlt: " + path);
            }
            String prefix = p + ".";
            if (props.stringPropertyNames().stream().noneMatch(k -> k.startsWith(prefix))) {
                throw new ConfigException("Profil '" + p + "' nicht gefunden in " + path);
            }
            url = prop(props, prefix + "url");
            token = prop(props, prefix + "token");
            user = prop(props, prefix + "username");
            password = prop(props, prefix + "password");
        } else {
            url = firstNonBlank(env.get(ENV_URL), prop(props, "url"));
            token = firstNonBlank(env.get(ENV_TOKEN), prop(props, "token"));
            user = firstNonBlank(env.get(ENV_USER), prop(props, "username"));
            password = firstNonBlank(env.get(ENV_PASSWORD), prop(props, "password"));
        }
        url = firstNonBlank(urlOverride, url);

        if (url == null) {
            throw new ConfigException("Keine Confluence-URL konfiguriert. --url, " + ENV_URL
                    + " oder 'url' in " + path + " setzen.");
        }
        URI baseUrl = parseUrl(url, warnings);
        if (token == null && (user == null || password == null)) {
            throw new ConfigException("Keine Zugangsdaten konfiguriert. " + ENV_TOKEN
                    + " (Personal Access Token) oder " + ENV_USER + " und " + ENV_PASSWORD
                    + " setzen (bzw. 'token' oder 'username'/'password' in " + path + ").");
        }
        return new Config(baseUrl, token, user, password);
    }

    static Path defaultFile(Map<String, String> env) {
        String custom = blankToNull(env.get(ENV_CONFIG));
        if (custom != null) {
            return Path.of(custom);
        }
        return Path.of(System.getProperty("user.home"), ".config", "confluence-helper", "config.properties");
    }

    /** Base URL with trailing slashes removed, so paths like {@code /rest/api/...} can be appended. */
    static URI parseUrl(String url, PrintStream warnings) {
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new ConfigException("Ungültige Confluence-URL: " + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("https") && !scheme.equals("http") || uri.getHost() == null) {
            throw new ConfigException("Ungültige Confluence-URL (erwartet https://…): " + url);
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new ConfigException("Confluence-URL darf weder Query noch Fragment enthalten: " + url);
        }
        if (scheme.equals("http")) {
            warnings.println("Warnung: Verbindung ohne HTTPS (" + uri + "), Zugangsdaten werden unverschlüsselt übertragen.");
        }
        return uri;
    }

    private static Properties readProperties(Path path, PrintStream warnings) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        warnIfReadableByOthers(path, warnings);
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            throw new ConfigException("Konfigurationsdatei kann nicht gelesen werden: " + path + " (" + e.getMessage() + ")");
        }
        return props;
    }

    private static void warnIfReadableByOthers(Path path, PrintStream warnings) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
            if (perms.contains(PosixFilePermission.GROUP_READ) || perms.contains(PosixFilePermission.OTHERS_READ)) {
                warnings.println("Warnung: " + path + " ist für andere Benutzer lesbar. Empfohlen: chmod 600 " + path);
            }
        } catch (UnsupportedOperationException | IOException e) {
            // not a POSIX file system: nothing to check
        }
    }

    private static String prop(Properties props, String key) {
        return props == null ? null : blankToNull(props.getProperty(key));
    }

    private static String firstNonBlank(String a, String b) {
        String x = blankToNull(a);
        return x != null ? x : blankToNull(b);
    }

    /**
     * Trims whitespace including what {@link String#trim()} keeps: no-break spaces (U+00A0, U+202F),
     * zero-width spaces (U+200B) and byte order marks, which often stick to copied tokens.
     */
    static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        int start = 0;
        int end = s.length();
        while (start < end && isInvisible(s.charAt(start))) {
            start++;
        }
        while (end > start && isInvisible(s.charAt(end - 1))) {
            end--;
        }
        return start == end ? null : s.substring(start, end);
    }

    private static boolean isInvisible(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c)
                || Character.getType(c) == Character.FORMAT;
    }

    public boolean usesToken() {
        return token != null;
    }

    @Override
    public String toString() {
        return "Config[baseUrl=" + baseUrl
                + ", token=" + (token == null ? "null" : "****")
                + ", username=" + username
                + ", password=" + (password == null ? "null" : "****") + "]";
    }
}
