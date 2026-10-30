package ch.confluencehelper.client;

/** Reference to a page: either by id, or by space key and title (display URLs). */
public record PageRef(String id, String spaceKey, String title) {

    public static PageRef ofId(String id) {
        return new PageRef(id, null, null);
    }

    public static PageRef ofTitle(String spaceKey, String title) {
        return new PageRef(null, spaceKey, title);
    }

    public boolean hasId() {
        return id != null;
    }
}
