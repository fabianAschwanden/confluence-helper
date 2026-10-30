package ch.confluencehelper.sync;

/** The page changed on the server since the local copy was exported. */
public class VersionConflictException extends SyncException {

    private final int localVersion;
    private final int serverVersion;

    public VersionConflictException(String pageId, int localVersion, int serverVersion) {
        super(VERSION_CONFLICT, ("Versionskonflikt bei Seite %s: lokal Version %d, auf dem Server Version %d. "
                + "Unterschiede ansehen mit «cfh diff», überschreiben mit «--force». Zum Zusammenführen die "
                + "Server-Version in einen anderen Ordner exportieren (cfh export %s -o ANDERER_ORDNER).")
                .formatted(pageId, localVersion, serverVersion, pageId));
        this.localVersion = localVersion;
        this.serverVersion = serverVersion;
    }

    public int localVersion() {
        return localVersion;
    }

    public int serverVersion() {
        return serverVersion;
    }
}
