package ch.confluencehelper.convert;

/** A table with more than {@code maxCells} cells; {@code sheet} tells an Excel sheet from a CSV file. */
public final class TableTooLargeException extends IllegalArgumentException {

    private final int rows;
    private final int columns;
    private final long maxCells;
    private final boolean sheet;

    TableTooLargeException(int rows, int columns, long maxCells, boolean sheet) {
        super(headline(rows, columns, maxCells) + (sheet
                ? "Belegter Bereich reicht vermutlich bis zu einer weit entfernten Zelle; "
                + "Blatt bereinigen oder mit --sheet einschränken."
                : "Datei kürzen oder aufteilen."));
        this.rows = rows;
        this.columns = columns;
        this.maxCells = maxCells;
        this.sheet = sheet;
    }

    static String headline(int rows, int columns, long maxCells) {
        return "Tabelle zu gross: %d Zeilen × %d Spalten (> %d Zellen). ".formatted(rows, columns, maxCells);
    }

    public String headline() {
        return headline(rows, columns, maxCells);
    }

    public boolean sheet() {
        return sheet;
    }
}
