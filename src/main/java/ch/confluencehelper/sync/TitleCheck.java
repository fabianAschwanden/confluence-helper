package ch.confluencehelper.sync;

import java.util.Map;

/** The «title already taken» failure shared by {@link Creator} and {@link Pusher}. */
final class TitleCheck {

    private TitleCheck() {
    }

    static SyncException exists(String spaceKey, String title, String existingId) {
        return exists(spaceKey, title, existingId, "anderen Titel wählen.");
    }

    static SyncException exists(String spaceKey, String title, String existingId, String hint) {
        return new SyncException(SyncException.Reason.TITLE_EXISTS,
                Map.of("spaceKey", spaceKey, "title", title, "id", existingId),
                "Im Space " + spaceKey + " gibt es bereits eine Seite «" + title + "» (ID " + existingId
                        + "). Titel sind pro Space eindeutig; " + hint);
    }
}
