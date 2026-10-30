package ch.confluencehelper.sync;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/** File and folder names that are safe on Windows, macOS and Linux. */
public final class Slugs {

    public static final int MAX_SLUG = 60;
    static final int MAX_FILE_NAME = 200;

    private static final String WINDOWS_FORBIDDEN = "<>:\"/\\|?*";
    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$",
            "COM0", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT0", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private Slugs() {
    }

    /** Lowercase ASCII slug: umlauts transliterated (ä → ae), other accents dropped, max 60 chars. */
    public static String slug(String title) {
        String s = Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFC).toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replace("æ", "ae").replace("œ", "oe").replace("ø", "o");
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        s = s.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > MAX_SLUG) {
            s = s.substring(0, MAX_SLUG).replaceAll("-+$", "");
        }
        return s.isEmpty() ? "seite" : s;
    }

    /** Folder name {@code <id>-<slug>} of an exported page. */
    public static String folderName(String id, String title) {
        String safeId = id == null ? "" : id.replaceAll("[^A-Za-z0-9]", "");
        return safeId + "-" + slug(title);
    }

    /**
     * Turns an attachment title into a plain file name: no directories, no traversal, no characters or
     * device names Windows rejects. Returns {@code fallback} if nothing usable remains.
     */
    public static String safeFileName(String name, String fallback) {
        String n = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFC).replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        StringBuilder sb = new StringBuilder(n.length());
        n.codePoints().forEach(cp -> {
            if (cp < 0x20 || cp == 0x7f || WINDOWS_FORBIDDEN.indexOf(cp) >= 0) {
                sb.append('_');
            } else {
                sb.appendCodePoint(cp);
            }
        });
        n = sb.toString().strip().replaceAll("[. ]+$", "");
        if (n.isEmpty() || n.chars().allMatch(c -> c == '.' || c == '_')) {
            return fallback;
        }
        if (n.startsWith(".")) {
            n = "_" + n.substring(1);
        }
        int dot = n.indexOf('.');
        String base = (dot < 0 ? n : n.substring(0, dot)).strip().toUpperCase(Locale.ROOT);
        if (WINDOWS_RESERVED.contains(base)) {
            n = "_" + n;
        }
        if (n.length() > MAX_FILE_NAME) {
            int ext = n.lastIndexOf('.');
            String suffix = ext > 0 && n.length() - ext <= 16 ? n.substring(ext) : "";
            n = n.substring(0, MAX_FILE_NAME - suffix.length()) + suffix;
        }
        return n;
    }
}
