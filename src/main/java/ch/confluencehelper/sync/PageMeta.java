package ch.confluencehelper.sync;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/**
 * Contents of {@code page.json} next to an exported or created {@code page.xhtml}.
 *
 * @param contentHash SHA-256 of the storage last exchanged with the server (line endings normalised);
 *                    tells whether {@code page.xhtml} was edited locally since. Null in old files.
 * @param attachments files in {@code attachments/} that came from the server, by local file name
 */
public record PageMeta(String id, String title, String spaceKey, int version, String parentId,
                       String baseUrl, String webUrl, Instant exportedAt, String contentHash,
                       Map<String, AttachmentMeta> attachments) {

    /**
     * An attachment file as last exchanged with the server.
     *
     * @param id     attachment id on the server
     * @param title  attachment name on the server (the local file name may differ, see {@link Slugs#safeFileName})
     * @param size   file size in bytes
     * @param sha256 SHA-256 of the file content
     */
    public record AttachmentMeta(String id, String title, long size, String sha256) {
    }

    public PageMeta {
        attachments = attachments == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(attachments));
    }

    public PageMeta(String id, String title, String spaceKey, int version, String parentId,
                    String baseUrl, String webUrl, Instant exportedAt) {
        this(id, title, spaceKey, version, parentId, baseUrl, webUrl, exportedAt, null, Map.of());
    }

    public PageMeta withAttachments(Map<String, AttachmentMeta> files) {
        return new PageMeta(id, title, spaceKey, version, parentId, baseUrl, webUrl, exportedAt, contentHash, files);
    }
}
