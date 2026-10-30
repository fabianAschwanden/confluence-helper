package ch.confluencehelper.cli;

import ch.confluencehelper.sync.Creator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;

@Command(name = "import", mixinStandardHelpOptions = true,
        description = "Datei umwandeln und als neue Seite anlegen (= convert + create).")
class ImportCommand extends BaseCommand {

    @Parameters(paramLabel = "DATEI", description = "Eingabedatei (.pdf, .xlsx, .xls, .csv)")
    Path input;

    @Option(names = "--space", required = true, paramLabel = "K", description = "Space-Key")
    String space;

    @Option(names = "--title", required = true, paramLabel = "T", description = "Seitentitel")
    String title;

    @Option(names = "--parent", paramLabel = "SEITE", description = "Übergeordnete Seite (ID oder URL)")
    String parent;

    @Option(names = "--attach-source", description = "Originaldatei als Anhang hochladen")
    boolean attachSource;

    @Option(names = {"-o", "--output"}, paramLabel = "DIR", defaultValue = ".",
            description = "Lokale Kopie in DIR/<ID>-<titel>/ ablegen (Standard: aktueller Ordner)")
    Path output;

    @Option(names = {"-m", "--message"}, paramLabel = "MSG", description = "Kommentar für den Anhang")
    String message;

    @Mixin
    ConversionOptions conversion;

    @Override
    public Integer call() {
        String storage = conversion.convert(input, null);
        Creator.Result r = new Creator(api()).createInto(storage, output, space, title, parent);
        printProblems(err(), input.toString(), r.warnings());
        printCreated(r);
        if (attachSource) {
            api().upload(r.page().id(), input, message != null ? message : "Originaldatei des Imports");
            out().println("Anhang hochgeladen: " + input.getFileName());
        }
        return 0;
    }
}
