package ch.confluencehelper.cli;

import ch.confluencehelper.client.Page;
import ch.confluencehelper.sync.Differ;
import ch.confluencehelper.sync.PageMeta;
import ch.confluencehelper.sync.PageStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.Objects;

@Command(name = "diff", mixinStandardHelpOptions = true,
        description = "Lokale Seite mit der aktuellen Server-Version vergleichen (unified diff).")
class DiffCommand extends BaseCommand {

    @Parameters(paramLabel = "ORDNER|page.xhtml", description = "Exportierter Seitenordner oder XHTML-Datei")
    Path target;

    @Option(names = "--other-server", description = "Auch vergleichen, wenn page.json zu einer anderen Basis-URL gehört")
    boolean otherServer;

    @Override
    public Integer call() {
        PageStore.Location loc = PageStore.locate(target);
        PageMeta meta = PageStore.requireMeta(loc);
        String local = PageStore.readXhtml(loc.xhtml());
        PageStore.requireSameServer(meta, loc.meta(), api().baseUrl(), otherServer);
        Page server = api().getPage(meta.id());

        int gap = server.version() - meta.version();
        out().println("Seite " + meta.id() + ": lokal Version " + meta.version() + ", Server Version "
                + server.version() + (gap == 0 ? " (aktuell)"
                : gap > 0 ? " (Server ist " + gap + (gap == 1 ? " Version" : " Versionen") + " weiter)"
                : " (lokale Version ist neuer als der Server?)"));
        if (meta.title() != null && !Objects.equals(meta.title(), server.title())) {
            out().println("Titel: Server «" + server.title() + "», lokal «" + meta.title() + "»");
        }
        String diff = Differ.unified(server.storage(), local,
                "server/" + PageStore.XHTML + " (Version " + server.version() + ")", "lokal/" + PageStore.XHTML);
        if (diff.isEmpty()) {
            out().println("Keine Unterschiede im Inhalt.");
        } else {
            out().print(diff);
        }
        out().flush();
        return 0;
    }
}
