package ch.confluencehelper.sync;

import ch.confluencehelper.client.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Reads and writes the local page layout ({@code page.xhtml} + {@code page.json}). */
public final class PageStore {

    public static final String XHTML = "page.xhtml";
    public static final String META = "page.json";
    public static final String ATTACHMENTS = "attachments";

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** Where a page lives locally: its storage file, its metadata file and the folder holding both. */
    public record Location(Path dir, Path xhtml, Path meta) {

        public Path attachmentsDir() {
            return dir.resolve(ATTACHMENTS);
        }
    }

    private PageStore() {
    }

    /**
     * A folder means {@code <folder>/page.xhtml}; a file means that file with {@code page.json} beside it.
     * The storage file must exist.
     */
    public static Location locate(Path target) {
        Path t = target.toAbsolutePath().normalize();
        Path xhtml = Files.isDirectory(t) ? t.resolve(XHTML) : t;
        if (!Files.isRegularFile(xhtml)) {
            throw new SyncException(Files.isDirectory(t)
                    ? "Im Ordner " + target + " liegt keine " + XHTML
                    : "Datei nicht gefunden: " + target);
        }
        Path dir = xhtml.getParent();
        return new Location(dir, xhtml, dir.resolve(META));
    }

    /** Location of a page folder that may not exist yet. */
    public static Location inFolder(Path dir) {
        Path d = dir.toAbsolutePath().normalize();
        return new Location(d, d.resolve(XHTML), d.resolve(META));
    }

    /** Reads UTF-8 storage XHTML; a leading BOM (added by some editors) is dropped. */
    public static String readXhtml(Path file) {
        try {
            String s = Files.readString(file, StandardCharsets.UTF_8);
            return s.startsWith("\ufeff") ? s.substring(1) : s;
        } catch (CharacterCodingException e) {
            throw new SyncException(file + " ist nicht UTF-8-kodiert – im Editor als UTF-8 speichern "
                    + "(«cfh convert» ohne -o: Ausgabe nicht über die Windows-Konsole umleiten, sondern -o verwenden).");
        } catch (IOException e) {
            throw new UncheckedIOException("Kann " + file + " nicht lesen: " + e.getMessage(), e);
        }
    }

    /** Writes the storage exactly as given (UTF-8, no reformatting, no line-ending conversion). */
    public static void writeXhtml(Path file, String storage) {
        write(file, (storage == null ? "" : storage).getBytes(StandardCharsets.UTF_8));
    }

    public static Optional<PageMeta> readMeta(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonNode n = JSON.readTree(file.toFile());
            if (n == null || !n.isObject()) {
                throw new SyncException(file + " ist keine gültige page.json");
            }
            String exportedAt = text(n, "exportedAt");
            Map<String, PageMeta.AttachmentMeta> files = new TreeMap<>();
            JsonNode att = n.path("attachments");
            if (att.isObject()) {
                for (Map.Entry<String, JsonNode> e : att.properties()) {
                    JsonNode a = e.getValue();
                    files.put(e.getKey(), new PageMeta.AttachmentMeta(text(a, "id"), text(a, "title"),
                            a.path("size").asLong(-1), text(a, "sha256")));
                }
            }
            return Optional.of(new PageMeta(text(n, "id"), text(n, "title"), text(n, "spaceKey"),
                    n.path("version").asInt(0), text(n, "parentId"), text(n, "baseUrl"), text(n, "webUrl"),
                    exportedAt == null ? null : Instant.parse(exportedAt), text(n, "contentHash"), files));
        } catch (DateTimeParseException e) {
            throw new SyncException(file + ": ungültiges Datum in «exportedAt»");
        } catch (IOException e) {
            throw new SyncException(file + " ist keine gültige page.json: " + e.getMessage());
        }
    }

    /** Like {@link #readMeta} but fails with a hint when the page is not linked to Confluence yet. */
    public static PageMeta requireMeta(Location loc) {
        PageMeta meta = readMeta(loc.meta()).orElseThrow(() -> new SyncException(
                "Keine " + META + " neben " + loc.xhtml() + " gefunden. "
                        + "Seite zuerst mit «cfh export» holen oder mit «cfh create» anlegen."));
        if (meta.id() == null || meta.id().isBlank()) {
            throw new SyncException(loc.meta() + " enthält keine Seiten-ID");
        }
        return meta;
    }

    public static void writeMeta(Path file, PageMeta meta) {
        ObjectNode n = JSON.createObjectNode();
        n.put("id", meta.id());
        n.put("title", meta.title());
        n.put("spaceKey", meta.spaceKey());
        n.put("version", meta.version());
        n.put("parentId", meta.parentId());
        n.put("baseUrl", meta.baseUrl());
        n.put("webUrl", meta.webUrl());
        n.put("exportedAt", meta.exportedAt() == null ? null : meta.exportedAt().toString());
        n.put("contentHash", meta.contentHash());
        if (!meta.attachments().isEmpty()) {
            ObjectNode att = n.putObject("attachments");
            meta.attachments().forEach((name, a) -> {
                ObjectNode o = att.putObject(name);
                o.put("id", a.id());
                o.put("title", a.title());
                o.put("size", a.size());
                o.put("sha256", a.sha256());
            });
        }
        try {
            write(file, (JSON.writeValueAsString(n) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Metadata for a page just fetched from or written to the server (no attachment records). */
    public static PageMeta metaOf(Page page, URI baseUrl, Instant now) {
        return new PageMeta(page.id(), page.title(), page.spaceKey(), page.version(), page.parentId(),
                baseUrl == null ? null : baseUrl.toString(), page.webUrl(), now,
                page.storage() == null ? null : contentHash(page.storage()), Map.of());
    }

    /** Fingerprint of storage XHTML; only line endings are normalised, as in {@link Differ}. */
    public static String contentHash(String storage) {
        return hex(sha256().digest(Differ.normalize(storage).getBytes(StandardCharsets.UTF_8)));
    }

    /** SHA-256 of a file's bytes. */
    public static String fileHash(Path file) {
        MessageDigest md = sha256();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            for (int r; (r = in.read(buf)) > 0; ) {
                md.update(buf, 0, r);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Kann " + file + " nicht lesen: " + e.getMessage(), e);
        }
        return hex(md.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }

    /**
     * Refuses to work on a page folder that belongs to another Confluence instance than the one
     * configured now (e.g. exported from prod, pushed with the staging profile). Page IDs of a cloned
     * instance are identical, so the ID alone does not protect against writing to the wrong server.
     */
    public static void requireSameServer(PageMeta meta, Path metaFile, URI current, boolean allowOther) {
        if (allowOther || meta.baseUrl() == null || current == null) {
            return;
        }
        if (!normalizeBaseUrl(meta.baseUrl()).equals(normalizeBaseUrl(current.toString()))) {
            throw new SyncException(SyncException.Reason.OTHER_SERVER,
                    Map.of("file", metaFile.toString(), "pageServer", meta.baseUrl(), "current", current.toString()),
                    metaFile + " gehört zu " + meta.baseUrl() + ", verbunden ist aber "
                    + current + ". Mit «--profile» bzw. «--url» den Server wählen, von dem die Seite stammt. "
                    + "Nur wenn die Seite bewusst mit diesem Server verknüpft werden soll: «--other-server».");
        }
    }

    /** Scheme and host lower-case, default port and trailing slashes removed. */
    public static String normalizeBaseUrl(String url) {
        try {
            URI u = new URI(url.strip());
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            int port = u.getPort();
            if (port == 443 && scheme.equals("https") || port == 80 && scheme.equals("http")) {
                port = -1;
            }
            String path = u.getRawPath() == null ? "" : u.getRawPath().replaceAll("/+$", "");
            return scheme + "://" + host + (port < 0 ? "" : ":" + port) + path;
        } catch (URISyntaxException e) {
            return url.strip();
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** Writes via a temp file in the same folder so a crash never leaves a half-written file. */
    static void write(Path file, byte[] bytes) {
        try {
            Path dir = file.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, ".cfh-", ".tmp");
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
}
