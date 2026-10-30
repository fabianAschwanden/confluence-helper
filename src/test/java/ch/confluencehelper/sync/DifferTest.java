package ch.confluencehelper.sync;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DifferTest {

    @Test
    void lineEndingsAreIgnored() {
        assertThat(Differ.sameContent("<p>a</p>\r\n<p>b</p>", "<p>a</p>\n<p>b</p>")).isTrue();
        assertThat(Differ.unified("a\r\nb", "a\nb", "x", "y")).isEmpty();
        assertThat(Differ.sameContent(null, "")).isTrue();
    }

    @Test
    void unifiedDiffAndStats() {
        String diff = Differ.unified("a\nb\nc", "a\nB\nc\nd", "server", "lokal");
        assertThat(diff).startsWith("--- server\n+++ lokal\n").contains("-b\n").contains("+B\n").contains("+d");
        assertThat(Differ.stats("a\nb\nc", "a\nB\nc\nd")).isEqualTo(new Differ.Stats(2, 1));
        assertThat(Differ.stats("x", "x").isEmpty()).isTrue();
    }
}
