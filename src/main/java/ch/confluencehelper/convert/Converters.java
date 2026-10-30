package ch.confluencehelper.convert;

import java.nio.file.Path;
import java.util.Locale;

public final class Converters {

    private Converters() {
    }

    /** Picks the converter by file extension (.pdf, .xlsx, .xls, .xlsm, .csv, .tsv, case-insensitive). */
    public static DocumentConverter forFile(Path file) {
        Path name = file.getFileName();
        String n = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
        String ext = n.lastIndexOf('.') < 0 ? "" : n.substring(n.lastIndexOf('.') + 1);
        return switch (ext) {
            case "pdf" -> new PdfConverter();
            case "xlsx", "xls", "xlsm" -> new ExcelConverter();
            case "csv", "tsv" -> new CsvConverter();
            default -> throw new IllegalArgumentException("Nicht unterstütztes Dateiformat: " + file
                    + " (unterstützt: .pdf, .xlsx, .xls, .xlsm, .csv, .tsv)");
        };
    }
}
