package ch.confluencehelper.convert;

/**
 * One visual text line of a PDF page.
 *
 * @param page 1-based page number
 * @param text normalized line text (single spaces, trimmed)
 * @param size dominant font size in pt (by character count, rounded to 0.5)
 * @param bold whether (nearly) all characters use a bold font
 * @param x    left edge of the first character
 * @param y    baseline, measured from the top of the page
 * @param pageHeight height of the page (0 if unknown)
 */
record PdfLine(int page, String text, float size, boolean bold, float x, float y, float pageHeight) {
}
