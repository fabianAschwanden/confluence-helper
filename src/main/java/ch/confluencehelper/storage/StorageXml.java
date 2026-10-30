package ch.confluencehelper.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.ext.DefaultHandler2;

/** Helpers for Confluence storage-format XHTML. */
public final class StorageXml {

    private StorageXml() {
    }

    public static String escapeText(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        appendEscaped(sb, text, false);
        return sb.toString();
    }

    public static String escapeAttr(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        appendEscaped(sb, value, true);
        return sb.toString();
    }

    private static void appendEscaped(StringBuilder sb, String s, boolean attr) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append(attr ? "&quot;" : "\"");
                default -> {
                    // XML 1.0 forbids most control characters; drop them instead of producing invalid XML
                    if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                        continue;
                    }
                    sb.append(c);
                }
            }
        }
    }

    static final String NS_AC = "http://atlassian.com/content";
    static final String NS_RI = "http://atlassian.com/resource/identifier";
    static final String NS_AT = "http://atlassian.com/template";
    private static final String NS_XHTML = "http://www.w3.org/1999/xhtml";
    private static final String ROOT = "cfh-storage-root";

    /** Doctype with the internal entity subset plus the namespace-declaring root start tag; ends with a newline. */
    private static final String PREFIX = "<!DOCTYPE " + ROOT + " [\n" + loadEntities() + "]>\n"
            + "<" + ROOT + " xmlns:ac=\"" + NS_AC + "\" xmlns:ri=\"" + NS_RI + "\" xmlns:at=\"" + NS_AT + "\">\n";
    private static final String SUFFIX = "\n</" + ROOT + ">";
    private static final int PREFIX_LINES = (int) PREFIX.chars().filter(c -> c == '\n').count();

    /** Well-formedness and storage-rule check; empty list means valid. */
    public static List<Problem> validate(String xhtml) {
        Objects.requireNonNull(xhtml, "xhtml");
        Checker checker = new Checker(xhtml);
        try {
            SAXParser parser = newParser();
            parser.setProperty("http://xml.org/sax/properties/lexical-handler", checker);
            parser.parse(new InputSource(new StringReader(PREFIX + xhtml + SUFFIX)), checker);
        } catch (SAXParseException e) {
            // already recorded by fatalError()
        } catch (SAXException | RuntimeException e) {
            // The JDK parser throws a location-less SAXException e.g. for a <!DOCTYPE> inside content.
            checker.problems.add(checker.atLocator("Ungültiges Markup (z.B. <!DOCTYPE> im Inhalt): " + e.getMessage()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(checker.problems);
    }

    /** A reference to an attachment of this page: {@code <ri:attachment ri:filename="…" />} (line/column 1-based). */
    public record AttachmentRef(String filename, int line, int column) {
    }

    /**
     * Attachments of this page that the storage references, in document order, one per file name.
     * References to attachments of other pages (with a nested {@code ri:page}, {@code ri:blog-post} …) are
     * left out. Returns an empty list if the document is not well-formed.
     */
    public static List<AttachmentRef> attachmentRefs(String xhtml) {
        Objects.requireNonNull(xhtml, "xhtml");
        List<AttachmentRef> refs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Checker positions = new Checker(xhtml);
        DefaultHandler2 handler = new DefaultHandler2() {
            private Locator locator;
            private AttachmentRef current;
            private boolean foreign;

            @Override
            public void setDocumentLocator(Locator l) {
                locator = l;
                positions.setDocumentLocator(l);
            }

            @Override
            public InputSource resolveEntity(String name, String publicId, String baseURI, String systemId) {
                return new InputSource(new StringReader(""));
            }

            @Override
            public void startElement(String uri, String localName, String qName, Attributes atts) {
                if (!NS_RI.equals(uri)) {
                    return;
                }
                if ("attachment".equals(localName)) {
                    String name = atts.getValue(NS_RI, "filename");
                    current = name == null || name.isBlank() ? null : new AttachmentRef(name,
                            positions.mapLine(locator.getLineNumber()),
                            positions.mapColumn(locator.getLineNumber(), locator.getColumnNumber()));
                    foreign = false;
                } else if (current != null) {
                    foreign = true; // ri:page, ri:blog-post, ri:content-entity …: attachment of other content
                }
            }

            @Override
            public void endElement(String uri, String localName, String qName) {
                if (NS_RI.equals(uri) && "attachment".equals(localName)) {
                    if (current != null && !foreign && seen.add(current.filename())) {
                        refs.add(current);
                    }
                    current = null;
                }
            }
        };
        try {
            newParser().parse(new InputSource(new StringReader(PREFIX + xhtml + SUFFIX)), handler);
        } catch (SAXException | RuntimeException e) {
            return List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(refs);
    }

    private static SAXParser newParser() throws SAXException {
        try {
            SAXParserFactory f = SAXParserFactory.newInstance();
            f.setNamespaceAware(true);
            f.setValidating(false);
            f.setXIncludeAware(false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            SAXParser parser = f.newSAXParser();
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            // Only our own flat character entities can be declared (the fragment cannot carry a DOCTYPE),
            // so expansion bombs are impossible; lift the JDK limits that large pages with many &nbsp; would hit.
            for (String limit : List.of("jdk.xml.entityExpansionLimit", "jdk.xml.totalEntitySizeLimit",
                    "jdk.xml.entityReplacementLimit")) {
                try {
                    parser.setProperty(limit, "0");
                } catch (SAXException ignored) {
                    // property unknown to this parser implementation
                }
            }
            return parser;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String loadEntities() {
        try (InputStream in = StorageXml.class.getResourceAsStream("xhtml-entities.ent")) {
            if (in == null) {
                throw new IllegalStateException("resource xhtml-entities.ent missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record Open(String qName, int line, int column) {
    }

    private static final class Checker extends DefaultHandler2 {
        private final List<Problem> problems = new ArrayList<>();
        private final String[] inputLines;
        private final Deque<Open> open = new ArrayDeque<>();
        private final Deque<String> macros = new ArrayDeque<>();
        private Locator locator;
        private boolean inCodeBody;
        private boolean bodyHasCdata;
        private boolean bodyHasText;
        private int bodyLine;
        private int bodyColumn;
        private boolean topLayout;
        private int strayLine = -1;
        private int strayColumn;

        Checker(String input) {
            this.inputLines = input.split("\r\n|\r|\n", -1);
        }

        @Override
        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
        }

        @Override
        public InputSource resolveEntity(String name, String publicId, String baseURI, String systemId) {
            // never fetch anything external
            return new InputSource(new StringReader(""));
        }

        @Override
        public InputSource resolveEntity(String publicId, String systemId) {
            return new InputSource(new StringReader(""));
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            int line = mapLine(locator.getLineNumber());
            int col = mapColumn(locator.getLineNumber(), locator.getColumnNumber());
            if (!ROOT.equals(qName)) {
                if (open.isEmpty()) {
                    if (NS_AC.equals(uri) && "layout".equals(localName)) {
                        topLayout = true;
                    } else {
                        stray(line, col);
                    }
                }
                open.push(new Open(qName, line, col));
            }
            if (NS_AC.equals(uri)) {
                switch (localName) {
                    case "structured-macro" -> {
                        String name = atts.getValue(NS_AC, "name");
                        if (name == null || name.isBlank()) {
                            warn(line, col, "<ac:structured-macro> ohne ac:name");
                        }
                        macros.push(name == null ? "" : name.strip());
                    }
                    case "parameter" -> {
                        if (atts.getValue(NS_AC, "name") == null) {
                            warn(line, col, "<ac:parameter> ohne ac:name");
                        }
                    }
                    case "plain-text-body" -> {
                        String macro = macros.peek();
                        if ("code".equals(macro) || "noformat".equals(macro)) {
                            inCodeBody = true;
                            bodyHasCdata = false;
                            bodyHasText = false;
                            bodyLine = line;
                            bodyColumn = col;
                        }
                    }
                    default -> {
                    }
                }
            } else if (NS_RI.equals(uri) && "attachment".equals(localName)) {
                String filename = atts.getValue(NS_RI, "filename");
                if (filename == null || filename.isBlank()) {
                    warn(line, col, "<ri:attachment> ohne ri:filename");
                }
            } else if ("script".equalsIgnoreCase(localName) && (uri.isEmpty() || NS_XHTML.equals(uri))) {
                warn(line, col, "<script> wird von Confluence entfernt bzw. nicht ausgeführt");
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (!ROOT.equals(qName) && !open.isEmpty()) {
                open.pop();
            }
            if (NS_AC.equals(uri)) {
                if ("structured-macro".equals(localName) && !macros.isEmpty()) {
                    macros.pop();
                } else if ("plain-text-body".equals(localName) && inCodeBody) {
                    inCodeBody = false;
                    if (!bodyHasCdata && bodyHasText) {
                        warn(bodyLine, bodyColumn, "<ac:plain-text-body> im " + macros.peek()
                                + "-Makro ohne CDATA-Abschnitt (Inhalt in <![CDATA[ ... ]]> einschliessen)");
                    }
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (open.isEmpty() && strayLine < 0) {
                for (int i = start; i < start + length; i++) {
                    if (!Character.isWhitespace(ch[i])) {
                        stray(mapLine(locator.getLineNumber()), 0);
                        break;
                    }
                }
            }
            if (inCodeBody && !bodyHasText) {
                for (int i = start; i < start + length; i++) {
                    if (!Character.isWhitespace(ch[i])) {
                        bodyHasText = true;
                        break;
                    }
                }
            }
        }

        private void stray(int line, int column) {
            if (strayLine < 0) {
                strayLine = line;
                strayColumn = column;
            }
        }

        /** Confluence expects a page layout as the only top-level element; content beside it breaks the layout. */
        @Override
        public void endDocument() {
            if (topLayout && strayLine >= 0) {
                warn(strayLine, strayColumn, "Inhalt ausserhalb von <ac:layout>: Die Seite hat ein Seitenlayout, "
                        + "jeder Inhalt gehört in eine <ac:layout-cell>");
            }
        }

        @Override
        public void startCDATA() {
            if (inCodeBody) {
                bodyHasCdata = true;
            }
        }

        @Override
        public void warning(SAXParseException e) {
            problems.add(problem(Problem.Severity.WARNING, e));
        }

        @Override
        public void error(SAXParseException e) {
            problems.add(problem(Problem.Severity.ERROR, e));
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            Open unclosed = open.peek();
            String msg = e.getMessage();
            if (unclosed != null && msg != null && msg.contains("\"" + unclosed.qName() + "\"")) {
                // mismatched/unclosed end tag: point at the start tag that was never closed
                int foundLine = mapLine(e.getLineNumber());
                int foundCol = mapColumn(e.getLineNumber(), e.getColumnNumber());
                problems.add(new Problem(Problem.Severity.ERROR, unclosed.line(), unclosed.column(),
                        "Element <%s> wird nicht geschlossen (erkannt bei %d:%d): %s"
                                .formatted(unclosed.qName(), foundLine, foundCol, msg)));
            } else {
                problems.add(problem(Problem.Severity.ERROR, e));
            }
            throw e;
        }

        private Problem problem(Problem.Severity severity, SAXParseException e) {
            return new Problem(severity, mapLine(e.getLineNumber()),
                    mapColumn(e.getLineNumber(), e.getColumnNumber()), e.getMessage());
        }

        Problem atLocator(String message) {
            if (locator == null) {
                return new Problem(Problem.Severity.ERROR, 0, 0, message);
            }
            return new Problem(Problem.Severity.ERROR, mapLine(locator.getLineNumber()),
                    mapColumn(locator.getLineNumber(), locator.getColumnNumber()), message);
        }

        private void warn(int line, int column, String message) {
            problems.add(new Problem(Problem.Severity.WARNING, line, column, message));
        }

        /** Maps a line of the wrapped document to the original input (clamped into its range). */
        int mapLine(int wrappedLine) {
            if (wrappedLine <= 0) {
                return 0;
            }
            return Math.max(1, Math.min(wrappedLine - PREFIX_LINES, inputLines.length));
        }

        int mapColumn(int wrappedLine, int wrappedColumn) {
            if (wrappedLine <= 0 || wrappedColumn <= 0) {
                return 0;
            }
            int rel = wrappedLine - PREFIX_LINES;
            if (rel < 1) {
                return 1;
            }
            if (rel > inputLines.length) {
                // inside the wrapper's closing tag: report the end of the input
                return inputLines[inputLines.length - 1].length() + 1;
            }
            return wrappedColumn;
        }
    }
}
