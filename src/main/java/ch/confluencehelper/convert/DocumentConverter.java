package ch.confluencehelper.convert;

import java.io.IOException;
import java.nio.file.Path;

/** Converts a local document into Confluence storage-format XHTML. */
public interface DocumentConverter {

    String toStorage(Path input, ConvertOptions options) throws IOException;
}
