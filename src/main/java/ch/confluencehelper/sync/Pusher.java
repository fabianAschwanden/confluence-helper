package ch.confluencehelper.sync;

import ch.confluencehelper.client.Attachment;
import ch.confluencehelper.client.ConfluenceException;
import ch.confluencehelper.client.Page;
import ch.confluencehelper.storage.Problem;
import ch.confluencehelper.storage.StorageXml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;

/** Uploads a locally edited {@code page.xhtml} as a new version of its Confluence page. */
public class Pusher {

    /**
     * @param title          new title, or null to use the title from page.json
     * @param allowOtherServer push although page.json was written for another Confluence base URL
     */
    public record Options(String title, String message, boolean dryRun, boolean force, boolean attachments,
                          boolean allowOtherServer) {

        public Options(String title, String message, boolean dryRun, boolean force, boolean attachments) {
            this(title, message, dryRun, force, attachments, false);
        }
    }

    public enum Status { UPDATED, NO_CHANGE, DRY_RUN }

    /**
     * @param uploads attachment files uploaded (or, in a dry run, that would be uploaded)
     * @param warnings non-fatal validation findings
     */
    public record Result(Status status, String pageId, String oldTitle, String newTitle, int serverVersion,
                         int newVersion, Differ.Stats stats, List<Path> uploads, List<Problem> warnings,
                         String webUrl) {

        public boolean titleChanged() {
            return !Objects.equals(oldTitle, newTitle);
        }
    }

    /** A local attachment file to upload; {@code attachmentId} null = new attachment. */
    private record Upload(Path file, String name, String title, String attachmentId, String sha256) {
    }

    private final PageApi api;
    private final Function<String, List<Problem>> validator;
    private final Clock clock;

    public Pusher(PageApi api, Function<String, List<Problem>> validator, Clock clock) {
        this.api = api;
        this.validator = validator;
        this.clock = clock;
    }

    public Pusher(PageApi api) {
        this(api, StorageXml::validate, Clock.systemUTC());
    }

    public Result push(Path target, Options options) {
        PageStore.Location loc = PageStore.locate(target);
        PageMeta meta = PageStore.requireMeta(loc);
        String storage = PageStore.readXhtml(loc.xhtml());

        List<Problem> problems = validator.apply(storage);
        if (problems.stream().anyMatch(Problem::isError)) {
            throw new ValidationFailedException(loc.xhtml().toString(), problems);
        }
        List<Problem> warnings = new ArrayList<>(problems);
        PageStore.requireSameServer(meta, loc.meta(), api.baseUrl(), options.allowOtherServer());

        Page server = api.getPage(meta.id());
        if (!options.force() && meta.spaceKey() != null && server.spaceKey() != null
                && !meta.spaceKey().equalsIgnoreCase(server.spaceKey())) {
            throw new SyncException(SyncException.Reason.SPACE_MISMATCH, Map.of("file", loc.meta().toString(),
                    "localSpace", meta.spaceKey(), "serverSpace", server.spaceKey()),
                    loc.meta() + " gehört zu Space " + meta.spaceKey() + ", Seite " + meta.id()
                    + " liegt auf dem Server aber in Space " + server.spaceKey()
                    + ". Falscher Server oder verschobene Seite? Mit «cfh diff» prüfen, überschreiben mit «--force».");
        }
        String title = firstNonBlank(options.title(), meta.title(), server.title());
        String space = firstNonBlank(server.spaceKey(), meta.spaceKey());
        if (options.title() != null && !options.title().isBlank() && !title.equalsIgnoreCase(server.title())
                && space != null) {
            api.findPage(space, title).filter(p -> !p.id().equals(meta.id())).ifPresent(p -> {
                throw TitleCheck.exists(space, title, p.id());
            });
        }
        boolean contentChanged = !Differ.sameContent(storage, server.storage());
        boolean changed = contentChanged || !Objects.equals(title, server.title());
        if (!options.force() && server.version() != meta.version() && changed) {
            throw new VersionConflictException(meta.id(), meta.version(), server.version());
        }
        // a newer server version with exactly the local content (e.g. our own push whose page.json update
        // failed) is simply adopted below
        Differ.Stats stats = Differ.stats(server.storage(), storage);
        List<Attachment> remote = options.attachments()
                && Files.isDirectory(loc.attachmentsDir(), LinkOption.NOFOLLOW_LINKS) ? api.attachments(meta.id()) : null;
        List<Upload> uploads = remote != null ? pendingUploads(loc, meta, remote) : List.of();
        List<Path> uploadFiles = uploads.stream().map(Upload::file).toList();
        warnings.addAll(missingAttachments(loc, meta, storage, uploads, remote));

        if (options.dryRun()) {
            return new Result(Status.DRY_RUN, meta.id(), server.title(), title, server.version(),
                    changed ? server.version() + 1 : server.version(), stats, uploadFiles, warnings, server.webUrl());
        }

        Page result = server;
        Status status = Status.NO_CHANGE;
        if (changed) {
            int newVersion = server.version() + 1;
            Page updated = update(meta.id(), title, newVersion, storage, options.message());
            result = merge(updated, server, title, newVersion);
            if (result.storage() == null) {
                result = refetch(result, storage);
            }
            status = Status.UPDATED;
        }
        Map<String, PageMeta.AttachmentMeta> files = new TreeMap<>(meta.attachments());
        PageMeta newMeta = PageStore.metaOf(result, api.baseUrl(), clock.instant());
        if (changed || server.version() != meta.version() || !Objects.equals(meta.title(), result.title())
                || !Objects.equals(meta.contentHash(), newMeta.contentHash())
                || !Objects.equals(meta.baseUrl(), newMeta.baseUrl())) {
            saveAfterServerWrite(loc, result, newMeta.withAttachments(files), changed);
        }
        // the folder now mirrors the server: later attachment uploads only touch page.json
        for (Upload u : uploads) {
            Attachment uploaded;
            try {
                uploaded = api.upload(meta.id(), u.file(), u.title(), u.attachmentId(), options.message());
            } catch (RuntimeException e) {
                throw afterUpdate(e, changed, result.version(), "Hochladen von «" + u.name() + "»");
            }
            files.put(u.name(), new PageMeta.AttachmentMeta(
                    uploaded != null && uploaded.id() != null && !uploaded.id().isBlank() ? uploaded.id() : u.attachmentId(),
                    u.title(), sizeOf(u.file()), u.sha256()));
            saveAfterServerWrite(loc, null, newMeta.withAttachments(files), changed);
        }
        return new Result(status, meta.id(), server.title(), result.title(), server.version(), result.version(),
                stats, uploadFiles, warnings, result.webUrl());
    }

    /**
     * PUT with a recovery for a lost response: if the request timed out or the connection broke, the
     * server may still have saved it. Then the page is fetched again and accepted if it is our version.
     */
    private Page update(String id, String title, int newVersion, String storage, String message) {
        try {
            return api.updatePage(id, title, newVersion, storage, message);
        } catch (ConfluenceException e) {
            if (e.status() != 0) {
                throw e;
            }
            Page now;
            try {
                now = api.getPage(id);
            } catch (RuntimeException again) {
                throw e;
            }
            if (now.version() == newVersion && Objects.equals(now.title(), title)) {
                return now;
            }
            throw e;
        }
    }

    /** The PUT response carries no storage: fetch it, so page.xhtml gets exactly what the server saved. */
    private Page refetch(Page result, String sent) {
        try {
            Page fresh = api.getPage(result.id());
            if (fresh.version() == result.version() && fresh.storage() != null) {
                return merge(fresh, result, result.title(), result.version());
            }
        } catch (RuntimeException e) {
            // keep what we sent; the next push compares against the server anyway
        }
        return new Page(result.id(), result.title(), result.spaceKey(), result.version(), result.parentId(), sent,
                result.webUrl());
    }

    /**
     * Writes the storage the server saved (Confluence normalises markup on save) and page.json right
     * after the server write, before anything else can fail.
     */
    private static void saveAfterServerWrite(PageStore.Location loc, Page saved, PageMeta meta, boolean pageUpdated) {
        try {
            if (saved != null && saved.storage() != null && pageUpdated) {
                PageStore.writeXhtml(loc.xhtml(), saved.storage());
            }
            PageStore.writeMeta(loc.meta(), meta);
        } catch (RuntimeException e) {
            throw new SyncException((pageUpdated ? "Seite wurde als Version " + meta.version() + " gespeichert, aber "
                    : "") + loc.meta() + " konnte nicht aktualisiert werden: " + e.getMessage()
                    + ". In page.json «version» auf " + meta.version() + " setzen, sonst meldet der nächste "
                    + "Push einen Versionskonflikt.");
        }
    }

    private static SyncException afterUpdate(RuntimeException e, boolean pageUpdated, int version, String step) {
        int code = e instanceof ConfluenceException c && c.isAuthProblem() ? SyncException.AUTH
                : e instanceof SyncException s ? s.exitCode() : SyncException.ERROR;
        String status = e instanceof ConfluenceException c && c.status() > 0 ? " (HTTP " + c.status() + ")" : "";
        return new SyncException(code, (pageUpdated ? "Seite wurde als Version " + version + " gespeichert, aber "
                : "") + step + " ist fehlgeschlagen" + status + ": " + e.getMessage());
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Files in attachments/ to upload. Exported files are matched to their attachment through page.json
     * (the local name may differ from the attachment name) and uploaded only if their content changed
     * since export. Other files are matched by name and uploaded if missing on the server or different
     * in content. Symbolic links are never followed.
     */
    private List<Upload> pendingUploads(PageStore.Location loc, PageMeta meta, List<Attachment> remote) {
        Path dir = loc.attachmentsDir();
        Map<String, Attachment> byId = new HashMap<>();
        Map<String, Attachment> byName = new HashMap<>();
        for (Attachment a : remote) {
            byId.put(a.id(), a);
            byName.putIfAbsent(nfc(a.title()), a);
        }
        for (Attachment a : remote) {
            byName.putIfAbsent(Slugs.safeFileName(a.title(), "anhang-" + a.id()), a);
        }
        List<Upload> result = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> list = Files.list(dir)) {
            files = list.sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Path f : files) {
            String name = nfc(f.getFileName().toString());
            if (name.startsWith(".") || name.endsWith(Exporter.BACKUP_SUFFIX)
                    || !Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            String hash = PageStore.fileHash(f);
            PageMeta.AttachmentMeta known = meta.attachments().get(name);
            if (known != null) {
                if (hash.equals(known.sha256())) {
                    continue; // unchanged since export (also when deleted on the server meanwhile)
                }
                Attachment r = byId.get(known.id());
                result.add(new Upload(f, name, r != null ? r.title() : known.title(), r != null ? r.id() : null, hash));
                continue;
            }
            Attachment r = byName.get(name);
            if (r == null) {
                result.add(new Upload(f, name, name, null, hash));
            } else if (r.size() != sizeOf(f) || !hash.equals(remoteHash(loc, r))) {
                result.add(new Upload(f, name, r.title(), r.id(), hash));
            }
        }
        return result;
    }

    /**
     * Warnings for attachments the page references that will not exist on the server after this push:
     * neither there already nor uploaded now. Confluence would show them as broken images or links.
     * Advisory only: if the attachment list cannot be read, no warning.
     */
    private List<Problem> missingAttachments(PageStore.Location loc, PageMeta meta, String storage,
                                             List<Upload> uploads, List<Attachment> remote) {
        Map<String, StorageXml.AttachmentRef> missing = new LinkedHashMap<>();
        for (StorageXml.AttachmentRef r : StorageXml.attachmentRefs(storage)) {
            missing.putIfAbsent(nfc(r.filename()), r);
        }
        uploads.forEach(u -> missing.remove(nfc(u.title())));
        if (missing.isEmpty()) {
            return List.of();
        }
        try {
            (remote != null ? remote : api.attachments(meta.id())).forEach(a -> missing.remove(nfc(a.title())));
        } catch (RuntimeException e) {
            return List.of();
        }
        List<Problem> result = new ArrayList<>();
        for (StorageXml.AttachmentRef r : missing.values()) {
            boolean local = remote == null && Files.isRegularFile(
                    loc.attachmentsDir().resolve(Slugs.safeFileName(r.filename(), "anhang")), LinkOption.NOFOLLOW_LINKS);
            result.add(new Problem(Problem.Severity.WARNING, r.line(), r.column(), "Anhang «" + r.filename()
                    + "» fehlt auf dem Server" + (local
                    ? "; die Datei in attachments/ wird nur mit «Neue oder geänderte Anhänge hochladen» "
                    + "(--attachments) hochgeladen"
                    : " und wird mit diesem Push nicht hochgeladen; Confluence zeigt ihn als defekt an")));
        }
        return result;
    }

    /** Downloads an attachment into a temp file to compare contents (read-only on the server). */
    private String remoteHash(PageStore.Location loc, Attachment a) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile(loc.dir(), ".cfh-compare-", ".tmp");
            api.download(a, tmp);
            return PageStore.fileHash(tmp);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException e) {
                    // hidden temp file, harmless
                }
            }
        }
    }

    private static String nfc(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFC);
    }

    /** The PUT response may omit fields; fall back to what we know. Storage is kept: it is what was saved. */
    private static Page merge(Page updated, Page server, String title, int newVersion) {
        if (updated == null) {
            updated = new Page(server.id(), title, null, newVersion, null, null, null);
        }
        return new Page(server.id(),
                firstNonBlank(updated.title(), title),
                firstNonBlank(updated.spaceKey(), server.spaceKey()),
                updated.version() > 0 ? updated.version() : newVersion,
                firstNonBlank(updated.parentId(), server.parentId()),
                updated.storage(),
                firstNonBlank(updated.webUrl(), server.webUrl()));
    }

    static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
