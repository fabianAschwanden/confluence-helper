package ch.confluencehelper.cli;

import ch.confluencehelper.client.ConfluenceClient;
import ch.confluencehelper.config.Config;
import ch.confluencehelper.sync.PageApi;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.Map;

@Command(name = "cfh",
        mixinStandardHelpOptions = true,
        versionProvider = CfhCommand.Version.class,
        description = "Confluence-Seiten exportieren, bearbeiten, anlegen und aus PDF/Excel/CSV importieren.",
        subcommands = {
                WhoamiCommand.class, ExportCommand.class, PushCommand.class, CreateCommand.class,
                ConvertCommand.class, ImportCommand.class, DiffCommand.class, ValidateCommand.class,
                UiCommand.class, WorkspaceCommand.class, CommandLine.HelpCommand.class
        },
        footer = {"", "Exit-Codes: 0 ok, 1 Fehler, 2 Validierung, 3 Versionskonflikt, 4 Anmeldung/Rechte"})
public class CfhCommand implements Runnable {

    /** Creates the API client lazily, so offline commands (convert, validate) need no configuration. */
    @FunctionalInterface
    public interface ApiFactory {
        PageApi create(String url, String profile, boolean verbose);
    }

    public static final ApiFactory DEFAULT_FACTORY = clientFactory(System.getenv(), System.err);

    /**
     * Factory for the real REST client, reading {@code CONFLUENCE_*}/{@code CFH_CONFIG} from {@code env}.
     * {@code log} receives config warnings and the verbose HTTP log.
     */
    public static ApiFactory clientFactory(Map<String, String> env, PrintStream log) {
        Map<String, String> copy = Map.copyOf(env);
        return (url, profile, verbose) ->
                PageApi.of(new ConfluenceClient(Config.load(url, profile, copy, log), verbose, log));
    }

    @Option(names = "--url", scope = ScopeType.INHERIT, paramLabel = "URL",
            description = "Confluence-Basis-URL (überschreibt Konfiguration)")
    String url;

    @Option(names = "--profile", scope = ScopeType.INHERIT, paramLabel = "NAME",
            description = "Profil aus der Konfigurationsdatei")
    String profile;

    @Option(names = {"-v", "--verbose"}, scope = ScopeType.INHERIT,
            description = "HTTP-Aufrufe und Details ausgeben")
    boolean verbose;

    @Spec
    CommandSpec spec;

    private final ApiFactory factory;
    private PageApi api;

    public CfhCommand() {
        this(DEFAULT_FACTORY);
    }

    public CfhCommand(ApiFactory factory) {
        this.factory = factory;
    }

    PageApi api() {
        if (api == null) {
            api = factory.create(url, profile, verbose);
        }
        return api;
    }

    boolean verbose() {
        return verbose;
    }

    @Override
    public void run() {
        PrintWriter out = spec.commandLine().getOut();
        spec.commandLine().usage(out);
    }

    static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String v = CfhCommand.class.getPackage().getImplementationVersion();
            return new String[]{"cfh " + (v == null ? "dev" : v)};
        }
    }
}
