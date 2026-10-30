package ch.confluencehelper.sync;

import ch.confluencehelper.client.Attachment;
import ch.confluencehelper.client.ConfluenceClient;
import ch.confluencehelper.client.Page;
import ch.confluencehelper.client.PageRef;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** The subset of {@link ConfluenceClient} the sync services need; lets tests use an in-memory fake. */
public interface PageApi {

    URI baseUrl();

    String currentUser();

    Page getPage(String id);

    Optional<Page> findPage(String spaceKey, String title);

    Page resolve(PageRef ref);

    List<Page> children(String pageId);

    Page createPage(String spaceKey, String parentId, String title, String storage);

    Page updatePage(String id, String title, int newVersion, String storage, String message);

    List<Attachment> attachments(String pageId);

    void download(Attachment attachment, Path target);

    /** Uploads {@code file} under its own name: a new version if an attachment of that name exists. */
    default Attachment upload(String pageId, Path file, String comment) {
        return upload(pageId, file, file.getFileName().toString(), null, comment);
    }

    /**
     * Uploads {@code file} as attachment {@code title}.
     *
     * @param attachmentId existing attachment to add a new version to; null = look up by title, else create
     */
    Attachment upload(String pageId, Path file, String title, String attachmentId, String comment);

    static PageApi of(ConfluenceClient c) {
        return new PageApi() {
            @Override public URI baseUrl() { return c.baseUrl(); }
            @Override public String currentUser() { return c.currentUser(); }
            @Override public Page getPage(String id) { return c.getPage(id); }
            @Override public Optional<Page> findPage(String spaceKey, String title) { return c.findPage(spaceKey, title); }
            @Override public Page resolve(PageRef ref) { return c.resolve(ref); }
            @Override public List<Page> children(String pageId) { return c.children(pageId); }
            @Override public Page createPage(String spaceKey, String parentId, String title, String storage) {
                return c.createPage(spaceKey, parentId, title, storage);
            }
            @Override public Page updatePage(String id, String title, int newVersion, String storage, String message) {
                return c.updatePage(id, title, newVersion, storage, message);
            }
            @Override public List<Attachment> attachments(String pageId) { return c.attachments(pageId); }
            @Override public void download(Attachment attachment, Path target) { c.download(attachment, target); }
            @Override public Attachment upload(String pageId, Path file, String title, String attachmentId,
                                               String comment) {
                return c.upload(pageId, file, title, attachmentId, comment);
            }
        };
    }
}
