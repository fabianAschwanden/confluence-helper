package ch.confluencehelper.convert;

import static ch.confluencehelper.storage.StorageXml.escapeText;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

/**
 * Converts the text layer of a PDF into storage XHTML. Structure (headings, paragraphs, lists) is
 * reconstructed heuristically from font sizes and line positions; images are ignored, no OCR.
 */
public class PdfConverter implements DocumentConverter {

    static {
        LibraryLogging.silence();
    }

    static final String NO_TEXT_MESSAGE =
            "Im PDF wurde kein Text gefunden (vermutlich ein gescanntes Dokument). Texterkennung (OCR) wird nicht unterstützt.";

    @Override
    public String toStorage(Path input, ConvertOptions options) throws IOException {
        ConvertOptions opts = options == null ? ConvertOptions.defaults() : options;
        List<PdfLine> lines;
        int pages;
        try (PDDocument document = Loader.loadPDF(input.toFile())) {
            pages = document.getNumberOfPages();
            lines = new PdfLineStripper().extract(document);
        } catch (InvalidPasswordException e) {
            throw new IOException("Das PDF ist passwortgeschützt und kann nicht gelesen werden: " + input, e);
        }

        StringBuilder out = new StringBuilder();
        boolean hasTitle = opts.title() != null && !opts.title().isBlank();
        if (hasTitle) {
            out.append("<h1>").append(escapeText(opts.title().strip())).append("</h1>\n");
        }
        if (lines.isEmpty()) {
            System.err.println("Warnung: " + input.getFileName() + " enthält keinen Text (gescanntes PDF?). OCR wird nicht unterstützt.");
            out.append("<ac:structured-macro ac:name=\"info\">\n<ac:rich-text-body>\n<p>")
                    .append(escapeText(NO_TEXT_MESSAGE))
                    .append("</p>\n</ac:rich-text-body>\n</ac:structured-macro>\n");
            return out.toString();
        }

        for (PdfLayout.Block block : PdfLayout.layout(lines, pages)) {
            switch (block) {
                case PdfLayout.Heading h -> {
                    // keep a single h1 when a title is given; shifting keeps the hierarchy (h2–h4)
                    int level = Math.min(6, h.level() + (hasTitle ? 1 : 0));
                    out.append("<h").append(level).append('>').append(escapeText(h.text()))
                            .append("</h").append(level).append(">\n");
                }
                case PdfLayout.Paragraph p -> out.append("<p>").append(escapeText(p.text())).append("</p>\n");
                case PdfLayout.ListBlock l -> {
                    String tag = l.ordered() ? "ol" : "ul";
                    out.append('<').append(tag).append(">\n");
                    for (String item : l.items()) {
                        out.append("<li>").append(escapeText(item)).append("</li>\n");
                    }
                    out.append("</").append(tag).append(">\n");
                }
            }
        }
        return out.toString();
    }
}
