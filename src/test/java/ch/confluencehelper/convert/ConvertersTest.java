package ch.confluencehelper.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ConvertersTest {

    @Test
    void picksConverterByExtensionIgnoringCase() {
        assertThat(Converters.forFile(Path.of("dir/Bericht.PDF"))).isInstanceOf(PdfConverter.class);
        assertThat(Converters.forFile(Path.of("a.xlsx"))).isInstanceOf(ExcelConverter.class);
        assertThat(Converters.forFile(Path.of("a.XLS"))).isInstanceOf(ExcelConverter.class);
        assertThat(Converters.forFile(Path.of("a.xlsm"))).isInstanceOf(ExcelConverter.class);
        assertThat(Converters.forFile(Path.of("a.b.csv"))).isInstanceOf(CsvConverter.class);
        assertThat(Converters.forFile(Path.of("a.Tsv"))).isInstanceOf(CsvConverter.class);
    }

    @Test
    void rejectsUnknownExtensions() {
        assertThatThrownBy(() -> Converters.forFile(Path.of("brief.docx")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nicht unterstütztes Dateiformat")
                .hasMessageContaining("brief.docx");
        assertThatThrownBy(() -> Converters.forFile(Path.of("ohne-endung")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Converters.forFile(Path.of("csv")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
