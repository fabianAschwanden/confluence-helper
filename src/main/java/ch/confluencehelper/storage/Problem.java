package ch.confluencehelper.storage;

/** A validation finding in a storage-format document (line/column 1-based, 0 if unknown). */
public record Problem(Severity severity, int line, int column, String message) {

    public enum Severity { ERROR, WARNING }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    @Override
    public String toString() {
        return "%s %d:%d %s".formatted(severity, line, column, message);
    }
}
