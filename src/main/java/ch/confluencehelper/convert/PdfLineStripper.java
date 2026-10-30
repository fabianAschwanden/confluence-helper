package ch.confluencehelper.convert;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/** Collects text lines together with position and font information. */
final class PdfLineStripper extends PDFTextStripper {

    /** Minimum number of side-by-side line pairs before a page region is treated as two text columns. */
    private static final int MIN_COLUMN_LINES = 2;

    /** Text runs of one visual line, split where the horizontal gap is too large for a word space. */
    private record Segment(String text, List<TextPosition> positions, float x0, float x1) {

        int words() {
            return text.isEmpty() ? 0 : text.split(" ").length;
        }
    }

    private final List<PdfLine> lines = new ArrayList<>();
    /** Lines of the current page, each as its segments (left to right). */
    private final List<List<Segment>> pageLines = new ArrayList<>();
    private final List<StringBuilder> segText = new ArrayList<>();
    private final List<List<TextPosition>> segPositions = new ArrayList<>();

    PdfLineStripper() {
        setSortByPosition(true);
        setSuppressDuplicateOverlappingText(true);
    }

    List<PdfLine> extract(PDDocument document) throws IOException {
        lines.clear();
        writeText(document, new StringWriter());
        return List.copyOf(lines);
    }

    @Override
    protected void writeString(String string, List<TextPosition> textPositions) {
        if (!textPositions.isEmpty() && !segPositions.isEmpty() && !segPositions.getLast().isEmpty()) {
            TextPosition last = segPositions.getLast().getLast();
            TextPosition first = textPositions.getFirst();
            float gap = first.getXDirAdj() - (last.getXDirAdj() + last.getWidthDirAdj());
            if (gap > columnGap(last)) {
                startSegment();
            }
        }
        if (segText.isEmpty()) {
            startSegment();
        }
        segText.getLast().append(string);
        segPositions.getLast().addAll(textPositions);
    }

    @Override
    protected void writeWordSeparator() {
        if (!segText.isEmpty()) {
            segText.getLast().append(' ');
        }
    }

    @Override
    protected void writeLineSeparator() {
        endLine();
    }

    @Override
    protected void endPage(PDPage page) throws IOException {
        endLine();
        emitPage();
        super.endPage(page);
    }

    private void startSegment() {
        segText.add(new StringBuilder());
        segPositions.add(new ArrayList<>());
    }

    /** A gap wider than three spaces (at least one em) separates columns or table cells, not words. */
    private static float columnGap(TextPosition tp) {
        float size = fontSize(tp);
        float space = tp.getWidthOfSpace();
        if (!(space > 0) || Float.isNaN(space) || space > size) {
            space = size * 0.28f;
        }
        return Math.max(3 * space, size);
    }

    private void endLine() {
        List<Segment> segments = new ArrayList<>();
        for (int i = 0; i < segText.size(); i++) {
            String t = normalize(segText.get(i).toString());
            List<TextPosition> pos = segPositions.get(i);
            if (t.isEmpty() || pos.isEmpty()) {
                continue;
            }
            TextPosition last = pos.getLast();
            segments.add(new Segment(t, List.copyOf(pos), pos.getFirst().getXDirAdj(),
                    last.getXDirAdj() + last.getWidthDirAdj()));
        }
        if (!segments.isEmpty()) {
            pageLines.add(segments);
        }
        segText.clear();
        segPositions.clear();
    }

    /**
     * Emits the page's lines. Where two text columns sit side by side (sorting by position puts both
     * on the same visual line), the left column is emitted before the right one; other multi-part lines
     * such as table rows stay single lines.
     */
    private void emitPage() {
        float gutter = findGutter();
        int i = 0;
        while (i < pageLines.size()) {
            int end = gutter > 0 ? columnRunEnd(i, gutter) : i;
            if (end > i) {
                List<Segment> right = new ArrayList<>();
                for (int j = i; j < end; j++) {
                    for (Segment s : pageLines.get(j)) {
                        if (s.x0() >= gutter - 4) {
                            right.add(s);
                        } else {
                            addLine(s.text(), s.positions());
                        }
                    }
                }
                right.forEach(s -> addLine(s.text(), s.positions()));
                i = end;
            } else {
                List<Segment> segs = pageLines.get(i);
                StringBuilder text = new StringBuilder();
                List<TextPosition> pos = new ArrayList<>();
                for (Segment s : segs) {
                    text.append(text.isEmpty() ? "" : " ").append(s.text());
                    pos.addAll(s.positions());
                }
                addLine(text.toString(), pos);
                i++;
            }
        }
        pageLines.clear();
    }

    /** x position of the right column if at least two lines consist of two multi-word parts aligned there. */
    private float findGutter() {
        List<Float> starts = new ArrayList<>();
        for (List<Segment> segs : pageLines) {
            if (isColumnPair(segs)) {
                starts.add(segs.get(1).x0());
            }
        }
        float best = -1;
        int bestCount = 0;
        for (float x : starts) {
            int count = 0;
            float min = x;
            for (float y : starts) {
                if (Math.abs(x - y) <= 4) {
                    count++;
                    min = Math.min(min, y);
                }
            }
            if (count > bestCount) {
                bestCount = count;
                best = min;
            }
        }
        return bestCount >= MIN_COLUMN_LINES ? best : -1;
    }

    private static boolean isColumnPair(List<Segment> segs) {
        return segs.size() == 2 && segs.get(0).words() >= 2 && segs.get(1).words() >= 2
                && segs.get(0).text().length() >= 10 && segs.get(1).text().length() >= 10;
    }

    /**
     * End (exclusive) of a run of lines starting at {@code start} that fit the two-column layout, or
     * {@code start} if there is no such run with enough side-by-side lines.
     */
    private int columnRunEnd(int start, float gutter) {
        int end = start;
        int pairs = 0;
        while (end < pageLines.size() && fitsColumns(pageLines.get(end), gutter)) {
            List<Segment> segs = pageLines.get(end);
            if (isColumnPair(segs) && Math.abs(segs.get(1).x0() - gutter) <= 4) {
                pairs++;
            }
            end++;
        }
        return pairs >= MIN_COLUMN_LINES ? end : start;
    }

    /** Every part lies entirely left of the gutter or starts at it; at most one part per side. */
    private static boolean fitsColumns(List<Segment> segs, float gutter) {
        int left = 0;
        int right = 0;
        for (Segment s : segs) {
            if (s.x0() >= gutter - 4) {
                right++;
            } else if (s.x1() <= gutter - 2) {
                left++;
            } else {
                return false;
            }
        }
        return left <= 1 && right <= 1;
    }

    private void addLine(String normalized, List<TextPosition> positions) {
        if (normalized.isEmpty() || positions.isEmpty()) {
            return;
        }
        Map<Float, Integer> sizes = new HashMap<>();
        int boldChars = 0;
        int chars = 0;
        List<Float> ys = new ArrayList<>();
        for (TextPosition tp : positions) {
            String u = tp.getUnicode();
            if (u == null || u.isBlank()) {
                continue;
            }
            int n = u.length();
            chars += n;
            sizes.merge(round(fontSize(tp)), n, Integer::sum);
            if (isBold(tp.getFont())) {
                boldChars += n;
            }
            ys.add(tp.getYDirAdj());
        }
        if (chars == 0) {
            return;
        }
        float size = sizes.entrySet().stream()
                .max(Map.Entry.<Float, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .orElseThrow().getKey();
        ys.sort(null);
        float y = ys.get(ys.size() / 2);
        float x = positions.getFirst().getXDirAdj();
        float height = getCurrentPage() == null ? 0 : getCurrentPage().getCropBox().getHeight();
        lines.add(new PdfLine(getCurrentPageNo(), normalized, size, boldChars >= chars * 0.9, x, y, height));
    }

    private static float fontSize(TextPosition tp) {
        float pt = tp.getFontSizeInPt();
        return pt > 0 ? pt : tp.getHeightDir();
    }

    private static float round(float size) {
        return Math.round(size * 2) / 2f;
    }

    static boolean isBold(PDFont font) {
        if (font == null) {
            return false;
        }
        String name = font.getName() == null ? "" : font.getName().toLowerCase(Locale.ROOT);
        if (name.contains("bold") || name.contains("black") || name.contains("heavy") || name.contains("semibold")) {
            return true;
        }
        PDFontDescriptor fd = font.getFontDescriptor();
        return fd != null && (fd.isForceBold() || fd.getFontWeight() >= 700);
    }

    /** Private-use code points Office uses for list bullets in the Symbol and Wingdings fonts. */
    static final String PUA_BULLETS = "\uf0b7\uf0a7\uf0a8\uf076\uf0d8\uf0fc\uf0e0\uf06e\uf071\uf0a1\uf0fb";

    /** Collapses whitespace, maps Office bullet glyphs to •, and drops characters that are not allowed in XML. */
    static String normalize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                if (space && !sb.isEmpty()) {
                    sb.append(' ');
                }
                space = false;
                sb.append(c).append(s.charAt(++i));
                continue;
            }
            if (Character.isSurrogate(c) || c == '￾' || c == '￿' || (c < 0x20 && !Character.isWhitespace(c))) {
                continue;
            }
            if (c >= '\ue000' && c <= '\uf8ff') {
                // Symbol/Wingdings glyphs are mapped into the Private Use Area: keep bullets, drop the rest
                if (PUA_BULLETS.indexOf(c) < 0) {
                    continue;
                }
                c = '\u2022';
            }
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                space = true;
                continue;
            }
            if (space && !sb.isEmpty()) {
                sb.append(' ');
            }
            space = false;
            sb.append(c);
        }
        return sb.toString();
    }
}
