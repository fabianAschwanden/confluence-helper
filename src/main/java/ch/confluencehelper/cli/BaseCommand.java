package ch.confluencehelper.cli;

import ch.confluencehelper.storage.Problem;
import ch.confluencehelper.sync.Creator;
import ch.confluencehelper.sync.PageApi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;

/** Shared plumbing: access to the root command, the API and picocli's output writers. */
abstract class BaseCommand implements Callable<Integer> {

    @ParentCommand
    CfhCommand root;

    @Spec
    CommandSpec spec;

    PageApi api() {
        return root.api();
    }

    PrintWriter out() {
        return spec.commandLine().getOut();
    }

    PrintWriter err() {
        return spec.commandLine().getErr();
    }

    void printCreated(Creator.Result r) {
        out().println("Seite «" + r.page().title() + "» angelegt (ID " + r.page().id() + ", Version "
                + r.page().version() + ").");
        if (r.page().webUrl() != null) {
            out().println(r.page().webUrl());
        }
        out().println("Verknüpft: " + r.location().meta() + " – weitere Änderungen mit «cfh push "
                + r.location().xhtml() + "».");
    }

    static void printProblems(PrintWriter w, String file, List<Problem> problems) {
        for (Problem p : problems) {
            w.println(format(file, p));
        }
    }

    static String format(String file, Problem p) {
        String kind = p.isError() ? "FEHLER" : "WARNUNG";
        String pos = p.line() > 0 ? ":" + p.line() + (p.column() > 0 ? ":" + p.column() : "") : "";
        return "%s%s: %s: %s".formatted(file, pos, kind, p.message());
    }
}
