package ch.confluencehelper.cli;

import ch.confluencehelper.client.ConfluenceException;
import ch.confluencehelper.config.ConfigException;
import ch.confluencehelper.convert.LibraryLogging;
import ch.confluencehelper.sync.SyncException;
import ch.confluencehelper.sync.ValidationFailedException;
import picocli.CommandLine;

import java.io.Console;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;

public final class Main {

    public static final int OK = 0;
    public static final int ERROR = 1;
    public static final int VALIDATION = 2;
    public static final int VERSION_CONFLICT = 3;
    public static final int AUTH = 4;

    private Main() {
    }

    public static void main(String[] args) {
        LibraryLogging.silence();
        CommandLine cmd = commandLine(new CfhCommand());
        cmd.setOut(new PrintWriter(new OutputStreamWriter(System.out, stdoutCharset(isTerminal(), System.out.charset())),
                true));
        cmd.setErr(writer(System.err));
        System.exit(cmd.execute(args));
    }

    /** Fully configured command line; tests swap the API factory and the output writers. */
    public static CommandLine commandLine(CfhCommand root) {
        CommandLine cmd = new CommandLine(root);
        cmd.setParameterExceptionHandler((ex, args) -> {
            CommandLine failed = ex.getCommandLine();
            failed.getErr().println("Fehler: " + ex.getMessage());
            if (!CommandLine.UnmatchedArgumentException.printSuggestions(ex, failed.getErr())) {
                failed.getErr().println("Hilfe: cfh " + (failed == cmd ? "" : failed.getCommandName() + " ")
                        + "--help");
            }
            return ERROR;
        });
        cmd.setExecutionExceptionHandler((ex, commandLine, parseResult) ->
                handle(ex, commandLine.getErr(), root.verbose()));
        return cmd;
    }

    static int handle(Exception ex, PrintWriter err, boolean verbose) {
        int code;
        switch (ex) {
            case ValidationFailedException v -> {
                BaseCommand.printProblems(err, v.file(), v.problems());
                err.println("Fehler: " + v.getMessage());
                code = v.exitCode();
            }
            case SyncException s -> {
                err.println("Fehler: " + s.getMessage());
                code = s.exitCode();
            }
            case ConfluenceException c -> code = confluence(c, err);
            case ConfigException c -> {
                err.println("Konfigurationsfehler: " + c.getMessage());
                err.println("Hinweis: CONFLUENCE_URL und CONFLUENCE_TOKEN (Personal Access Token) setzen oder in "
                        + "~/.config/confluence-helper/config.properties «url=…» und «token=…» eintragen "
                        + "(Datei nur für dich lesbar: chmod 600). Alternativ --url und --profile verwenden.");
                code = ERROR;
            }
            case UncheckedIOException u when u.getCause() instanceof NoSuchFileException n -> {
                err.println("Fehler: Datei nicht gefunden: " + n.getFile());
                code = ERROR;
            }
            case UncheckedIOException u -> {
                err.println("Fehler: " + u.getMessage());
                code = ERROR;
            }
            case IOException io -> {
                err.println("Fehler: " + io.getMessage());
                code = ERROR;
            }
            case IllegalArgumentException i -> {
                err.println("Fehler: " + i.getMessage());
                code = ERROR;
            }
            default -> {
                err.println("Unerwarteter Fehler: " + ex);
                code = ERROR;
            }
        }
        if (verbose) {
            ex.printStackTrace(err);
        }
        err.flush();
        return code;
    }

    private static int confluence(ConfluenceException c, PrintWriter err) {
        String status = c.status() > 0 ? " (HTTP " + c.status() + ")" : "";
        err.println("Fehler" + status + ": " + c.getMessage());
        if (c.isAuthProblem()) {
            err.println("Hinweis: Token bzw. Benutzername/Passwort prüfen und ob du Lese-/Schreibrechte auf "
                    + "Seite und Space hast. «cfh whoami» testet die Anmeldung.");
            return AUTH;
        }
        if (c.status() == 409) {
            err.println("Hinweis: Die Seite wurde inzwischen auf dem Server geändert. «cfh diff» zeigt die "
                    + "Unterschiede. Mit --force überschreiben oder die Server-Version mit «cfh export … -o "
                    + "ANDERER_ORDNER» holen und die Änderungen zusammenführen (page.xhtml nicht einfach ersetzen).");
            return VERSION_CONFLICT;
        }
        return ERROR;
    }

    /**
     * Redirected output (e.g. {@code cfh convert x.pdf > page.xhtml}) is always UTF-8, the encoding
     * Confluence storage files must have; Windows would otherwise use the ANSI code page and lose
     * characters. Only an interactive console gets its own encoding.
     */
    static Charset stdoutCharset(boolean terminal, Charset consoleCharset) {
        return terminal ? consoleCharset : StandardCharsets.UTF_8;
    }

    private static boolean isTerminal() {
        Console console = System.console();
        if (console == null) {
            return false;
        }
        try {
            // Java 22+: System.console() may exist although stdout is redirected
            return (Boolean) Console.class.getMethod("isTerminal").invoke(console);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    private static PrintWriter writer(PrintStream stream) {
        return new PrintWriter(new OutputStreamWriter(stream, stream.charset()), true);
    }
}
