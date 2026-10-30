package ch.confluencehelper.sync;

import ch.confluencehelper.storage.Problem;

import java.util.List;

/** Storage XHTML contains errors; nothing was sent to the server. */
public class ValidationFailedException extends SyncException {

    private final String file;
    private final List<Problem> problems;

    public ValidationFailedException(String file, List<Problem> problems) {
        super(VALIDATION, file + " ist kein gültiges Storage-XHTML (" + count(problems) + " Fehler)");
        this.file = file;
        this.problems = List.copyOf(problems);
    }

    public String file() {
        return file;
    }

    public List<Problem> problems() {
        return problems;
    }

    private static long count(List<Problem> problems) {
        return problems.stream().filter(Problem::isError).count();
    }
}
