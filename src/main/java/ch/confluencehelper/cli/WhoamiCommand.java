package ch.confluencehelper.cli;

import picocli.CommandLine.Command;

@Command(name = "whoami", mixinStandardHelpOptions = true,
        description = "Verbindung und angemeldeten Benutzer prüfen.")
class WhoamiCommand extends BaseCommand {

    @Override
    public Integer call() {
        String user = api().currentUser();
        out().println("Angemeldet als " + user + " auf " + api().baseUrl());
        return 0;
    }
}
