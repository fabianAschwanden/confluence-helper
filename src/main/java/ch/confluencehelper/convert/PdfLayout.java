package ch.confluencehelper.convert;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns positioned PDF lines into structural blocks (headings, paragraphs, lists). */
final class PdfLayout {

    sealed interface Block permits Heading, Paragraph, ListBlock {
    }

    record Heading(int level, String text) implements Block {
    }

    record Paragraph(String text) implements Block {
    }

    record ListBlock(boolean ordered, List<String> items) implements Block {
    }

    /** Number of lines at the top/bottom of a page that are checked for headers, footers and page numbers. */
    private static final int EDGE_LINES = 3;
    private static final Pattern PAGE_NUMBER = Pattern.compile(
            "(?i)(?:(?:seite|page|pag\\.|s\\.|p\\.)\\s*)?[-–—]?\\s*\\d{1,4}\\s*[-–—]?"
                    + "(?:\\s*(?:/|von|of|de|di|sur)\\s*\\d{1,4})?");
    private static final Pattern SYMBOL_BULLET = Pattern.compile(
            "^[•·▪◦●○■□►▸‣⁃∙➢✓\uf0b7\uf0a7\uf0a8\uf076\uf0d8\uf0fc\uf0e0\uf06e\uf071\uf0a1\uf0fb]\\s*(\\S.*)$");
    private static final Pattern DASH_BULLET = Pattern.compile("^[-–—*]\\s+(\\S.*)$");
    private static final Pattern ORDERED = Pattern.compile("^((?:\\d{1,3}|[a-z])[.)])\\s+(\\S.*)$");
    /** German/Romance abbreviations that look like a letter marker: "z. B.", "d. h.", "u. a.", "s. o.". */
    private static final Pattern ABBREVIATION = Pattern.compile("^[a-z]\\.\\s*\\p{L}{1,2}\\..*");
    /** Words after a line-final hyphen that show a suspended compound ("Ein- und Ausgaben"). */
    private static final Set<String> CONJUNCTIONS = Set.of("und", "oder", "bis", "sowie", "beziehungsweise",
            "bzw.", "als", "noch", "wie", "resp.", "respektive", "statt", "anstatt", "et", "ou", "e", "o",
            "ed", "od", "and", "or", "to", "nor");

    private final List<PdfLine> lines;
    private final float body;
    private final float lineSpacing;
    private final Map<Float, Integer> headingLevels = new HashMap<>();
    private final int boldLevel;

    private PdfLayout(List<PdfLine> lines) {
        this.lines = lines;
        this.body = bodySize(lines);
        this.lineSpacing = lineSpacing(lines, body);
        TreeSet<Float> sizes = new TreeSet<>();
        for (PdfLine l : lines) {
            if (isHeadingSize(l)) {
                sizes.add(l.size());
            }
        }
        int level = 1;
        for (Float s : sizes.descendingSet()) {
            headingLevels.put(s, Math.min(3, level++));
        }
        this.boldLevel = Math.min(3, sizes.size() + 1);
    }

    static List<Block> layout(List<PdfLine> extracted, int pageCount) {
        List<PdfLine> content = removePageFurniture(extracted, pageCount);
        if (content.isEmpty()) {
            return List.of();
        }
        return new PdfLayout(content).build();
    }

    // ---- header / footer / page numbers ----

    /**
     * Removes running headers, footers and page numbers. A line is only considered if it belongs to a
     * block at the very top or bottom of the page (first/last {@value #EDGE_LINES} lines, outer 15 %)
     * that is set apart from the body text by a clear vertical gap. Such a line is dropped if
     * <ul>
     *   <li>its exact text repeats in that position on both pages (2 pages) or on at least half of the pages,</li>
     *   <li>it differs from the repeats only in the page number/page count (body or smaller font),</li>
     *   <li>it is set in a smaller font than the body and repeats when all numbers are ignored, or</li>
     *   <li>it is just a page number ("3", "- 3 -", "Seite 3 von 10").</li>
     * </ul>
     */
    static List<PdfLine> removePageFurniture(List<PdfLine> lines, int pageCount) {
        if (lines.isEmpty()) {
            return lines;
        }
        Map<Integer, List<PdfLine>> byPage = new LinkedHashMap<>();
        for (PdfLine l : lines) {
            byPage.computeIfAbsent(l.page(), p -> new ArrayList<>()).add(l);
        }
        float body = bodySize(lines);
        float detachedGap = lineSpacing(lines, body) * 1.8f;
        int pages = Math.max(pageCount, byPage.size());

        Set<PdfLine> edges = new HashSet<>();
        Map<String, Set<Integer>> exact = new HashMap<>();
        Map<String, Set<Integer>> numbered = new HashMap<>();
        Map<String, Set<Integer>> digitsIgnored = new HashMap<>();
        for (List<PdfLine> page : byPage.values()) {
            for (PdfLine l : edgeBlocks(page, detachedGap)) {
                edges.add(l);
                exact.computeIfAbsent(exactKey(l.text()), k -> new HashSet<>()).add(l.page());
                numbered.computeIfAbsent(pageNumberKey(l, pages), k -> new HashSet<>()).add(l.page());
                digitsIgnored.computeIfAbsent(digitKey(l.text()), k -> new HashSet<>()).add(l.page());
            }
        }
        // body size without the candidates: in short documents header and footer may outweigh the text
        List<PdfLine> rest = lines.stream().filter(l -> !edges.contains(l)).toList();
        float bodyText = rest.isEmpty() ? body : bodySize(rest);
        int threshold = pages == 2 ? 2 : (pages + 1) / 2;
        List<PdfLine> result = new ArrayList<>(lines.size());
        for (PdfLine l : lines) {
            if (edges.contains(l) && isFurniture(l, pages, threshold, bodyText, exact, numbered, digitsIgnored)) {
                continue;
            }
            result.add(l);
        }
        return result;
    }

    private static boolean isFurniture(PdfLine l, int pages, int threshold, float body,
                                       Map<String, Set<Integer>> exact, Map<String, Set<Integer>> numbered,
                                       Map<String, Set<Integer>> digitsIgnored) {
        if (isPageNumber(l.text())) {
            return true;
        }
        if (pages < 2) {
            return false;
        }
        if (exact.get(exactKey(l.text())).size() >= threshold) {
            return true;
        }
        if (l.size() <= body + 0.5f && numbered.get(pageNumberKey(l, pages)).size() >= threshold) {
            return true;
        }
        return l.size() < body - 0.5f && digitsIgnored.get(digitKey(l.text())).size() >= threshold;
    }

    /** Lines of the top and bottom block of a page that are separated from the body by a clear gap. */
    private static List<PdfLine> edgeBlocks(List<PdfLine> page, float detachedGap) {
        List<PdfLine> result = new ArrayList<>();
        int n = page.size();
        int top = 0;
        for (int k = Math.min(EDGE_LINES, n); k >= 1; k--) {
            if (page.subList(0, k).stream().allMatch(PdfLayout::inEdgeZone)
                    && (k == n || page.get(k).y() - page.get(k - 1).y() > detachedGap)) {
                top = k;
                break;
            }
        }
        result.addAll(page.subList(0, top));
        for (int k = Math.min(EDGE_LINES, n - top); k >= 1; k--) {
            if (page.subList(n - k, n).stream().allMatch(PdfLayout::inEdgeZone)
                    && (n - k == top || page.get(n - k).y() - page.get(n - k - 1).y() > detachedGap)) {
                result.addAll(page.subList(n - k, n));
                break;
            }
        }
        return result;
    }

    /** Headers and footers sit in the outer 15 % of the page. */
    private static boolean inEdgeZone(PdfLine l) {
        float h = l.pageHeight();
        return h <= 0 || l.y() < h * 0.15f || l.y() > h * 0.85f;
    }

    /** A line that is only a page number: "7", "- 7 -", "Seite 7", "7 / 12", "Page 7 of 12". */
    private static boolean isPageNumber(String text) {
        Matcher m = PAGE_NUMBER.matcher(text);
        if (!m.matches()) {
            return false;
        }
        // a bare 4-digit number is more likely a year or an article number than a page number
        return !text.strip().matches("\\d{4}");
    }

    private static String exactKey(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** Text with the page's own number and the page count replaced, so "Seite 2 von 5" repeats. */
    private static String pageNumberKey(PdfLine l, int pages) {
        Matcher m = Pattern.compile("\\d+").matcher(exactKey(l.text()));
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String digits = m.group();
            String replacement = digits;
            if (digits.length() <= 4) {
                int v = Integer.parseInt(digits);
                replacement = v == l.page() || v == pages ? "#" : digits;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String digitKey(String text) {
        return exactKey(text).replaceAll("\\d+", "#");
    }

    private static float bodySize(List<PdfLine> lines) {
        Map<Float, Integer> chars = new HashMap<>();
        for (PdfLine l : lines) {
            chars.merge(l.size(), l.text().replace(" ", "").length(), Integer::sum);
        }
        return chars.entrySet().stream()
                .max(Map.Entry.<Float, Integer>comparingByValue()
                        .thenComparing(Map.Entry.<Float, Integer>comparingByKey().reversed()))
                .orElseThrow().getKey();
    }

    /** Typical baseline distance between consecutive body lines, capped so paragraph gaps stay detectable. */
    private static float lineSpacing(List<PdfLine> lines, float body) {
        Map<Float, Integer> deltas = new HashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            PdfLine a = lines.get(i - 1);
            PdfLine b = lines.get(i);
            if (a.page() != b.page() || Math.abs(a.size() - body) > 0.5f || Math.abs(b.size() - body) > 0.5f) {
                continue;
            }
            float d = b.y() - a.y();
            if (d >= body * 0.8f && d <= body * 3f) {
                deltas.merge(Math.round(d * 2) / 2f, 1, Integer::sum);
            }
        }
        float mode = deltas.entrySet().stream()
                .max(Map.Entry.<Float, Integer>comparingByValue()
                        .thenComparing(Map.Entry.<Float, Integer>comparingByKey().reversed()))
                .map(Map.Entry::getKey)
                .orElse(body * 1.2f);
        return Math.min(mode, body * 1.35f);
    }

    private boolean isHeadingSize(PdfLine l) {
        float ratio = l.size() / body;
        boolean large = ratio >= 1.15f || (ratio >= 1.05f && l.bold());
        return large && l.text().length() <= 200 && l.text().codePoints().anyMatch(Character::isLetter);
    }

    // ---- block building ----

    private boolean isGap(int i) {
        if (i == 0) {
            return true;
        }
        PdfLine prev = lines.get(i - 1);
        PdfLine cur = lines.get(i);
        if (prev.page() != cur.page() || Math.abs(prev.size() - cur.size()) > 1f) {
            return true;
        }
        float delta = cur.y() - prev.y();
        float spacing = lineSpacing * Math.max(prev.size(), cur.size()) / body;
        return delta < -1f || delta > spacing * 1.3f;
    }

    private int headingLevel(int i) {
        PdfLine l = lines.get(i);
        Integer level = headingLevels.get(l.size());
        if (level != null && isHeadingSize(l)) {
            return level;
        }
        return isBoldHeading(i) ? boldLevel : 0;
    }

    /** Weak signal: a short, standalone, fully bold line at body size. */
    private boolean isBoldHeading(int i) {
        PdfLine l = lines.get(i);
        String t = l.text();
        if (!l.bold() || t.length() > 80 || l.size() < body - 0.5f || listMatch(t) != null
                || t.matches(".*[.,;]$") || t.codePoints().noneMatch(Character::isLetter)) {
            return false;
        }
        boolean startsBlock = i == 0 || isGap(i) || lines.get(i - 1).text().matches(".*[.!?:]$");
        boolean endsBlock = i == lines.size() - 1 || isGap(i + 1) || !lines.get(i + 1).bold();
        return startsBlock && endsBlock;
    }

    private List<Block> build() {
        List<Block> blocks = new ArrayList<>();
        StringBuilder heading = null;
        int headingLevel = 0;
        StringBuilder paragraph = null;
        List<StringBuilder> items = null;
        boolean ordered = false;
        float bulletX = 0;
        String lastMarker = null;

        for (int i = 0; i < lines.size(); i++) {
            PdfLine line = lines.get(i);
            String text = line.text().replace("­", "") + (line.text().endsWith("­") ? "­" : "");
            int level = headingLevel(i);
            boolean gap = isGap(i);

            if (level > 0) {
                if (heading != null && level == headingLevel && !gap) {
                    join(heading, text);
                    continue;
                }
                flush(blocks, heading, headingLevel, paragraph, items, ordered);
                paragraph = null;
                items = null;
                heading = new StringBuilder(text);
                headingLevel = level;
                continue;
            }

            String[] item = listMatch(text);
            if (item != null && !startsListItem(i, item[0], paragraph != null, items != null, ordered, lastMarker,
                    bulletX, gap)) {
                item = null; // e.g. "z. B." or "1. Januar" wrapped onto a new line inside a paragraph
            }
            if (item != null) {
                boolean isOrdered = item[0] != null;
                lastMarker = item[0];
                if (items == null || isOrdered != ordered) {
                    flush(blocks, heading, headingLevel, paragraph, items, ordered);
                    heading = null;
                    paragraph = null;
                    items = new ArrayList<>();
                    ordered = isOrdered;
                }
                items.add(new StringBuilder(item[1]));
                bulletX = line.x();
                continue;
            }

            if (items != null && !gap && line.x() > bulletX + 2f) {
                join(items.getLast(), text);
                continue;
            }
            if (paragraph != null && (!gap || continuesAcrossPages(i))) {
                join(paragraph, text);
                continue;
            }
            flush(blocks, heading, headingLevel, paragraph, items, ordered);
            heading = null;
            items = null;
            paragraph = new StringBuilder(text);
        }
        flush(blocks, heading, headingLevel, paragraph, items, ordered);
        return blocks;
    }

    /**
     * Whether a line that looks like a list item really starts one. Numbers and dashes at the start of a
     * wrapped line ("1. Januar", "– so der Bericht") only count when the previous line ended a block
     * or the line is indented differently; inside a list, an indented line continues the item.
     * Letter markers need ")" or must continue a letter sequence (a., b., …).
     */
    private boolean startsListItem(int i, String marker, boolean inParagraph, boolean inList, boolean ordered,
                                   String lastMarker, float bulletX, boolean gap) {
        PdfLine line = lines.get(i);
        if (inList && !gap && line.x() > bulletX + 2f) {
            return false;
        }
        if (marker != null && Character.isLetter(marker.charAt(0)) && marker.endsWith(".")) {
            char letter = marker.charAt(0);
            boolean continues = inList && ordered && lastMarker != null && lastMarker.length() == 2
                    && lastMarker.charAt(0) == letter - 1;
            if (!continues && letter != 'a') {
                return false;
            }
        }
        boolean symbolBullet = marker == null && SYMBOL_BULLET.matcher(line.text()).matches();
        if (symbolBullet || !inParagraph || gap || i == 0) {
            return true;
        }
        PdfLine prev = lines.get(i - 1);
        return prev.text().matches(".*[.!?:;]$") || Math.abs(line.x() - prev.x()) > 2f;
    }

    /** A paragraph continues on the next page when the sentence obviously did not end. */
    private boolean continuesAcrossPages(int i) {
        PdfLine prev = lines.get(i - 1);
        PdfLine cur = lines.get(i);
        if (prev.page() == cur.page() || Math.abs(prev.size() - cur.size()) > 1f) {
            return false;
        }
        String p = prev.text();
        return !p.matches(".*[.!?:]$") && startsLowercase(cur.text());
    }

    private static void flush(List<Block> blocks, StringBuilder heading, int level, StringBuilder paragraph,
                              List<StringBuilder> items, boolean ordered) {
        if (heading != null) {
            blocks.add(new Heading(level, clean(heading)));
        }
        if (paragraph != null) {
            blocks.add(new Paragraph(clean(paragraph)));
        }
        if (items != null) {
            blocks.add(new ListBlock(ordered, items.stream().map(PdfLayout::clean).toList()));
        }
    }

    private static String clean(StringBuilder sb) {
        return sb.toString().replace("­", "").trim();
    }

    /** Returns {orderedMarker-or-null, itemText} (marker e.g. "1." or "a)") or null if the line is no list item. */
    static String[] listMatch(String text) {
        Matcher m = ORDERED.matcher(text);
        if (m.matches() && !ABBREVIATION.matcher(text).matches()) {
            return new String[] {m.group(1), m.group(2)};
        }
        m = SYMBOL_BULLET.matcher(text);
        if (m.matches()) {
            return new String[] {null, m.group(1)};
        }
        m = DASH_BULLET.matcher(text);
        if (m.matches()) {
            return new String[] {null, m.group(1)};
        }
        return null;
    }

    /** Appends a line, joining words hyphenated at the line break. */
    static void join(StringBuilder sb, String next) {
        int len = sb.length();
        if (len == 0) {
            sb.append(next);
            return;
        }
        char last = sb.charAt(len - 1);
        if (last == '­') {
            sb.setLength(len - 1);
            sb.append(next);
            return;
        }
        if (last == '-' && len >= 2 && Character.isLetter(sb.charAt(len - 2))) {
            if (startsWithConjunction(next)) {
                sb.append(' ').append(next); // "Ein- und Ausgaben": the hyphen belongs to the word
                return;
            }
            if (startsLowercase(next)) {
                sb.setLength(len - 1);
            }
            sb.append(next);
            return;
        }
        sb.append(' ').append(next);
    }

    private static boolean startsWithConjunction(String s) {
        int space = s.indexOf(' ');
        String first = (space < 0 ? s : s.substring(0, space)).toLowerCase(Locale.ROOT).replaceAll("[,;:]$", "");
        return CONJUNCTIONS.contains(first);
    }

    private static boolean startsLowercase(String s) {
        return !s.isEmpty() && Character.isLowerCase(s.codePointAt(0));
    }
}
