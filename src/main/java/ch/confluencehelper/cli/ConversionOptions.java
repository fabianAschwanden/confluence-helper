package ch.confluencehelper.cli;

import ch.confluencehelper.convert.ConvertOptions;
import ch.confluencehelper.convert.Converters;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Converter options shared by {@code convert} and {@code import}. */
class ConversionOptions {

    @Option(names = "--sheet", paramLabel = "NAME", description = "Nur dieses Blatt (mehrfach möglich)")
    List<String> sheets = new ArrayList<>();

    @Option(names = "--include-hidden", description = "Auch ausgeblendete Blätter übernehmen")
    boolean includeHidden;

    @Option(names = "--no-header", description = "Erste Zeile nicht als Tabellenkopf verwenden")
    boolean noHeader;

    String convert(Path input, String heading) {
        if (!Files.isRegularFile(input)) {
            throw new UncheckedIOException(new NoSuchFileException(input.toString()));
        }
        try {
            return Converters.forFile(input)
                    .toStorage(input, new ConvertOptions(List.copyOf(sheets), includeHidden, !noHeader, heading));
        } catch (IOException e) {
            throw new UncheckedIOException("Konvertierung von " + input + " fehlgeschlagen: " + e.getMessage(), e);
        }
    }
}
