package ch.confluencehelper.cli;

import ch.confluencehelper.sync.Pusher;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;

@Command(name = "push", mixinStandardHelpOptions = true,
        description = "Lokal bearbeitete Seite als neue Version hochladen.")
class PushCommand extends BaseCommand {

    @Parameters(paramLabel = "ORDNER|page.xhtml", description = "Exportierter Seitenordner oder XHTML-Datei")
    Path target;

    @Option(names = "--title", paramLabel = "T", description = "Neuer Seitentitel")
    String title;

    @Option(names = {"-m", "--message"}, paramLabel = "MSG", description = "Versionskommentar")
    String message;

    @Option(names = "--dry-run", description = "Nur anzeigen, was geändert würde")
    boolean dryRun;

    @Option(names = "--force", description = "Trotz neuerer Server-Version überschreiben")
    boolean force;

    @Option(names = "--attachments", description = "Neue/geänderte Dateien aus attachments/ hochladen")
    boolean attachments;

    /** Set by IDE run configurations: IntelliJ turns Cancel in its comment prompt into an empty message. */
    @Option(names = "--require-message", hidden = true,
            description = "Ohne Versionskommentar abbrechen, statt hochzuladen")
    boolean requireMessage;

    @Option(names = "--other-server", description = "Trotz anderer Basis-URL in page.json auf den aktuell "
            + "konfigurierten Server schreiben (verknüpft den Ordner neu; --force genügt dafür nicht)")
    boolean otherServer;

    @Override
    public Integer call() {
        if (requireMessage && !dryRun && (message == null || message.isBlank())) {
            throw new IllegalArgumentException("Kein Versionskommentar angegeben – abgebrochen, nichts hochgeladen.");
        }
        Pusher.Result r = new Pusher(api()).push(target, new Pusher.Options(title, message, dryRun, force, attachments, otherServer));
        printProblems(err(), target.toString(), r.warnings());
        String changes = "+%d/-%d Zeilen".formatted(r.stats().added(), r.stats().removed());
        switch (r.status()) {
            case DRY_RUN -> {
                out().println("Probelauf – nichts hochgeladen.");
                if (r.newVersion() == r.serverVersion()) {
                    out().println("Keine Änderung an Inhalt und Titel.");
                } else {
                    out().println("Würde Version " + r.newVersion() + " von Seite " + r.pageId() + " erstellen: "
                            + changes + ".");
                }
                if (r.titleChanged()) {
                    out().println("Titel: «" + r.oldTitle() + "» → «" + r.newTitle() + "»");
                }
            }
            case NO_CHANGE -> out().println("Keine Änderung – Seite " + r.pageId() + " bleibt auf Version "
                    + r.newVersion() + ".");
            case UPDATED -> {
                out().println("Seite «" + r.newTitle() + "» aktualisiert: Version " + r.serverVersion() + " → "
                        + r.newVersion() + " (" + changes + ").");
                if (r.webUrl() != null) {
                    out().println(r.webUrl());
                }
            }
        }
        for (Path p : r.uploads()) {
            out().println((r.status() == Pusher.Status.DRY_RUN ? "Würde Anhang hochladen: " : "Anhang hochgeladen: ")
                    + p.getFileName());
        }
        return 0;
    }
}
