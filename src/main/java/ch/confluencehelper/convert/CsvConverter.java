package ch.confluencehelper.convert;

import static ch.confluencehelper.storage.StorageXml.escapeText;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Converts CSV/TSV into a single storage table (no {@code <h2>}, a CSV has no sheets; the sheets filter is ignored).
 * Encoding: BOM (UTF-8/UTF-16), else UTF-8, falling back to windows-1252 if the bytes are not valid UTF-8.
 * Delimiter: {@code .tsv} = tab, an Excel {@code sep=X} first line, else detected among tab, {@code ;} and {@code ,}.
 */
public class CsvConverter implements DocumentConverter {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private static final char[] CANDIDATES = {'\t', ';', ','};
    private static final int SAMPLE_RECORDS = 20;

    @Override
    public String toStorage(Path input, ConvertOptions options) throws IOException {
        String text = decode(Files.readAllBytes(input));
        char delimiter;
        String fileName = input.getFileName() == null ? "" : input.getFileName().toString();
        String firstLine = text.lines().findFirst().orElse("");
        if (firstLine.length() == 5 && firstLine.regionMatches(true, 0, "sep=", 0, 4)) {
            delimiter = firstLine.charAt(4);
            text = text.substring(firstLine.length()).replaceFirst("^(\r\n|\r|\n)", "");
        } else if (fileName.toLowerCase(Locale.ROOT).endsWith(".tsv")) {
            delimiter = '\t';
        } else {
            delimiter = detectDelimiter(text);
        }

        TableWriter table = new TableWriter(false);
        List<List<String>> records = parse(text, delimiter);
        for (int r = 0; r < records.size(); r++) {
            List<String> fields = records.get(r);
            for (int c = 0; c < fields.size(); c++) {
                table.put(r, c, fields.get(c), null);
            }
        }

        StringBuilder out = new StringBuilder();
        if (options.title() != null && !options.title().isBlank()) {
            out.append("<h1>").append(escapeText(options.title().strip())).append("</h1>\n");
        }
        if (table.isEmpty()) {
            out.append("<p>Keine Daten gefunden.</p>\n");
        } else {
            table.write(out, options.headerRow());
        }
        return out.toString();
    }

    static String decode(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xEF && (bytes[1] & 0xff) == 0xBB && (bytes[2] & 0xff) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xFF && (bytes[1] & 0xff) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xFE && (bytes[1] & 0xff) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            // typical for CSV saved by Excel on Swiss/German Windows
            return new String(bytes, WINDOWS_1252);
        }
    }

    /**
     * Counts candidate delimiters outside quotes per record in the first records. A delimiter that occurs equally
     * often (and at least once) in every sampled record wins, in the order tab, semicolon, comma (so "1,5;2,3" with
     * decimal commas yields ';'). Otherwise the most frequent candidate wins; default ','.
     */
    static char detectDelimiter(String text) {
        List<int[]> counts = new ArrayList<>();
        int[] current = new int[CANDIDATES.length];
        boolean inQuotes = false, recordHasContent = false;
        for (int i = 0; i < text.length() && counts.size() < SAMPLE_RECORDS; i++) {
            char ch = text.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
                recordHasContent = true;
            } else if (!inQuotes && (ch == '\n' || ch == '\r')) {
                if (recordHasContent) {
                    counts.add(current);
                }
                current = new int[CANDIDATES.length];
                recordHasContent = false;
            } else {
                recordHasContent = true;
                if (!inQuotes) {
                    for (int k = 0; k < CANDIDATES.length; k++) {
                        if (ch == CANDIDATES[k]) {
                            current[k]++;
                        }
                    }
                }
            }
        }
        if (recordHasContent && counts.size() < SAMPLE_RECORDS) {
            counts.add(current);
        }
        if (counts.isEmpty()) {
            return ',';
        }
        for (int k = 0; k < CANDIDATES.length; k++) {
            int first = counts.get(0)[k];
            boolean consistent = first > 0;
            for (int[] c : counts) {
                consistent &= c[k] == first;
            }
            if (consistent) {
                return CANDIDATES[k];
            }
        }
        int best = -1;
        long bestTotal = 0;
        for (int k = 0; k < CANDIDATES.length; k++) {
            long total = 0;
            for (int[] c : counts) {
                total += c[k];
            }
            if (total > bestTotal) {
                bestTotal = total;
                best = k;
            }
        }
        return best < 0 ? ',' : CANDIDATES[best];
    }

    /** RFC 4180 parser, lenient towards stray quotes; accepts CRLF, LF and CR line endings. */
    static List<List<String>> parse(String text, char delimiter) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false, fieldStarted = false;
        int i = 0, n = text.length();
        while (i < n) {
            char ch = text.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"' && !fieldStarted) {
                inQuotes = true;
                fieldStarted = true;
            } else if (ch == delimiter) {
                record.add(field.toString());
                field.setLength(0);
                fieldStarted = false;
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
                record.add(field.toString());
                records.add(record);
                record = new ArrayList<>();
                field.setLength(0);
                fieldStarted = false;
            } else {
                field.append(ch);
                fieldStarted = true;
            }
            i++;
        }
        if (fieldStarted || !record.isEmpty() || field.length() > 0) {
            record.add(field.toString());
            records.add(record);
        }
        return records;
    }
}
