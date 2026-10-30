package ch.confluencehelper.cli;

import ch.confluencehelper.workspace.Installation;
import ch.confluencehelper.workspace.WorkspaceSetup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "workspace", mixinStandardHelpOptions = true,
        description = "Arbeitsordner für VS Code und IntelliJ mit GitHub Copilot einrichten.",
        subcommands = WorkspaceCommand.Init.class)
class WorkspaceCommand implements Runnable {

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    @Command(name = "init", mixinStandardHelpOptions = true,
            description = {"Legt Copilot-Anweisungen, Prompt-Dateien, AGENTS.md, VS-Code-Einstellungen und -Aufgaben "
                    + "sowie IntelliJ-Run-Konfigurationen an. Seitenordner bleiben unberührt.",
                    "Erneut ausführen aktualisiert unveränderte Dateien; von dir geänderte bleiben, ausser mit --force."})
    static class Init implements Callable<Integer> {

        @Spec
        CommandSpec spec;

        @Parameters(paramLabel = "DIR", arity = "0..1", defaultValue = ".",
                description = "Arbeitsordner (Standard: aktueller Ordner)")
        Path dir;

        @Option(names = "--force", description = "Auch von dir geänderte Dateien ersetzen (alte Fassung als .bak)")
        boolean force;

        @Override
        public Integer call() {
            WorkspaceSetup.Report r = new WorkspaceSetup(Installation.detect())
                    .init(dir, new WorkspaceSetup.Options(force, true));
            PrintWriter out = spec.commandLine().getOut();
            out.println("Arbeitsordner für GitHub Copilot eingerichtet: " + r.dir());
            r.lines().forEach(l -> out.println("  " + l));
            out.println("Öffnen:");
            out.println("  VS Code:  code \"" + r.dir() + "\"  (oder Datei > Ordner öffnen …)");
            out.println("  IntelliJ: Datei > Öffnen … > " + r.dir());
            out.println("Anleitung: docs/copilot.md im Repo von confluence-helper.");
            out.flush();
            return 0;
        }
    }
}
