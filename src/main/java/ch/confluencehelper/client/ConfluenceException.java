package ch.confluencehelper.client;

/** HTTP or API failure. {@code status} is 0 for transport errors. Never carries credentials. */
public class ConfluenceException extends RuntimeException {

    private final int status;

    public ConfluenceException(int status, String message) {
        super(message);
        this.status = status;
    }

    public ConfluenceException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
    }

    public int status() {
        return status;
    }

    public boolean isAuthProblem() {
        return status == 401 || status == 403;
    }
}
