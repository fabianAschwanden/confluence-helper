package ch.confluencehelper.sync;

import ch.confluencehelper.client.Page;
import ch.confluencehelper.client.PageRefs;
import ch.confluencehelper.storage.Problem;
import ch.confluencehelper.storage.StorageXml;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Creates new Confluence pages from local storage XHTML and links them via {@code page.json}. */
public class Creator {

    /** @param warnings non-fatal validation findings */
    public record Result(Page page, PageStore.Location location, List<Problem> warnings) {
    }

    private final PageApi api;
    private final Function<String, List<Problem>> validator;
    private final Clock clock;

    public Creator(PageApi api, Function<String, List<Problem>> validator, Clock clock) {
        this.api = api;
        this.validator = validator;
        this.clock = clock;
    }

    public Creator(PageApi api) {
        this(api, StorageXml::validate, Clock.systemUTC());
    }

    /** Creates a page from an existing file or folder; {@code page.json} is written next to the file. */
    public Result create(Path target, String spaceKey, String title, String parent) {
        PageStore.Location loc = PageStore.locate(target);
        Optional<PageMeta> existing = PageStore.readMeta(loc.meta());
        if (existing.isPresent() && existing.get().id() != null) {
            throw new SyncException(loc.meta() + " verweist bereits auf Seite " + existing.get().id()
                    + ". Änderungen mit «cfh push» hochladen oder die Datei in einen eigenen Ordner legen.");
        }
        String storage = PageStore.readXhtml(loc.xhtml());
        Prepared p = prepare(storage, loc.xhtml().toString(), spaceKey, title, parent);
        Page page = withStorage(api.createPage(spaceKey, p.parentId(), title, storage), storage);
        save(loc, page, spaceKey, title, p.parentId());
        return new Result(page, loc, p.warnings());
    }

    /** Creates a page from generated storage and saves it as {@code <outDir>/<id>-<slug>/}. */
    public Result createInto(String storage, Path outDir, String spaceKey, String title, String parent) {
        Prepared p = prepare(storage, title, spaceKey, title, parent);
        Page page = withStorage(api.createPage(spaceKey, p.parentId(), title, storage), storage);
        PageStore.Location loc = PageStore.inFolder(outDir.resolve(Slugs.folderName(page.id(), title)));
        save(loc, page, spaceKey, title, p.parentId());
        return new Result(page, loc, p.warnings());
    }

    /**
     * Confluence normalises the markup on save; page.xhtml gets exactly the saved storage so that the
     * next push or export sees no phantom change. If the response has no body, it is fetched again.
     */
    private Page withStorage(Page created, String sent) {
        if (created.storage() != null) {
            return created;
        }
        try {
            Page fresh = api.getPage(created.id());
            if (fresh.storage() != null) {
                return new Page(created.id(), Pusher.firstNonBlank(created.title(), fresh.title()),
                        Pusher.firstNonBlank(created.spaceKey(), fresh.spaceKey()),
                        created.version() > 0 ? created.version() : fresh.version(),
                        Pusher.firstNonBlank(created.parentId(), fresh.parentId()), fresh.storage(),
                        Pusher.firstNonBlank(created.webUrl(), fresh.webUrl()));
            }
        } catch (RuntimeException e) {
            // keep the sent storage; the page exists, linking it locally matters more
        }
        return new Page(created.id(), created.title(), created.spaceKey(), created.version(), created.parentId(),
                sent, created.webUrl());
    }

    private void save(PageStore.Location loc, Page page, String spaceKey, String title, String parentId) {
        try {
            PageStore.writeXhtml(loc.xhtml(), page.storage());
            PageStore.writeMeta(loc.meta(), meta(page, spaceKey, title, parentId));
        } catch (RuntimeException e) {
            throw new SyncException("Seite «" + title + "» wurde angelegt (ID " + page.id() + "), aber die lokale "
                    + "Verknüpfung konnte nicht geschrieben werden: " + e.getMessage()
                    + ". Mit «cfh export " + page.id() + "» holen.");
        }
    }

    private record Prepared(String parentId, List<Problem> warnings) {
    }

    /** All checks that must pass before anything is written to the server. */
    private Prepared prepare(String storage, String source, String spaceKey, String title, String parent) {
        if (spaceKey == null || spaceKey.isBlank()) {
            throw new SyncException("Space-Key fehlt (--space)");
        }
        if (title == null || title.isBlank()) {
            throw new SyncException("Titel fehlt (--title)");
        }
        List<Problem> problems = validator.apply(storage);
        if (problems.stream().anyMatch(Problem::isError)) {
            throw new ValidationFailedException(source, problems);
        }
        api.findPage(spaceKey, title).ifPresent(p -> {
            throw TitleCheck.exists(spaceKey, title, p.id(), "anderen Titel wählen oder «cfh push» verwenden.");
        });
        String parentId = null;
        if (parent != null && !parent.isBlank()) {
            Page parentPage = api.resolve(PageRefs.parse(parent, api.baseUrl()));
            if (parentPage.spaceKey() != null && !parentPage.spaceKey().equalsIgnoreCase(spaceKey)) {
                throw new SyncException("Übergeordnete Seite «" + parentPage.title() + "» liegt im Space "
                        + parentPage.spaceKey() + ", nicht in " + spaceKey);
            }
            parentId = parentPage.id();
        }
        return new Prepared(parentId, List.copyOf(problems));
    }

    private PageMeta meta(Page created, String spaceKey, String title, String parentId) {
        Page p = new Page(created.id(),
                Pusher.firstNonBlank(created.title(), title),
                Pusher.firstNonBlank(created.spaceKey(), spaceKey),
                created.version() > 0 ? created.version() : 1,
                Pusher.firstNonBlank(created.parentId(), parentId),
                created.storage(), created.webUrl());
        return PageStore.metaOf(p, api.baseUrl(), clock.instant());
    }
}
