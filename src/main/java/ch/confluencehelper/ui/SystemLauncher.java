package ch.confluencehelper.ui;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * macOS uses {@code open} directly (AWT would add a Dock icon); elsewhere {@link Desktop} first, then
 * {@code rundll32}/{@code explorer} (Windows) or {@code xdg-open}.
 */
final class SystemLauncher implements Launcher {

    private final boolean mac;
    private final boolean windows;

    SystemLauncher() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        mac = os.contains("mac");
        windows = os.contains("win");
    }

    @Override
    public boolean browse(URI uri) {
        if (mac) {
            return run("open", uri.toString());
        }
        if (desktop(Desktop.Action.BROWSE, d -> d.browse(uri))) {
            return true;
        }
        return windows ? run("rundll32", "url.dll,FileProtocolHandler", uri.toString())
                : run("xdg-open", uri.toString());
    }

    @Override
    public boolean open(Path dir) {
        if (mac || windows) {
            return run(openCommand(dir, mac).toArray(String[]::new));
        }
        return desktop(Desktop.Action.OPEN, d -> d.open(dir.toFile())) || run("xdg-open", dir.toString());
    }

    /**
     * macOS: {@code open <dir>} would start a folder that LaunchServices takes for an app bundle, so the
     * folder is only revealed in Finder ({@code -R} never launches anything), via its page.xhtml if present
     * so that Finder shows the folder's content.
     */
    static List<String> openCommand(Path dir, boolean mac) {
        if (!mac) {
            return List.of("explorer", dir.toString());
        }
        Path xhtml = dir.resolve("page.xhtml");
        return List.of("open", "-R", Files.isRegularFile(xhtml, LinkOption.NOFOLLOW_LINKS) ? xhtml.toString()
                : dir.toString());
    }

    @Override
    public boolean openIde(Ide ide, Path project, Path file) {
        List<String> cmd = ideCommand(ide, project, file, mac, windows, Probe.SYSTEM, System.getenv(),
                Path.of(System.getProperty("user.home")));
        return cmd != null && run(cmd.toArray(String[]::new));
    }

    /** File system lookups, replaceable in tests. */
    interface Probe {
        boolean exists(Path p);

        List<Path> children(Path dir);

        Probe SYSTEM = new Probe() {
            @Override
            public boolean exists(Path p) {
                return Files.exists(p);
            }

            @Override
            public List<Path> children(Path dir) {
                try (Stream<Path> s = Files.list(dir)) {
                    return s.sorted().toList();
                } catch (IOException | RuntimeException e) {
                    return List.of();
                }
            }
        };
    }

    private static final List<String> INTELLIJ_APPS = List.of("IntelliJ IDEA.app", "IntelliJ IDEA Ultimate.app",
            "IntelliJ IDEA CE.app", "IntelliJ IDEA Community Edition.app");

    /**
     * The command that opens {@code project} (and {@code file}, VS Code only) in the IDE, as an argument list
     * without any shell; null if the IDE is not found. Windows avoids {@code .cmd} launchers (cmd.exe would
     * interpret characters in the path) and starts the {@code .exe} directly.
     */
    static List<String> ideCommand(Ide ide, Path project, Path file, boolean mac, boolean windows, Probe probe,
                                   Map<String, String> env, Path home) {
        List<String> tail = new ArrayList<>(List.of(project.toString()));
        if (ide == Ide.VSCODE && file != null) {
            tail.add(file.toString());
        }
        if (mac) {
            List<Path> bases = List.of(Path.of("/Applications"), home.resolve("Applications"));
            if (ide == Ide.VSCODE) {
                Path code = which("code", env, probe);
                if (code != null) {
                    return concat(List.of(code.toString()), tail);
                }
                for (Path base : bases) {
                    Path app = base.resolve("Visual Studio Code.app");
                    Path cli = app.resolve("Contents/Resources/app/bin/code");
                    if (probe.exists(cli)) {
                        return concat(List.of(cli.toString()), tail);
                    }
                    if (probe.exists(app)) {
                        return List.of("open", "-a", app.toString(), project.toString());
                    }
                }
                return null;
            }
            Path idea = which("idea", env, probe);
            if (idea != null) {
                return concat(List.of(idea.toString()), tail);
            }
            for (Path base : bases) {
                for (String name : INTELLIJ_APPS) {
                    if (probe.exists(base.resolve(name))) {
                        return concat(List.of("open", "-na", base.resolve(name).toString(), "--args"), tail);
                    }
                }
            }
            return null;
        }
        if (windows) {
            List<Path> exes = new ArrayList<>();
            if (ide == Ide.VSCODE) {
                for (String var : List.of("LOCALAPPDATA", "ProgramFiles", "ProgramFiles(x86)")) {
                    String base = env.get(var);
                    if (base != null && !base.isBlank()) {
                        exes.add(Path.of(base, var.equals("LOCALAPPDATA") ? "Programs" : "", "Microsoft VS Code", "Code.exe"));
                    }
                }
                Path cmd = which("code.cmd", env, probe);
                if (cmd != null && cmd.getParent() != null && cmd.getParent().getParent() != null) {
                    exes.add(cmd.getParent().getParent().resolve("Code.exe"));
                }
            } else {
                Path exe = which("idea64.exe", env, probe);
                if (exe != null) {
                    exes.add(exe);
                }
                for (String[] dir : List.of(new String[]{"ProgramFiles", "JetBrains"}, new String[]{"LOCALAPPDATA", "Programs"})) {
                    String base = env.get(dir[0]);
                    if (base == null || base.isBlank()) {
                        continue;
                    }
                    // newest installation first (folder names end with the version)
                    List<Path> found = new ArrayList<>(probe.children(Path.of(base, dir[1])).stream()
                            .filter(p -> p.getFileName().toString().startsWith("IntelliJ IDEA")).toList());
                    found.sort(Comparator.reverseOrder());
                    found.forEach(p -> exes.add(p.resolve("bin").resolve("idea64.exe")));
                }
            }
            return exes.stream().filter(probe::exists).findFirst()
                    .map(exe -> concat(List.of(exe.toString()), tail)).orElse(null);
        }
        List<String> names = ide == Ide.VSCODE ? List.of("code")
                : List.of("idea", "idea.sh", "intellij-idea-ultimate", "intellij-idea-community");
        for (String n : names) {
            Path p = which(n, env, probe);
            if (p != null) {
                return concat(List.of(p.toString()), tail);
            }
        }
        return null;
    }

    /** A program from PATH; relative PATH entries (e.g. the current folder) are ignored. */
    static Path which(String name, Map<String, String> env, Probe probe) {
        String path = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            try {
                Path d = Path.of(dir.strip());
                if (d.isAbsolute() && probe.exists(d.resolve(name))) {
                    return d.resolve(name);
                }
            } catch (RuntimeException e) {
                // invalid PATH entry
            }
        }
        return null;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static boolean run(String... command) {
        try {
            new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private interface DesktopCall {
        void call(Desktop d) throws IOException;
    }

    private static boolean desktop(Desktop.Action action, DesktopCall call) {
        try {
            if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()
                    || !Desktop.getDesktop().isSupported(action)) {
                return false;
            }
            call.call(Desktop.getDesktop());
            return true;
        } catch (IOException | RuntimeException | LinkageError e) {
            return false;
        }
    }
}
