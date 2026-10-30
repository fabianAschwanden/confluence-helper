package ch.confluencehelper.cli;

import ch.confluencehelper.ui.UiServer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.BindException;
import java.nio.file.Path;

@Command(name = "ui", mixinStandardHelpOptions = true,
        description = "Lokale Weboberfläche im Browser starten (nur auf diesem Rechner erreichbar).")
class UiCommand extends BaseCommand {

    @Option(names = "--port", paramLabel = "N", defaultValue = "0",
            description = "Port auf 127.0.0.1 (Standard: zufällig)")
    int port;

    @Option(names = "--no-browser", description = "Browser nicht automatisch öffnen")
    boolean noBrowser;

    @Option(names = "--workspace", paramLabel = "DIR",
            description = "Arbeitsordner (Standard: zuletzt in der Oberfläche gewählt, sonst ~/Confluence)")
    Path workspace;

    @Override
    public Integer call() throws Exception {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("--port muss zwischen 0 und 65535 liegen");
        }
        UiServer server;
        try {
            server = UiServer.start(new UiServer.Options()
                    .port(port)
                    .workspace(workspace == null ? null : workspace.toAbsolutePath())
                    .url(root.url)
                    .profile(root.profile)
                    .verbose(root.verbose));
        } catch (BindException e) {
            throw new IllegalArgumentException("Port " + port + " ist bereits belegt; anderen Port wählen oder --port weglassen");
        }
        out().println("Läuft unter " + server.url() + " – Fenster schliessen oder «Beenden» im Browser");
        out().println("Arbeitsordner: " + server.workspace());
        out().flush();
        if (!noBrowser && !server.openBrowser()) {
            out().println("Browser konnte nicht geöffnet werden – Adresse oben im Browser öffnen.");
            out().flush();
        }
        server.awaitStop();
        out().println("Beendet.");
        return 0;
    }
}
