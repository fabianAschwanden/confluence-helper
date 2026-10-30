package ch.confluencehelper.cli;

import ch.confluencehelper.client.PageRefs;
import ch.confluencehelper.sync.Exporter;
import ch.confluencehelper.sync.PageApi;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

@Command(name = "export", mixinStandardHelpOptions = true,
        description = "Seite als <ID>-<titel>/page.xhtml + page.json herunterladen.")
class ExportCommand extends BaseCommand {

    @Parameters(paramLabel = "SEITE", description = "Seiten-ID oder Confluence-URL")
    String page;

    @Option(names = {"-o", "--output"}, paramLabel = "DIR", defaultValue = ".",
            description = "Zielordner (Standard: aktueller Ordner)")
    Path output;

    @Option(names = "--attachments", description = "Anhänge nach attachments/ herunterladen")
    boolean attachments;

    @Option(names = "--children", description = "Unterseiten mitexportieren (verschachtelte Ordner)")
    boolean children;

    @Option(names = "--depth", paramLabel = "N",
            description = "Maximale Tiefe der Unterseiten (Standard: unbegrenzt, impliziert --children)")
    Integer depth;

    @Option(names = "--force", description = "Lokale, noch nicht hochgeladene Änderungen überschreiben")
    boolean force;

    @Override
    public Integer call() {
        if (page == null || page.isBlank()) {
            // IntelliJ passes "" when its prompt is cancelled
            throw new IllegalArgumentException("Keine Seite angegeben – abgebrochen, nichts exportiert.");
        }
        if (depth != null && depth < 0) {
            throw new IllegalArgumentException("--depth muss 0 oder grösser sein");
        }
        boolean withChildren = children || depth != null;
        int maxDepth = withChildren ? (depth == null ? Integer.MAX_VALUE : depth) : 0;
        PageApi api = api();
        Exporter exporter = new Exporter(api, Clock.systemUTC(), root.verbose() ? err()::println : s -> { });
        List<Exporter.Result> results = exporter.export(PageRefs.parse(page, api.baseUrl()), output,
                new Exporter.Options(attachments, withChildren, maxDepth, force));
        for (Exporter.Result r : results) {
            String att = attachments ? " (" + r.attachments() + " Anhänge)" : "";
            out().println("  ".repeat(r.level()) + "«" + r.page().title() + "» Version " + r.page().version()
                    + " → " + r.dir() + att);
        }
        out().println(results.size() == 1 ? "1 Seite exportiert." : results.size() + " Seiten exportiert.");
        return 0;
    }
}
