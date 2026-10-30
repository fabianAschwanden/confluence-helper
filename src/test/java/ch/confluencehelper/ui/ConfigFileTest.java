package ch.confluencehelper.ui;

import ch.confluencehelper.config.ConfigException;
import ch.confluencehelper.config.ConfigFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link ConfigFile} lives in the config package; tested here because it serves the UI. */
class ConfigFileTest {

    @TempDir
    Path tmp;

    @Test
    void updateKeepsLayoutAndHandlesContinuationsAndDuplicates() throws Exception {
        Path f = tmp.resolve("a/b/config.properties");
        Files.createDirectories(f.getParent());
        Files.writeString(f, "! Kopf\n\nurl = https://alt \\\n  .example\nnotiz: bleibt\ntoken=a\ntoken=b\n");

        ConfigFile.update(f, Map.of("url", "https://neu.example", "x.url", "https://x#1"), Set.of("token"));

        assertThat(Files.readString(f)).isEqualTo("! Kopf\n\nurl=https://neu.example\nnotiz: bleibt\nx.url=https://x#1\n");
        Properties p = ConfigFile.read(f);
        assertThat(p.getProperty("x.url")).isEqualTo("https://x#1");
        assertThat(ConfigFile.profiles(p)).containsExactly("x");
        assertThat(Files.getPosixFilePermissions(f)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void valuesWithSpecialCharactersRoundTrip() {
        Path f = tmp.resolve("c.properties");
        String value = " führend\\mit=Zeichen:#!\n";
        ConfigFile.update(f, Map.of("k", value), Set.of());
        assertThat(ConfigFile.read(f).getProperty("k")).isEqualTo(value);
    }

    @Test
    void saveConnectionValidatesBeforeWriting() {
        Path f = tmp.resolve("d.properties");
        assertThatThrownBy(() -> ConfigFile.saveConnection(f, "x y", "https://a.example", "t", null, null))
                .isInstanceOf(ConfigException.class).hasMessageContaining("Profilname");
        assertThatThrownBy(() -> ConfigFile.saveConnection(f, null, "https://a.example", "", null, null))
                .isInstanceOf(ConfigException.class).hasMessageContaining("Token");
        assertThatThrownBy(() -> ConfigFile.saveConnection(f, null, "https://a.example", "to ken", null, null))
                .isInstanceOf(ConfigException.class);
        assertThat(f).doesNotExist();

        ConfigFile.saveConnection(f, null, "https://a.example/", "​tok ", null, null);
        assertThat(ConfigFile.read(f).getProperty("token")).isEqualTo("tok");
        assertThat(ConfigFile.read(f).getProperty("url")).isEqualTo("https://a.example");
    }

    @Test
    void storedSecretIsOnlyKeptForTheSameServer() throws Exception {
        Path f = tmp.resolve("e.properties");
        String original = "url=https://wiki.firma.ch\ntoken=PROD\nstaging.url=https://staging.firma.ch\n"
                + "staging.username=anna\nstaging.password=pw\n";
        Files.writeString(f, original);

        // other host, other port, other scheme: the stored token must not travel there
        for (String url : List.of("https://wiki.firma.co", "https://wiki.firma.ch:8443", "http://wiki.firma.ch")) {
            assertThatThrownBy(() -> ConfigFile.saveConnection(f, null, url, "", null, null))
                    .isInstanceOf(ConfigException.class).hasMessageContaining("Token neu eingeben");
            assertThatThrownBy(() -> ConfigFile.resolveConnection(f, null, url, null, null, null))
                    .isInstanceOf(ConfigException.class);
        }
        assertThatThrownBy(() -> ConfigFile.saveConnection(f, "staging", "https://wiki.firma.ch", null, "anna", ""))
                .isInstanceOf(ConfigException.class).hasMessageContaining("Passwort neu eingeben");
        assertThat(Files.readString(f)).isEqualTo(original);

        // same server (other context path, explicit default port): kept
        assertThat(ConfigFile.resolveConnection(f, null, "https://WIKI.firma.ch:443/confluence", "", null, null).token())
                .isEqualTo("PROD");
        assertThat(Files.readString(f)).isEqualTo(original);
        ConfigFile.saveConnection(f, null, "https://wiki.firma.ch/confluence", "", null, null);
        assertThat(ConfigFile.read(f).getProperty("token")).isEqualTo("PROD");
        // a new secret for a new host is fine
        ConfigFile.saveConnection(f, "staging", "https://test.firma.ch", null, "anna", "neu");
        assertThat(ConfigFile.read(f).getProperty("staging.password")).isEqualTo("neu");
    }

    @Test
    void commentEndingInBackslashDoesNotSwallowTheNextLine() throws Exception {
        Path f = tmp.resolve("g.properties");
        Files.writeString(f, "# Ablage: C:\\Daten\\\ntoken=ALT\nurl=https://wiki.firma.ch\n");
        ConfigFile.saveConnection(f, null, "https://wiki.firma.ch", null, "anna", "pw");
        Properties p = ConfigFile.read(f);
        assertThat(p.getProperty("token")).isNull();
        assertThat(p.getProperty("username")).isEqualTo("anna");
        assertThat(Files.readString(f)).startsWith("# Ablage: C:\\Daten\\\n");
    }
}
