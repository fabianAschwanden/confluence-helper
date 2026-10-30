package ch.confluencehelper.client;

/** A Confluence page as returned by the REST API. {@code storage} is null when not expanded. */
public record Page(String id, String title, String spaceKey, int version, String parentId,
                   String storage, String webUrl) {
}
