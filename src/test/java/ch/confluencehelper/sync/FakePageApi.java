package ch.confluencehelper.sync;

import ch.confluencehelper.client.Attachment;
import ch.confluencehelper.client.ConfluenceException;
import ch.confluencehelper.client.Page;
import ch.confluencehelper.client.PageRef;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** In-memory Confluence for service and command tests. */
public class FakePageApi implements PageApi {

    public final Map<String, Page> pages = new LinkedHashMap<>();
    public final Map<String, List<Attachment>> attachments = new LinkedHashMap<>();
    public final Map<String, byte[]> attachmentData = new LinkedHashMap<>();
    public final List<String> calls = new ArrayList<>();
    public RuntimeException failure;
    /** Thrown by the next update only, after the update was stored (a lost response). */
    public RuntimeException afterUpdateFailure;
    /** Thrown by the next upload only. */
    public RuntimeException uploadFailure;
    /** Applied to storage on create/update, like Confluence's own normalisation; null = verbatim. */
    public UnaryOperator<String> normalizer;
    /** If false, create/update responses carry no body (the client must fetch the page again). */
    public boolean responseWithBody = true;
    private int nextId = 1000;

    @Override
    public URI baseUrl() {
        return URI.create("https://wiki.example.ch");
    }

    @Override
    public String currentUser() {
        call("currentUser");
        return "Max Muster (mmuster)";
    }

    public Page add(String id, String space, String title, String parentId, int version, String storage) {
        Page p = new Page(id, title, space, version, parentId, storage, web(id));
        pages.put(id, p);
        return p;
    }

    public void addAttachment(String pageId, String title, byte[] data) {
        String id = "att" + (nextId++);
        attachments.computeIfAbsent(pageId, k -> new ArrayList<>())
                .add(new Attachment(id, title, "application/octet-stream", data.length, 1, "/download/" + id));
        attachmentData.put(id, data);
    }

    @Override
    public Page getPage(String id) {
        call("getPage " + id);
        Page p = pages.get(id);
        if (p == null) {
            throw new ConfluenceException(404, "Seite nicht gefunden oder keine Leserechte");
        }
        return p;
    }

    @Override
    public Optional<Page> findPage(String spaceKey, String title) {
        call("findPage " + spaceKey + " " + title);
        return pages.values().stream()
                .filter(p -> p.spaceKey().equals(spaceKey) && p.title().equalsIgnoreCase(title)).findFirst();
    }

    @Override
    public Page resolve(PageRef ref) {
        return ref.hasId() ? getPage(ref.id())
                : findPage(ref.spaceKey(), ref.title()).orElseThrow(() -> new ConfluenceException(404, "nicht gefunden"));
    }

    @Override
    public List<Page> children(String pageId) {
        call("children " + pageId);
        return pages.values().stream().filter(p -> pageId.equals(p.parentId()))
                .map(p -> new Page(p.id(), p.title(), p.spaceKey(), p.version(), p.parentId(), null, p.webUrl()))
                .toList();
    }

    @Override
    public Page createPage(String spaceKey, String parentId, String title, String storage) {
        call("createPage " + spaceKey + " " + parentId + " " + title);
        Page p = add(String.valueOf(nextId++), spaceKey, title, parentId, 1, normalize(storage));
        return responseWithBody ? p : withoutBody(p);
    }

    @Override
    public Page updatePage(String id, String title, int newVersion, String storage, String message) {
        call("updatePage " + id + " v" + newVersion + " " + title + " " + message);
        Page old = getPage(id);
        if (newVersion != old.version() + 1) {
            throw new ConfluenceException(409, "Version conflict");
        }
        Page p = new Page(id, title, old.spaceKey(), newVersion, old.parentId(), normalize(storage), old.webUrl());
        pages.put(id, p);
        if (afterUpdateFailure != null) {
            RuntimeException e = afterUpdateFailure;
            afterUpdateFailure = null;
            throw e;
        }
        return responseWithBody ? p : withoutBody(p);
    }

    @Override
    public List<Attachment> attachments(String pageId) {
        call("attachments " + pageId);
        return List.copyOf(attachments.getOrDefault(pageId, List.of()));
    }

    @Override
    public void download(Attachment attachment, Path target) {
        call("download " + attachment.title());
        try {
            Files.write(target, attachmentData.get(attachment.id()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Attachment upload(String pageId, Path file, String title, String attachmentId, String comment) {
        call("upload " + pageId + " " + file.getFileName() + (title.equals(file.getFileName().toString()) ? "" : " als " + title)
                + (attachmentId == null ? "" : " #" + attachmentId));
        if (uploadFailure != null) {
            RuntimeException e = uploadFailure;
            uploadFailure = null;
            throw e;
        }
        try {
            byte[] data = Files.readAllBytes(file);
            List<Attachment> list = attachments.computeIfAbsent(pageId, k -> new ArrayList<>());
            Attachment existing = list.stream()
                    .filter(a -> attachmentId != null ? a.id().equals(attachmentId) : a.title().equals(title))
                    .findFirst().orElse(null);
            if (existing == null) {
                addAttachment(pageId, title, data);
                return list.getLast();
            }
            Attachment next = new Attachment(existing.id(), existing.title(), existing.mediaType(), data.length,
                    existing.version() + 1, existing.downloadPath());
            list.set(list.indexOf(existing), next);
            attachmentData.put(existing.id(), data);
            return next;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String normalize(String storage) {
        return normalizer == null ? storage : normalizer.apply(storage);
    }

    private static Page withoutBody(Page p) {
        return new Page(p.id(), p.title(), p.spaceKey(), p.version(), p.parentId(), null, p.webUrl());
    }

    public String storage(String id) {
        return pages.get(id).storage();
    }

    public static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private void call(String c) {
        calls.add(c);
        if (failure != null) {
            throw failure;
        }
    }

    private static String web(String id) {
        return "https://wiki.example.ch/pages/viewpage.action?pageId=" + id;
    }
}
