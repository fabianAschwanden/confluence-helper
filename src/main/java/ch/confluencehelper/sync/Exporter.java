package ch.confluencehelper.sync;

import ch.confluencehelper.client.Attachment;
import ch.confluencehelper.client.Page;
import ch.confluencehelper.client.PageRef;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Exports a page (and optionally its attachments and child pages) into {@code <out>/<id>-<slug>/}. */
public class Exporter {

    /**
     * @param depth levels of child pages below the start page (only with {@code children})
     * @param force overwrite local copies that have unpushed changes
     */
    public record Options(boolean attachments, boolean children, int depth, boolean force) {

        public static Options pageOnly() {
            return new Options(false, false, 0, false);
        }
    }

    /** One exported page; child pages are nested in the folder of their parent. */
    public record Result(Page page, Path dir, int attachments, int level) {
    }

    private final PageApi api;
    private final Clock clock;
    private final Consumer<String> progress;

    public Exporter(PageApi api, Clock clock, Consumer<String> progress) {
        this.api = api;
        this.clock = clock;
        this.progress = progress;
    }

    public Exporter(PageApi api) {
        this(api, Clock.systemUTC(), s -> { });
    }

    public List<Result> export(PageRef ref, Path outDir, Options options) {
        List<Result> results = new ArrayList<>();
        Page root = api.resolve(ref);
        if (root.storage() == null) {
            root = api.getPage(root.id());
        }
        exportTree(root, outDir, 0, options, new HashSet<>(), results);
        return results;
    }

    /** Exports one page (no child pages) into exactly {@code dir}, e.g. to refresh an existing page folder. */
    public Result exportInto(String pageId, Path dir, Options options) {
        Page page = api.getPage(pageId);
        return new Result(page, dir, exportPage(page, dir, options), 0);
    }

    private void exportTree(Page page, Path parentDir, int level, Options options, Set<String> seen,
                            List<Result> results) {
        if (!seen.add(page.id())) {
            return;
        }
        Path dir = parentDir.resolve(Slugs.folderName(page.id(), page.title()));
        int attachments = exportPage(page, dir, options);
        results.add(new Result(page, dir, attachments, level));
        progress.accept("  ".repeat(level) + page.title() + " → " + dir);
        if (options.children() && level < options.depth()) {
            for (Page child : api.children(page.id())) {
                Page full = child.storage() == null ? api.getPage(child.id()) : child;
                exportTree(full, dir, level + 1, options, seen, results);
            }
        }
    }

    private int exportPage(Page page, Path dir, Options options) {
        PageStore.Location loc = PageStore.inFolder(dir);
        refuseSymlink(loc.dir());
        Optional<PageMeta> old = PageStore.readMeta(loc.meta());
        boolean localEdits = hasLocalEdits(page, loc, old);
        if (localEdits && !options.force()) {
            throw new SyncException(SyncException.Reason.LOCAL_CHANGES,
                    Map.of("dir", loc.dir().toString(), "serverVersion", String.valueOf(page.version())),
                    loc.xhtml() + " enthält lokale Änderungen, die noch nicht hochgeladen sind "
                    + "(Server: Version " + page.version() + "). Zuerst «cfh push» ausführen (bei Versionskonflikt "
                    + "Unterschiede mit «cfh diff» ansehen), die Server-Version in einen anderen Ordner exportieren "
                    + "(-o) oder mit «--force» überschreiben (die lokale Fassung wird dann als "
                    + PageStore.XHTML + BACKUP_SUFFIX + " gesichert).");
        }
        Map<String, PageMeta.AttachmentMeta> files = old.map(PageMeta::attachments).orElse(Map.of());
        int count = 0;
        if (options.attachments()) {
            AttachmentSync sync = new AttachmentSync(page, loc, files, options.force());
            try {
                sync.stage();
                if (localEdits) {
                    backup(loc.xhtml());
                }
                PageStore.writeXhtml(loc.xhtml(), page.storage());
                files = sync.commit();
                count = sync.count();
            } finally {
                sync.cleanUp();
            }
        } else {
            if (localEdits) {
                backup(loc.xhtml());
            }
            PageStore.writeXhtml(loc.xhtml(), page.storage());
        }
        PageStore.writeMeta(loc.meta(), PageStore.metaOf(page, api.baseUrl(), clock.instant()).withAttachments(files));
        return count;
    }

    static final String BACKUP_SUFFIX = ".bak";

    /**
     * Whether {@code page.xhtml} holds edits that would be lost by overwriting it with {@code page}:
     * it differs from the server content and from what was last exported/pushed (fingerprint in page.json).
     * Without page.json or fingerprint there is no way to tell, so any difference counts as an edit.
     */
    private static boolean hasLocalEdits(Page page, PageStore.Location loc, Optional<PageMeta> meta) {
        if (!Files.isRegularFile(loc.xhtml(), LinkOption.NOFOLLOW_LINKS)) {
            return Files.exists(loc.xhtml(), LinkOption.NOFOLLOW_LINKS);
        }
        String local = PageStore.readXhtml(loc.xhtml());
        if (Differ.sameContent(local, page.storage())) {
            return false;
        }
        String base = meta.map(PageMeta::contentHash).orElse(null);
        return base == null || !base.equals(PageStore.contentHash(local));
    }

    private static void backup(Path file) {
        try {
            Files.copy(file, file.resolveSibling(file.getFileName() + BACKUP_SUFFIX),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Kann keine Sicherung von " + file + " anlegen: " + e.getMessage(), e);
        }
    }

    /** Export writes server-chosen file names: never through a symlinked page or attachments folder. */
    private static void refuseSymlink(Path dir) {
        if (Files.isSymbolicLink(dir)) {
            throw new SyncException(dir + " ist ein symbolischer Link; Export dorthin wird verweigert.");
        }
    }

    /**
     * Brings {@code attachments/} in line with the server without losing local work: files are first
     * downloaded into a staging folder; files edited locally since the last export block the export
     * (unless forced); files deleted on the server are removed locally if they were not edited.
     */
    private final class AttachmentSync {

        private final Page page;
        private final PageStore.Location loc;
        private final Map<String, PageMeta.AttachmentMeta> known;
        private final boolean force;
        private final Path base;
        private Path staging;
        private final Map<String, Attachment> plan = new LinkedHashMap<>();
        private final Map<String, String> stagedHash = new HashMap<>();
        private final List<String> obsolete = new ArrayList<>();

        AttachmentSync(Page page, PageStore.Location loc, Map<String, PageMeta.AttachmentMeta> known, boolean force) {
            this.page = page;
            this.loc = loc;
            this.known = known;
            this.force = force;
            this.base = loc.attachmentsDir().toAbsolutePath().normalize();
        }

        int count() {
            return plan.size();
        }

        void stage() {
            refuseSymlink(base);
            List<Attachment> list = api.attachments(page.id());
            Set<String> used = new HashSet<>();
            Map<String, String> nameById = new HashMap<>();
            known.forEach((file, a) -> nameById.put(a.id(), file));
            // keep the file names of earlier exports stable
            for (Attachment a : list) {
                String earlier = nameById.get(a.id());
                if (earlier != null && used.add(earlier.toLowerCase(Locale.ROOT))) {
                    plan.put(earlier, a);
                }
            }
            for (Attachment a : list) {
                if (plan.containsValue(a)) {
                    continue;
                }
                String name = uniqueName(Slugs.safeFileName(a.title(), "anhang-" + a.id()), a.id(), used);
                plan.put(name, a);
            }
            List<String> conflicts = new ArrayList<>();
            if (!plan.isEmpty()) {
                try {
                    Files.createDirectories(loc.dir());
                    staging = Files.createTempDirectory(loc.dir(), ".cfh-staging-");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            for (Map.Entry<String, Attachment> e : plan.entrySet()) {
                Path target = base.resolve(e.getKey()).normalize();
                if (!base.equals(target.getParent())) {
                    throw new SyncException("Ungültiger Anhangname: " + e.getValue().title());
                }
                Path staged = staging.resolve(e.getKey());
                api.download(e.getValue(), staged);
                String hash = PageStore.fileHash(staged);
                stagedHash.put(e.getKey(), hash);
                if (isLocallyChanged(e.getKey(), target) && (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                        || !hash.equals(PageStore.fileHash(target)))) {
                    conflicts.add(e.getKey());
                }
            }
            for (String file : known.keySet()) {
                if (plan.containsKey(file)) {
                    continue;
                }
                Path target = base.resolve(file).normalize();
                if (!base.equals(target.getParent()) || !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (isLocallyChanged(file, target)) {
                    conflicts.add(file + " (auf dem Server gelöscht)");
                    if (force) {
                        obsolete.add(file);
                    }
                } else {
                    obsolete.add(file);
                }
            }
            if (!conflicts.isEmpty() && !force) {
                throw new SyncException("Lokal geänderte Anhänge würden überschrieben: " + String.join(", ", conflicts)
                        + " (in " + base + "). Zuerst «cfh push --attachments» ausführen, die Dateien sichern "
                        + "oder mit «--force» überschreiben.");
            }
        }

        /** A local file exists that is not (or no longer) the version that was exported. */
        private boolean isLocallyChanged(String file, Path target) {
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
            PageMeta.AttachmentMeta k = known.get(file);
            return k == null || k.sha256() == null || !k.sha256().equals(PageStore.fileHash(target));
        }

        Map<String, PageMeta.AttachmentMeta> commit() {
            Map<String, PageMeta.AttachmentMeta> result = new TreeMap<>();
            try {
                if (!plan.isEmpty()) {
                    Files.createDirectories(base);
                }
                for (Map.Entry<String, Attachment> e : plan.entrySet()) {
                    Path target = base.resolve(e.getKey());
                    Files.move(staging.resolve(e.getKey()), target, StandardCopyOption.REPLACE_EXISTING);
                    Attachment a = e.getValue();
                    result.put(e.getKey(), new PageMeta.AttachmentMeta(a.id(), a.title(), Files.size(target),
                            stagedHash.get(e.getKey())));
                }
                for (String file : obsolete) {
                    Files.deleteIfExists(base.resolve(file));
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Anhänge konnten nicht gespeichert werden: " + e.getMessage(), e);
            }
            return result;
        }

        void cleanUp() {
            if (staging == null) {
                return;
            }
            try (Stream<Path> files = Files.walk(staging)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            } catch (IOException e) {
                // leftover hidden staging folder is harmless
            }
        }
    }

    /** Avoids two attachments landing in the same file (also on case-insensitive file systems). */
    private static String uniqueName(String name, String id, Set<String> used) {
        String candidate = name;
        if (used.contains(candidate.toLowerCase(Locale.ROOT))) {
            candidate = Slugs.safeFileName(id + "-" + name, "anhang-" + id);
        }
        used.add(candidate.toLowerCase(Locale.ROOT));
        return candidate;
    }
}
