package ch.confluencehelper.convert;

import static ch.confluencehelper.storage.StorageXml.escapeAttr;
import static ch.confluencehelper.storage.StorageXml.escapeText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sparse table model rendered as a Confluence storage table, one row per line.
 * Leading/trailing empty rows and columns are trimmed; merged regions become rowspan/colspan.
 */
final class TableWriter {

    private record Cell(String text, String href) {
    }

    private record Region(int firstRow, int firstCol, int lastRow, int lastCol) {
    }

    /** Upper bound for rows × columns of one rendered table. */
    static final long MAX_CELLS = 1_000_000;

    private final boolean sheet;
    private final Map<Long, Cell> cells = new HashMap<>();

    /** @param sheet whether the table is an Excel sheet (for the wording of errors) */
    TableWriter(boolean sheet) {
        this.sheet = sheet;
    }
    private final List<Region> merges = new ArrayList<>();

    private static long key(int row, int col) {
        return ((long) row << 32) | (col & 0xffffffffL);
    }

    /** Stores a cell; blank cells without link are ignored. */
    void put(int row, int col, String text, String href) {
        String t = text == null ? "" : text;
        if (t.isBlank() && href == null) {
            return;
        }
        cells.put(key(row, col), new Cell(t, href));
    }

    void merge(int firstRow, int firstCol, int lastRow, int lastCol) {
        if (lastRow > firstRow || lastCol > firstCol) {
            merges.add(new Region(firstRow, firstCol, lastRow, lastCol));
        }
    }

    boolean isEmpty() {
        return cells.isEmpty();
    }

    /** Appends {@code <table>…</table>}; does nothing if the table has no content. */
    void write(StringBuilder out, boolean headerRow) {
        if (cells.isEmpty()) {
            return;
        }
        int minR = Integer.MAX_VALUE, minC = Integer.MAX_VALUE, maxR = Integer.MIN_VALUE, maxC = Integer.MIN_VALUE;
        for (long k : cells.keySet()) {
            int r = (int) (k >> 32), c = (int) k;
            minR = Math.min(minR, r);
            maxR = Math.max(maxR, r);
            minC = Math.min(minC, c);
            maxC = Math.max(maxC, c);
        }
        // merged regions are clamped to the cells that have content: a region reaching far beyond the
        // data (e.g. a title merged across a whole row) must not blow up the table
        long size = (long) (maxR - minR + 1) * (maxC - minC + 1);
        if (size > MAX_CELLS) {
            throw new TableTooLargeException(maxR - minR + 1, maxC - minC + 1, MAX_CELLS, sheet);
        }

        Map<Long, int[]> spans = new HashMap<>();
        Set<Long> covered = new HashSet<>();
        for (Region m : merges) {
            if (m.firstRow < minR || m.firstCol < minC || m.firstRow > maxR || m.firstCol > maxC) {
                continue; // blank anchor outside the table: cells render as plain empty cells
            }
            int lastRow = Math.min(m.lastRow, maxR), lastCol = Math.min(m.lastCol, maxC);
            if (lastRow == m.firstRow && lastCol == m.firstCol) {
                continue;
            }
            if (overlaps(m.firstRow, m.firstCol, lastRow, lastCol, spans, covered)) {
                continue; // invalid overlapping region
            }
            spans.put(key(m.firstRow, m.firstCol), new int[] {lastRow - m.firstRow + 1, lastCol - m.firstCol + 1});
            for (int r = m.firstRow; r <= lastRow; r++) {
                for (int c = m.firstCol; c <= lastCol; c++) {
                    if (r != m.firstRow || c != m.firstCol) {
                        covered.add(key(r, c));
                    }
                }
            }
        }

        out.append("<table>\n<tbody>\n");
        for (int r = minR; r <= maxR; r++) {
            String tag = headerRow && r == minR ? "th" : "td";
            out.append("<tr>");
            for (int c = minC; c <= maxC; c++) {
                long k = key(r, c);
                if (covered.contains(k)) {
                    continue;
                }
                out.append('<').append(tag);
                int[] span = spans.get(k);
                if (span != null) {
                    if (span[0] > 1) {
                        out.append(" rowspan=\"").append(span[0]).append('"');
                    }
                    if (span[1] > 1) {
                        out.append(" colspan=\"").append(span[1]).append('"');
                    }
                }
                out.append('>');
                Cell cell = cells.get(k);
                if (cell != null) {
                    appendContent(out, cell);
                }
                out.append("</").append(tag).append('>');
            }
            out.append("</tr>\n");
        }
        out.append("</tbody>\n</table>\n");
    }

    private static boolean overlaps(int r1, int c1, int r2, int c2, Map<Long, int[]> spans, Set<Long> covered) {
        for (int r = r1; r <= r2; r++) {
            for (int c = c1; c <= c2; c++) {
                long k = key(r, c);
                if (covered.contains(k) || spans.containsKey(k)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void appendContent(StringBuilder out, Cell cell) {
        String text = cell.text.strip();
        if (cell.href == null) {
            appendText(out, text);
            return;
        }
        out.append("<a href=\"").append(escapeAttr(cell.href)).append("\">");
        appendText(out, text.isEmpty() ? cell.href : text);
        out.append("</a>");
    }

    /** Escapes text and turns line breaks into {@code <br/>}. */
    static void appendText(StringBuilder out, String text) {
        String[] lines = text.split("\r\n|\r|\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append("<br/>");
            }
            out.append(escapeText(lines[i]));
        }
    }
}
