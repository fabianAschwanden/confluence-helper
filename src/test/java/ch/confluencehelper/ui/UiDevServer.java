package ch.confluencehelper.ui;

import ch.confluencehelper.config.Config;
import ch.confluencehelper.config.ConfigFile;
import ch.confluencehelper.testing.FakeConfluenceServer;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * Starts the UI against an in-memory Confluence with demo content, for manual testing and reviews.
 * Usage: {@code UiDevServer [port] [--no-open]} (default 8765); prints {@code UI_URL=<url>}. Ctrl+C or «Beenden»
 * stops it. {@code --no-open} only logs «Ordner öffnen» instead of opening Finder/Explorer (for automated runs).
 * Stdin commands simulate other Confluence users: {@code edit <id>} appends a paragraph to the page (new version),
 * {@code version <id>} prints {@code VERSION <id> <n>}.
 */
public final class UiDevServer {

    private UiDevServer() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 && !args[0].startsWith("-") ? Integer.parseInt(args[0]) : 8765;
        boolean openFolders = !java.util.Arrays.asList(args).contains("--no-open");
        FakeConfluenceServer fake = FakeConfluenceServer.start("/confluence");
        fake.addSpace("DEMO");
        fake.setCurrentUser("anna.muster", "Anna Muster");
        fake.enableBasicAuth("anna.muster", "geheim");
        String home = fake.addPage("DEMO", null, "Teamhandbuch", """
                <h1>Teamhandbuch</h1>
                <p>Willkommen im Handbuch des Teams Zürich. Hier stehen Abläufe, Zuständigkeiten und Kontakte.</p>
                <ac:structured-macro ac:name="info" ac:schema-version="1"><ac:rich-text-body>
                <p>Änderungen bitte immer mit einem kurzen Versionskommentar speichern.</p>
                </ac:rich-text-body></ac:structured-macro>
                <h2>Zuständigkeiten</h2>
                <table><tbody>
                <tr><th>Bereich</th><th>Person</th><th>Stellvertretung</th></tr>
                <tr><td>Buchhaltung</td><td>Jürg Müller</td><td>Anna Muster</td></tr>
                <tr><td>Informatik</td><td>Béatrice Lüthi</td><td>Jürg Müller</td></tr>
                <tr><td>Empfang</td><td>Ömer Özdemir</td><td>Béatrice Lüthi</td></tr>
                </tbody></table>
                <h2>Ablauf Ferienantrag</h2>
                <ol><li><p>Antrag im Formular erfassen</p></li><li><p>Freigabe durch die Teamleitung</p></li>
                <li><p>Eintrag im Teamkalender</p></li></ol>
                <p><ac:image><ri:attachment ri:filename="organigramm.png" /></ac:image></p>
                """);
        fake.addPage("DEMO", home, "Kontakte & Öffnungszeiten", """
                <h1>Kontakte &amp; Öffnungszeiten</h1>
                <p>Montag bis Freitag, 08:00–17:00 Uhr.</p>
                <ul><li><p>Telefon: 044 123 45 67</p></li><li><p>E-Mail: team@example.ch</p></li></ul>
                """);
        fake.addPage("DEMO", null, "Sitzungsprotokolle", "<p>Protokolle der wöchentlichen Teamsitzung.</p>");
        fake.addAttachment(home, "organigramm.png", "image/png", tinyPng());

        Path base = Files.createTempDirectory("cfh-ui-dev-");
        Path config = base.resolve("config.properties");
        ConfigFile.update(config, Map.of("url", fake.url().toString(), "token", fake.token()), Set.of());
        Path workspace = Files.createDirectories(base.resolve("Confluence"));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> UiServer.deleteTree(base)));

        UiServer server = UiServer.start(new UiServer.Options()
                .port(port)
                .env(Map.of(Config.ENV_CONFIG, config.toString()))
                .workspace(workspace)
                .uiProperties(base.resolve("ui.properties"))
                .idleTimeout(Duration.ofHours(8))
                .launcher(new Launcher() { // the reviewer opens the URL; folders open for real
                    @Override public boolean browse(URI uri) { return false; }
                    @Override public boolean open(Path dir) {
                        if (openFolders) {
                            return Launcher.system().open(dir);
                        }
                        System.out.println("OPEN " + dir);
                        System.out.flush();
                        return true;
                    }
                    @Override public boolean openIde(Ide ide, Path project, Path file) {
                        if (openFolders) {
                            return Launcher.system().openIde(ide, project, file);
                        }
                        System.out.println("IDE " + ide + " " + project + (file == null ? "" : " " + file));
                        System.out.flush();
                        return true;
                    }
                }));
        System.out.println("Fake-Confluence: " + fake.url() + " (Space DEMO, Seite " + home + ")");
        System.out.println("Arbeitsordner: " + server.workspace());
        System.out.println("Anmeldung: Token FAKE_TOKEN=" + fake.token() + " oder Benutzer anna.muster / Passwort geheim");
        System.out.println("UI_URL=" + server.url());
        System.out.flush();
        Thread stdin = new Thread(() -> readCommands(fake), "dev-stdin");
        stdin.setDaemon(true);
        stdin.start();
        server.awaitStop();
        fake.close();
        System.exit(0); // runs the hook that deletes the demo folder
    }

    private static void readCommands(FakeConfluenceServer fake) {
        try (var in = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                String[] cmd = line.trim().split("\\s+");
                try {
                    if (cmd.length == 2 && cmd[0].equals("edit")) {
                        var page = fake.page(cmd[1]);
                        fake.editPage(cmd[1], page.title(), page.storage() + "<p>Änderung von jemand anderem.</p>");
                        System.out.println("EDITED " + cmd[1] + " " + fake.page(cmd[1]).version());
                    } else if (cmd.length == 2 && cmd[0].equals("version")) {
                        System.out.println("VERSION " + cmd[1] + " " + fake.page(cmd[1]).version());
                    } else if (!line.isBlank()) {
                        System.out.println("? edit <id> | version <id>");
                    }
                } catch (RuntimeException e) {
                    System.out.println("ERROR " + e.getMessage());
                }
                System.out.flush();
            }
        } catch (java.io.IOException ignored) {
            // stdin closed: keep serving
        }
    }

    /** 1×1 transparent PNG. */
    private static byte[] tinyPng() {
        return Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
    }
}
