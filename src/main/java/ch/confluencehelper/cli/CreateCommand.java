package ch.confluencehelper.cli;

import ch.confluencehelper.sync.Creator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;

@Command(name = "create", mixinStandardHelpOptions = true,
        description = "Neue Seite aus einer lokalen XHTML-Datei anlegen (schreibt page.json daneben).")
class CreateCommand extends BaseCommand {

    @Parameters(paramLabel = "page.xhtml|ORDNER", description = "XHTML-Datei oder Ordner mit page.xhtml")
    Path target;

    @Option(names = "--space", required = true, paramLabel = "K", description = "Space-Key")
    String space;

    @Option(names = "--title", required = true, paramLabel = "T", description = "Seitentitel")
    String title;

    @Option(names = "--parent", paramLabel = "SEITE", description = "Übergeordnete Seite (ID oder URL)")
    String parent;

    // POST /rest/api/content has no version comment in the client contract; accepted for symmetry with push.
    @Option(names = {"-m", "--message"}, paramLabel = "MSG", description = "Versionskommentar (derzeit ignoriert)")
    String message;

    @Override
    public Integer call() {
        Creator.Result r = new Creator(api()).create(target, space, title, parent);
        printProblems(err(), target.toString(), r.warnings());
        printCreated(r);
        return 0;
    }
}
