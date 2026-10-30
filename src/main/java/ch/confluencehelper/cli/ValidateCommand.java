package ch.confluencehelper.cli;

import ch.confluencehelper.storage.Problem;
import ch.confluencehelper.storage.StorageXml;
import ch.confluencehelper.sync.PageStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;

@Command(name = "validate", mixinStandardHelpOptions = true,
        description = "Storage-XHTML auf Wohlgeformtheit und bekannte Storage-Regeln prüfen.")
class ValidateCommand extends BaseCommand {

    @Parameters(paramLabel = "page.xhtml", description = "XHTML-Datei oder Seitenordner")
    Path target;

    @Override
    public Integer call() {
        PageStore.Location loc = PageStore.locate(target);
        List<Problem> problems = StorageXml.validate(PageStore.readXhtml(loc.xhtml()));
        // absolute file path, so IDE problem matchers and terminals can link to the line
        printProblems(out(), loc.xhtml().toString(), problems);
        long errors = problems.stream().filter(Problem::isError).count();
        if (errors > 0) {
            out().println(errors + " Fehler, " + (problems.size() - errors) + " Warnungen.");
            return 2;
        }
        out().println(problems.isEmpty() ? "OK – keine Probleme gefunden."
                : "OK – " + problems.size() + " Warnungen.");
        return 0;
    }
}
