package ch.confluencehelper.cli;

import ch.confluencehelper.storage.Problem;
import ch.confluencehelper.storage.StorageXml;
import ch.confluencehelper.sync.PageStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;

@Command(name = "convert", mixinStandardHelpOptions = true,
        description = "PDF, Excel (.xlsx/.xls) oder CSV lokal in Confluence-Storage-XHTML umwandeln.")
class ConvertCommand extends BaseCommand {

    @Parameters(paramLabel = "DATEI", description = "Eingabedatei (.pdf, .xlsx, .xls, .csv)")
    Path input;

    @Option(names = {"-o", "--output"}, paramLabel = "page.xhtml",
            description = "Zieldatei (Standard: Ausgabe auf stdout)")
    Path output;

    @Option(names = "--title", paramLabel = "T", description = "Überschrift (h1) an den Anfang setzen")
    String title;

    @Mixin
    ConversionOptions conversion;

    @Override
    public Integer call() {
        String storage = conversion.convert(input, title);
        List<Problem> problems = StorageXml.validate(storage);
        String name = output == null ? input.toString() : output.toString();
        printProblems(err(), name, problems);
        if (output == null) {
            out().print(storage);
            out().flush();
        } else {
            PageStore.writeXhtml(output, storage);
            err().println("Geschrieben: " + output.toAbsolutePath().normalize());
        }
        return problems.stream().anyMatch(Problem::isError) ? 2 : 0;
    }
}
