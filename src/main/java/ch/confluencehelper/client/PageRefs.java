package ch.confluencehelper.client;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses page ids and Confluence page URLs, see docs/SPEC.md. Supported forms:
 * {@code 123}, {@code …/pages/viewpage.action?pageId=123}, {@code …/pages/123/…},
 * {@code …/spaces/KEY/pages/123/…}, {@code …/display/KEY/Title+With+Plus}.
 */
public final class PageRefs {

    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern PAGE_ID_PARAM = Pattern.compile("(?:^|&)pageId=(\\d+)(?:&|$)");

    private PageRefs() {
    }

    /**
     * Like {@link #parse(String)}, but a URL must point to {@code baseUrl} (host, port, context path):
     * page IDs are only unique per server, so a URL of another server must not pick a page here.
     */
    public static PageRef parse(String input, URI baseUrl) {
        PageRef ref = parse(input);
        String s = input.trim();
        int scheme = s.indexOf("://");
        if (baseUrl == null || scheme <= 0) {
            return ref;
        }
        String rest = s.substring(scheme + 3);
        int end = rest.length();
        for (char c : new char[]{'/', '?', '#'}) {
            int i = rest.indexOf(c);
            if (i >= 0 && i < end) {
                end = i;
            }
        }
        String path = end < rest.length() && rest.charAt(end) == '/' ? rest.substring(end).split("[?#]", 2)[0] : "";
        URI u;
        try {
            u = new URI(s.substring(0, scheme).toLowerCase(Locale.ROOT) + "://" + rest.substring(0, end));
        } catch (URISyntaxException e) {
            u = null;
        }
        String basePath = baseUrl.getRawPath() == null ? "" : baseUrl.getRawPath().replaceAll("/+$", "");
        boolean sameHost = u != null && u.getHost() != null && u.getHost().equalsIgnoreCase(baseUrl.getHost());
        boolean samePort = u != null && (!u.getScheme().equalsIgnoreCase(baseUrl.getScheme()) || port(u) == port(baseUrl));
        boolean samePath = basePath.isEmpty() || path.equals(basePath) || path.startsWith(basePath + "/");
        if (!sameHost || !samePort || !samePath) {
            String where = sameHost && samePort ? "Die Adresse liegt nicht unter " + baseUrl
                    : "Die Adresse gehört zu " + (u == null || u.getHost() == null ? "einem anderen Server"
                    : rest.substring(0, end)) + ", verbunden ist aber " + baseUrl;
            throw new IllegalArgumentException(where + ". Zuerst mit dem passenden Server verbinden "
                    + "oder nur die Seiten-ID angeben.");
        }
        return ref;
    }

    private static int port(URI u) {
        if (u.getPort() >= 0) {
            return u.getPort();
        }
        return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    /** @throws IllegalArgumentException if the input is neither an id nor a recognised page URL */
    public static PageRef parse(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Keine Seite angegeben (ID oder Confluence-URL erwartet).");
        }
        String s = input.trim();
        if (DIGITS.matcher(s).matches()) {
            return PageRef.ofId(s);
        }

        // Manual split instead of java.net.URI: pasted URLs may contain spaces or unencoded umlauts.
        int hash = s.indexOf('#');
        if (hash >= 0) {
            s = s.substring(0, hash);
        }
        String query = "";
        int q = s.indexOf('?');
        if (q >= 0) {
            query = s.substring(q + 1);
            s = s.substring(0, q);
        }
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            int slash = s.indexOf('/', scheme + 3);
            s = slash >= 0 ? s.substring(slash) : "";
        }

        Matcher m = PAGE_ID_PARAM.matcher(query);
        if (m.find()) {
            return PageRef.ofId(m.group(1));
        }

        List<String> segments = new ArrayList<>();
        for (String seg : s.split("/")) {
            if (!seg.isEmpty()) {
                segments.add(seg);
            }
        }
        for (int i = 0; i + 1 < segments.size(); i++) {
            if (segments.get(i).equals("pages") && DIGITS.matcher(segments.get(i + 1)).matches()) {
                return PageRef.ofId(segments.get(i + 1));
            }
        }
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).equals("display")) {
                // exactly /display/SPACE/Title; deeper paths are blog posts, shorter ones space home pages
                if (segments.size() - i == 3) {
                    String space = decode(segments.get(i + 1));
                    String title = decode(segments.get(i + 2));
                    if (!space.isBlank() && !title.isBlank()) {
                        return PageRef.ofTitle(space, title);
                    }
                }
                break;
            }
        }
        throw new IllegalArgumentException("Keine gültige Seiten-ID oder Confluence-Seiten-URL: " + input.trim());
    }

    /** URL-decodes a path segment; {@code +} means space, as in Confluence display URLs. */
    private static String decode(String segment) {
        try {
            return URLDecoder.decode(segment, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return segment.replace('+', ' ');
        }
    }
}
