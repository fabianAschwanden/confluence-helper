package ch.confluencehelper.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageRefsTest {

    @Test
    void plainId() {
        assertThat(PageRefs.parse(" 123456 ")).isEqualTo(PageRef.ofId("123456"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://wiki.firma.ch/pages/viewpage.action?pageId=987",
            "https://firma.ch/confluence/pages/viewpage.action?spaceKey=DOC&pageId=987#anchor",
            "https://wiki.firma.ch/pages/editpage.action?pageId=987",
            "https://wiki.firma.ch/pages/987/Some+Title",
            "https://wiki.firma.ch/pages/987",
            "https://firma.ch/confluence/spaces/DOC/pages/987/Some+Title",
            "https://firma.atlassian.net/wiki/spaces/DOC/pages/987/Some+Title",
            "/spaces/DOC/pages/987"
    })
    void urlsWithId(String url) {
        assertThat(PageRefs.parse(url)).isEqualTo(PageRef.ofId("987"));
    }

    @Test
    void displayUrlDecodesPlusAndPercent() {
        PageRef ref = PageRefs.parse("https://wiki.firma.ch/display/DOC/Title+Mit+Plus");
        assertThat(ref.hasId()).isFalse();
        assertThat(ref.spaceKey()).isEqualTo("DOC");
        assertThat(ref.title()).isEqualTo("Title Mit Plus");

        assertThat(PageRefs.parse("https://firma.ch/confluence/display/DOC/C%2B%2B+%26+%C3%9Cbersicht").title())
                .isEqualTo("C++ & Übersicht");
        assertThat(PageRefs.parse("https://wiki.firma.ch/display/~mmuster/Notizen?focusedCommentId=1#c").spaceKey())
                .isEqualTo("~mmuster");
    }

    @Test
    void displayUrlWithUnencodedUmlaut() {
        assertThat(PageRefs.parse("https://wiki.firma.ch/display/DOC/Über+uns").title()).isEqualTo("Über uns");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "abc",
            "https://wiki.firma.ch/",
            "https://wiki.firma.ch/display/DOC",
            "https://wiki.firma.ch/display/DOC/2024/01/01/Blogpost",
            "https://wiki.firma.ch/x/AbCd",
            "https://wiki.firma.ch/pages/viewpage.action?pageId=abc"
    })
    void rejectsUnknownInput(String input) {
        assertThatThrownBy(() -> PageRefs.parse(input)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> PageRefs.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void urlMustBelongToTheConnectedServer() {
        java.net.URI base = java.net.URI.create("https://wiki.firma.ch/confluence");
        assertThat(PageRefs.parse("https://WIKI.firma.ch:443/confluence/pages/viewpage.action?pageId=7", base).id())
                .isEqualTo("7");
        assertThat(PageRefs.parse("7", base).id()).isEqualTo("7");
        assertThatThrownBy(() -> PageRefs.parse("https://anderes.wiki.ch/pages/viewpage.action?pageId=7", base))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("anderes.wiki.ch")
                .hasMessageContaining("https://wiki.firma.ch/confluence");
        assertThatThrownBy(() -> PageRefs.parse("https://wiki.firma.ch:8443/confluence/pages/7/x", base))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageRefs.parse("https://wiki.firma.ch/wiki/pages/viewpage.action?pageId=7", base))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nicht unter");
    }
}
