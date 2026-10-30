package ch.confluencehelper.convert;

import java.util.List;

/**
 * @param sheets        sheet names to include (empty = all visible sheets)
 * @param includeHidden also convert hidden sheets
 * @param headerRow     treat the first non-empty row as table header
 * @param title         optional h1 heading prepended to the output
 */
public record ConvertOptions(List<String> sheets, boolean includeHidden, boolean headerRow, String title) {

    public static ConvertOptions defaults() {
        return new ConvertOptions(List.of(), false, true, null);
    }
}
