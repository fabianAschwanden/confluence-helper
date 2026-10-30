package ch.confluencehelper.sync;

import java.util.Map;

/** A user-facing failure of a sync operation; {@link #exitCode()} follows docs/SPEC.md. */
public class SyncException extends RuntimeException {

    public static final int ERROR = 1;
    public static final int VALIDATION = 2;
    public static final int VERSION_CONFLICT = 3;
    public static final int AUTH = 4;

    /** Failures the web UI words differently, because the message names CLI commands and options. */
    public enum Reason { NONE, LOCAL_CHANGES, OTHER_SERVER, SPACE_MISMATCH, TITLE_EXISTS }

    private final int exitCode;
    private final Reason reason;
    private final Map<String, String> details;

    public SyncException(int exitCode, String message) {
        super(message);
        this.exitCode = exitCode;
        this.reason = Reason.NONE;
        this.details = Map.of();
    }

    public SyncException(String message) {
        this(ERROR, message);
    }

    /** An {@link #ERROR} with a {@link Reason} and the values another wording needs. */
    public SyncException(Reason reason, Map<String, String> details, String message) {
        super(message);
        this.exitCode = ERROR;
        this.reason = reason;
        this.details = Map.copyOf(details);
    }

    public int exitCode() {
        return exitCode;
    }

    public Reason reason() {
        return reason;
    }

    public String detail(String key) {
        return details.get(key);
    }
}
