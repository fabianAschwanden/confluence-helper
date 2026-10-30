package ch.confluencehelper.ui;

import java.net.URI;
import java.nio.file.Path;

/** Starts desktop programs; replaced in tests so nothing real is launched. */
public interface Launcher {

    enum Ide {
        VSCODE("VS Code"), INTELLIJ("IntelliJ IDEA");

        public final String label;

        Ide(String label) {
            this.label = label;
        }
    }

    /** @return whether a browser was started */
    boolean browse(URI uri);

    /** @return whether a file manager was started */
    boolean open(Path dir);

    /**
     * Opens {@code project} as project folder, and {@code file} (may be null) in it where the IDE supports that.
     * @return whether the IDE was started
     */
    default boolean openIde(Ide ide, Path project, Path file) {
        return false;
    }

    Launcher NONE = new Launcher() {
        @Override public boolean browse(URI uri) { return false; }
        @Override public boolean open(Path dir) { return false; }
    };

    static Launcher system() {
        return new SystemLauncher();
    }
}
