package ch.confluencehelper.client;

/** Attachment metadata; {@code downloadPath} is the server-relative {@code _links.download}. */
public record Attachment(String id, String title, String mediaType, long size, int version,
                         String downloadPath) {
}
