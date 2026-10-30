package ch.confluencehelper.convert;

import static ch.confluencehelper.storage.StorageXml.escapeText;

import java.io.IOException;
import java.nio.file.Path;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;

/**
 * Converts .xlsx/.xls/.xlsm workbooks: per sheet an {@code <h2>} with the sheet name followed by a table.
 * Empty sheets are skipped; if nothing is left, a short note paragraph is emitted.
 */
public class ExcelConverter implements DocumentConverter {

    static {
        LibraryLogging.silence();
    }

    private final Locale locale;

    public ExcelConverter() {
        this(Locale.getDefault());
    }

    /** @param locale locale for number/date formatting (decimal and grouping separators, month names) */
    public ExcelConverter(Locale locale) {
        this.locale = Objects.requireNonNull(locale);
    }

    @Override
    public String toStorage(Path input, ConvertOptions options) throws IOException {
        try (Workbook wb = open(input)) {
            List<Sheet> sheets = selectSheets(wb, options);
            DataFormatter formatter = new DataFormatter(locale);
            DataFormatter cachedFormatter = new DataFormatter(locale);
            cachedFormatter.setUseCachedValuesForFormulaCells(true);
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();

            StringBuilder out = new StringBuilder();
            if (options.title() != null && !options.title().isBlank()) {
                out.append("<h1>").append(escapeText(options.title().strip())).append("</h1>\n");
            }
            boolean any = false;
            for (Sheet sheet : sheets) {
                TableWriter table = readSheet(sheet, formatter, cachedFormatter, evaluator, options.includeHidden());
                if (table.isEmpty()) {
                    continue;
                }
                any = true;
                out.append("<h2>").append(escapeText(sheet.getSheetName())).append("</h2>\n");
                table.write(out, options.headerRow());
            }
            if (!any) {
                out.append("<p>Keine Daten gefunden.</p>\n");
            }
            return out.toString();
        }
    }

    /** A sheet of a workbook; {@code hidden} also covers "very hidden" sheets. */
    public record SheetInfo(String name, boolean hidden) {
    }

    /** Lists the sheets of a workbook in their order, e.g. to let a user pick before converting. */
    public static List<SheetInfo> sheets(Path input) throws IOException {
        try (Workbook wb = open(input)) {
            List<SheetInfo> result = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                result.add(new SheetInfo(wb.getSheetName(i), wb.getSheetVisibility(i) != SheetVisibility.VISIBLE));
            }
            return result;
        }
    }

    private static Workbook open(Path input) throws IOException {
        try {
            return WorkbookFactory.create(input.toFile(), null, true);
        } catch (EncryptedDocumentException e) {
            throw new IOException("Excel-Datei ist passwortgeschützt: " + input, e);
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Keine gültige Excel-Datei: " + input + " (" + e.getMessage() + ")", e);
        }
    }

    private static List<Sheet> selectSheets(Workbook wb, ConvertOptions options) {
        List<Sheet> result = new ArrayList<>();
        List<String> wanted = options.sheets() == null ? List.of() : options.sheets();
        if (wanted.isEmpty()) {
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                if (options.includeHidden() || wb.getSheetVisibility(i) == SheetVisibility.VISIBLE) {
                    result.add(wb.getSheetAt(i));
                }
            }
            return result;
        }
        // explicitly requested sheets are converted in the requested order, even when hidden
        for (String name : wanted) {
            Sheet sheet = wb.getSheet(name);
            if (sheet == null) {
                for (int i = 0; i < wb.getNumberOfSheets() && sheet == null; i++) {
                    if (wb.getSheetName(i).equalsIgnoreCase(name.strip())) {
                        sheet = wb.getSheetAt(i);
                    }
                }
            }
            if (sheet == null) {
                List<String> available = new ArrayList<>();
                for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                    available.add(wb.getSheetName(i));
                }
                throw new IllegalArgumentException("Blatt «" + name + "» nicht gefunden. Vorhandene Blätter: "
                        + String.join(", ", available));
            }
            if (!result.contains(sheet)) {
                result.add(sheet);
            }
        }
        return result;
    }

    /**
     * Reads the cells of a sheet. Hidden rows and columns are left out (unless {@code includeHidden}),
     * and the remaining ones are renumbered so that no empty gap is left in the table.
     */
    private TableWriter readSheet(Sheet sheet, DataFormatter formatter, DataFormatter cachedFormatter,
                                  FormulaEvaluator evaluator, boolean includeHidden) {
        TableWriter table = new TableWriter(true);
        TreeSet<Integer> hiddenRows = new TreeSet<>();
        TreeSet<Integer> hiddenCols = new TreeSet<>();
        if (!includeHidden) {
            int maxCol = 0;
            for (Row row : sheet) {
                if (row.getZeroHeight()) {
                    hiddenRows.add(row.getRowNum());
                }
                maxCol = Math.max(maxCol, row.getLastCellNum());
            }
            for (int c = 0; c < maxCol; c++) {
                if (sheet.isColumnHidden(c)) {
                    hiddenCols.add(c);
                }
            }
        }
        int[] rowGaps = hiddenRows.stream().mapToInt(Integer::intValue).toArray();
        int[] colGaps = hiddenCols.stream().mapToInt(Integer::intValue).toArray();
        for (Row row : sheet) {
            if (hiddenRows.contains(row.getRowNum())) {
                continue;
            }
            for (Cell cell : row) {
                if (hiddenCols.contains(cell.getColumnIndex())) {
                    continue;
                }
                table.put(visible(cell.getRowIndex(), rowGaps), visible(cell.getColumnIndex(), colGaps),
                        format(cell, formatter, cachedFormatter, evaluator), href(cell));
            }
        }
        for (CellRangeAddress region : sheet.getMergedRegions()) {
            int firstRow = firstVisible(region.getFirstRow(), region.getLastRow(), hiddenRows);
            int firstCol = firstVisible(region.getFirstColumn(), region.getLastColumn(), hiddenCols);
            if (firstRow < 0 || firstCol < 0 || hiddenRows.contains(region.getFirstRow())
                    || hiddenCols.contains(region.getFirstColumn())) {
                continue; // anchor (holding the content) is hidden
            }
            table.merge(visible(firstRow, rowGaps), visible(firstCol, colGaps),
                    visible(lastVisible(region.getFirstRow(), region.getLastRow(), hiddenRows), rowGaps),
                    visible(lastVisible(region.getFirstColumn(), region.getLastColumn(), hiddenCols), colGaps));
        }
        return table;
    }

    /** Index after removing the hidden indices before it. */
    private static int visible(int index, int[] hidden) {
        int pos = Arrays.binarySearch(hidden, index);
        return index - (pos >= 0 ? pos : -pos - 1);
    }

    private static int firstVisible(int from, int to, TreeSet<Integer> hidden) {
        for (int i = from; i <= to; i++) {
            if (!hidden.contains(i)) {
                return i;
            }
        }
        return -1;
    }

    private static int lastVisible(int from, int to, TreeSet<Integer> hidden) {
        for (int i = to; i >= from; i--) {
            if (!hidden.contains(i)) {
                return i;
            }
        }
        return -1;
    }

    private String format(Cell cell, DataFormatter formatter, DataFormatter cachedFormatter,
                          FormulaEvaluator evaluator) {
        if (cell.getCellType() != CellType.FORMULA) {
            String date = systemShortDate(cell,
                    cell.getCellType() == CellType.NUMERIC ? cell.getNumericCellValue() : null);
            return date != null ? date : formatter.formatCellValue(cell);
        }
        try {
            CellValue value = evaluator.evaluate(cell);
            String date = systemShortDate(cell, value != null && value.getCellType() == CellType.NUMERIC
                    ? value.getNumberValue() : null);
            return date != null ? date : formatter.formatCellValue(cell, evaluator);
        } catch (RuntimeException e) {
            // unsupported function, external reference, …: use the result Excel stored in the file
            String date = systemShortDate(cell, cell.getCachedFormulaResultType() == CellType.NUMERIC
                    ? cell.getNumericCellValue() : null);
            return date != null ? date : cachedFormatter.formatCellValue(cell);
        }
    }

    /**
     * Excel formats that follow the operating system's regional settings and that POI would always
     * render US-style: built-in 14 (short date, 15.03.2024 in de-CH), built-in 22 (short date + time),
     * {@code [$-F800]} (long date, "Sonntag, 15. März 2026") and {@code [$-F400]} (time, "14:30:00").
     * They are formatted with the converter's locale here; null for any other format.
     */
    private String systemShortDate(Cell cell, Double value) {
        if (value == null || cell.getCellStyle() == null || !DateUtil.isValidExcelDate(value)) {
            return null;
        }
        short index = cell.getCellStyle().getDataFormat();
        String fmt = cell.getCellStyle().getDataFormatString();
        String upper = fmt == null ? "" : fmt.toUpperCase(Locale.ROOT);
        String pattern;
        if (index == 14) {
            pattern = shortDatePattern();
        } else if (index == 22) {
            pattern = shortDatePattern() + " HH:mm";
        } else if (upper.startsWith("[$-F800]")) {
            pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                    FormatStyle.FULL, null, IsoChronology.INSTANCE, locale);
        } else if (upper.startsWith("[$-F400]")) {
            pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                    null, FormatStyle.MEDIUM, IsoChronology.INSTANCE, locale);
        } else {
            return null;
        }
        return DateUtil.getLocalDateTime(value).format(DateTimeFormatter.ofPattern(pattern, locale));
    }

    private String shortDatePattern() {
        return DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale).replaceAll("y+", "yyyy");
    }

    private static String href(Cell cell) {
        Hyperlink link = cell.getHyperlink();
        if (link == null || link.getAddress() == null) {
            return hyperlinkFormula(cell);
        }
        String address = link.getAddress().strip();
        HyperlinkType type = link.getType();
        if (type == HyperlinkType.EMAIL && !address.regionMatches(true, 0, "mailto:", 0, 7)) {
            address = "mailto:" + address;
        }
        if (type != HyperlinkType.URL && type != HyperlinkType.EMAIL && type != HyperlinkType.FILE) {
            return null; // links into the workbook itself have no meaning on the wiki page
        }
        return safeUrl(address);
    }

    private static final Pattern HYPERLINK_FORMULA = Pattern.compile("(?is)^\\s*HYPERLINK\\s*\\((.*)\\)\\s*$");
    private static final Pattern STRING_LITERAL = Pattern.compile("^\\s*\"((?:[^\"]|\"\")*)\"");
    private static final Pattern CELL_REFERENCE = Pattern.compile("^\\s*(\\$?[A-Za-z]{1,3}\\$?\\d{1,7})\\s*(?:[,;)]|$)");

    /**
     * URL of a {@code =HYPERLINK("url", "name")} formula; such links have no Hyperlink object. The first
     * argument may be a string literal or a reference to a cell on the same sheet.
     */
    private static String hyperlinkFormula(Cell cell) {
        if (cell.getCellType() != CellType.FORMULA) {
            return null;
        }
        String formula;
        try {
            formula = cell.getCellFormula();
        } catch (RuntimeException e) {
            return null;
        }
        Matcher m = HYPERLINK_FORMULA.matcher(formula == null ? "" : formula);
        if (!m.matches()) {
            return null;
        }
        String args = m.group(1);
        Matcher lit = STRING_LITERAL.matcher(args);
        if (lit.find()) {
            return safeUrl(lit.group(1).replace("\"\"", "\"").strip());
        }
        Matcher ref = CELL_REFERENCE.matcher(args);
        if (ref.find()) {
            CellReference cr = new CellReference(ref.group(1));
            Row row = cell.getSheet().getRow(cr.getRow());
            Cell target = row == null ? null : row.getCell(cr.getCol());
            if (target != null && target.getCellType() == CellType.STRING) {
                return safeUrl(target.getStringCellValue().strip());
            }
        }
        return null;
    }

    /** Only web and mail links are allowed on the page (no javascript:, file:, …). */
    private static String safeUrl(String address) {
        String lower = address.toLowerCase(Locale.ROOT);
        boolean safe = lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("mailto:") || lower.startsWith("ftp://");
        return safe ? address : null;
    }
}
