package ch.confluencehelper.workspace;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where this cfh lives, as written into IDE task files: the launchers {@code bin/cfh} and
 * {@code bin\cfh.cmd} next to {@code target/cfh.jar}, the JAR itself and the running JDK.
 *
 * @param command        POSIX launcher; plain {@code cfh} (from PATH) if unknown
 * @param commandWindows Windows launcher; plain {@code cfh.cmd} if unknown
 * @param jar            the shaded JAR, or null when not running from one (IDE run configurations need it)
 * @param javaHome       JDK for the IDE run configurations, or null
 */
public record Installation(String command, String commandWindows, Path jar, Path javaHome) {

    private static final Pattern CELLAR = Pattern.compile("(.*)/Cellar/([^/]+)/[^/]+/(.*)");

    public static final Installation PATH_ONLY = new Installation("cfh", "cfh.cmd", null, null);

    /** This installation, derived from the JAR the classes were loaded from. */
    public static Installation detect() {
        try {
            CodeSource src = Installation.class.getProtectionDomain().getCodeSource();
            if (src == null) {
                return PATH_ONLY;
            }
            return fromJar(Path.of(src.getLocation().toURI()), stableJavaHome(Path.of(System.getProperty("java.home"))));
        } catch (URISyntaxException | IllegalArgumentException | SecurityException e) {
            return PATH_ONLY;
        }
    }

    /** {@code <repo>/target/cfh.jar} → {@code <repo>/bin/cfh}; a class folder (tests, IDE) → {@link #PATH_ONLY}. */
    static Installation fromJar(Path codeSource, Path javaHome) {
        Path jar = codeSource.toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar) || !jar.getFileName().toString().endsWith(".jar") || jar.getParent() == null
                || jar.getParent().getParent() == null) {
            return PATH_ONLY;
        }
        Path bin = jar.getParent().getParent().resolve("bin");
        Path sh = bin.resolve("cfh");
        Path cmd = bin.resolve("cfh.cmd");
        return new Installation(Files.isRegularFile(sh) ? sh.toString() : "cfh",
                Files.isRegularFile(cmd) ? cmd.toString() : "cfh.cmd", jar,
                javaHome != null && Files.isDirectory(javaHome) ? javaHome : null);
    }

    /**
     * Homebrew's {@code …/Cellar/openjdk@21/21.0.4/…} disappears with the next upgrade; its
     * {@code …/opt/openjdk@21/…} link stays.
     */
    static Path stableJavaHome(Path javaHome) {
        Matcher m = CELLAR.matcher(javaHome.toString().replace('\\', '/'));
        if (m.matches()) {
            Path opt = Path.of(m.group(1), "opt", m.group(2), m.group(3));
            if (Files.isDirectory(opt)) {
                return opt;
            }
        }
        return javaHome;
    }

    /** IntelliJ run configurations start the JAR directly, so they need both paths. */
    public boolean hasJar() {
        return jar != null && javaHome != null;
    }

    /** The command as typed in a terminal of this OS: POSIX-quoted, or PowerShell {@code & "…"} on Windows. */
    public String shellCommand(boolean windows) {
        if (windows) {
            return commandWindows.matches("[A-Za-z0-9._-]+") ? commandWindows
                    : "& \"" + commandWindows.replace("\"", "") + "\"";
        }
        return command.matches("[A-Za-z0-9._/+-]+") ? command : "'" + command.replace("'", "'\\''") + "'";
    }
}
