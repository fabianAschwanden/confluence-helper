package ch.confluencehelper.sync;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;

import java.util.Arrays;
import java.util.List;

/** Line-based comparison of storage XHTML; only line endings are normalised. */
public final class Differ {

    /** Changed lines of a diff from {@code from} to {@code to}. */
    public record Stats(int added, int removed) {

        public boolean isEmpty() {
            return added == 0 && removed == 0;
        }
    }

    private Differ() {
    }

    public static String normalize(String s) {
        return s == null ? "" : s.replace("\r\n", "\n").replace('\r', '\n');
    }

    public static boolean sameContent(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    public static Stats stats(String from, String to) {
        int added = 0;
        int removed = 0;
        for (AbstractDelta<String> d : diff(from, to).getDeltas()) {
            removed += d.getSource().size();
            added += d.getTarget().size();
        }
        return new Stats(added, removed);
    }

    /** Unified diff with 3 context lines; empty string if there is no difference. */
    public static String unified(String from, String to, String fromName, String toName) {
        List<String> a = lines(from);
        Patch<String> patch = DiffUtils.diff(a, lines(to));
        if (patch.getDeltas().isEmpty()) {
            return "";
        }
        return String.join("\n", UnifiedDiffUtils.generateUnifiedDiff(fromName, toName, a, patch, 3)) + "\n";
    }

    private static Patch<String> diff(String from, String to) {
        return DiffUtils.diff(lines(from), lines(to));
    }

    private static List<String> lines(String s) {
        String n = normalize(s);
        return n.isEmpty() ? List.of() : Arrays.asList(n.split("\n", -1));
    }
}
