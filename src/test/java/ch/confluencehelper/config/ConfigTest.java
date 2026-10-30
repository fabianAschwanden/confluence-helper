package ch.confluencehelper.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ConfigTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    private String warnings() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    private Path file(String content) throws IOException {
        Path f = dir.resolve("config.properties");
        Files.writeString(f, content, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX
        }
        return f;
    }

    private Path missing() {
        return dir.resolve("nope.properties");
    }

    @Test
    void fromEnvironmentOnly() {
        Config c = Config.load(null, null,
                Map.of("CONFLUENCE_URL", "https://wiki.firma.ch/", "CONFLUENCE_TOKEN", "secret"), missing(), err);
        assertThat(c.baseUrl()).isEqualTo(URI.create("https://wiki.firma.ch"));
        assertThat(c.token()).isEqualTo("secret");
        assertThat(c.usesToken()).isTrue();
        assertThat(warnings()).isEmpty();
    }

    @Test
    void fromFileWithContextPath() throws IOException {
        Path f = file("url=https://firma.ch/confluence/\ntoken=t1\n");
        Config c = Config.load(null, null, Map.of(), f, err);
        assertThat(c.baseUrl()).isEqualTo(URI.create("https://firma.ch/confluence"));
        assertThat(c.token()).isEqualTo("t1");
    }

    @Test
    void environmentBeatsFileAndOverrideBeatsBoth() throws IOException {
        Path f = file("url=https://file.example\ntoken=filetoken\n");
        Config c = Config.load(null, null, Map.of("CONFLUENCE_URL", "https://env.example"), f, err);
        assertThat(c.baseUrl()).hasHost("env.example");
        assertThat(c.token()).isEqualTo("filetoken");

        Config o = Config.load("https://cli.example", null, Map.of("CONFLUENCE_URL", "https://env.example"), f, err);
        assertThat(o.baseUrl()).hasHost("cli.example");
    }

    @Test
    void basicAuthFallback() throws IOException {
        Path f = file("url=https://wiki.example\nusername=mmuster\npassword=geheim\n");
        Config c = Config.load(null, null, Map.of(), f, err);
        assertThat(c.token()).isNull();
        assertThat(c.usesToken()).isFalse();
        assertThat(c.username()).isEqualTo("mmuster");
        assertThat(c.password()).isEqualTo("geheim");
    }

    @Test
    void profileUsesPrefixedKeysAndIgnoresEnvironment() throws IOException {
        Path f = file("""
                url=https://default.example
                token=default
                test.url=https://test.example/wiki
                test.token=testtoken
                """);
        Config c = Config.load(null, "test",
                Map.of("CONFLUENCE_URL", "https://env.example", "CONFLUENCE_TOKEN", "envtoken"), f, err);
        assertThat(c.baseUrl()).isEqualTo(URI.create("https://test.example/wiki"));
        assertThat(c.token()).isEqualTo("testtoken");

        Config noProfile = Config.load(null, null, Map.of(), f, err);
        assertThat(noProfile.token()).isEqualTo("default");
    }

    @Test
    void unknownProfileFails() throws IOException {
        Path f = file("url=https://default.example\ntoken=x\n");
        assertThatThrownBy(() -> Config.load(null, "prod", Map.of(), f, err))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Profil 'prod' nicht gefunden");
        assertThatThrownBy(() -> Config.load(null, "prod", Map.of(), missing(), err))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Konfigurationsdatei fehlt");
    }

    @Test
    void missingUrlOrCredentialsFail() {
        assertThatThrownBy(() -> Config.load(null, null, Map.of("CONFLUENCE_TOKEN", "t"), missing(), err))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Keine Confluence-URL");
        assertThatThrownBy(() -> Config.load(null, null,
                Map.of("CONFLUENCE_URL", "https://x.example", "CONFLUENCE_USER", "u"), missing(), err))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Keine Zugangsdaten");
    }

    @Test
    void invalidUrlFails() {
        assertThatThrownBy(() -> Config.load("ftp://x.example", null, Map.of("CONFLUENCE_TOKEN", "t"), missing(), err))
                .isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> Config.load("wiki.example", null, Map.of("CONFLUENCE_TOKEN", "t"), missing(), err))
                .isInstanceOf(ConfigException.class);
    }

    @Test
    void plainHttpWarns() {
        Config.load("http://wiki.example", null, Map.of("CONFLUENCE_TOKEN", "t"), missing(), err);
        assertThat(warnings()).contains("ohne HTTPS");
    }

    @Test
    void cfhConfigEnvSelectsFile() throws IOException {
        Path f = file("url=https://custom.example\ntoken=t\n");
        Config c = Config.load(null, null, Map.of("CFH_CONFIG", f.toString()), null, err);
        assertThat(c.baseUrl()).hasHost("custom.example");
    }

    @Test
    void warnsWhenFileReadableByOthers() throws IOException {
        Path f = file("url=https://wiki.example\ntoken=t\n");
        try {
            Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "POSIX permissions not supported");
        }
        Config.load(null, null, Map.of(), f, err);
        assertThat(warnings()).contains("für andere Benutzer lesbar").contains("chmod 600");
    }

    @Test
    void noWarningForPrivateFile() throws IOException {
        Path f = file("url=https://wiki.example\ntoken=t\n");
        Config.load(null, null, Map.of(), f, err);
        assertThat(warnings()).isEmpty();
    }

    @Test
    void toStringMasksSecrets() {
        Config c = new Config(URI.create("https://wiki.example"), "supersecrettoken", "mmuster", "geheimpasswort");
        assertThat(c.toString())
                .contains("https://wiki.example", "mmuster", "****")
                .doesNotContain("supersecrettoken", "geheimpasswort");
        assertThat(new Config(URI.create("https://wiki.example"), null, null, null).toString())
                .contains("token=null");
    }

    @Test
    void invisibleCharactersAroundTheTokenAreRemoved() {
        for (String raw : new String[] {"secret\u00a0", "\u200bsecret", "\ufeffsecret\u202f\n"}) {
            Config c = Config.load(null, null,
                    Map.of("CONFLUENCE_URL", "https://wiki.firma.ch", "CONFLUENCE_TOKEN", raw), missing(), err);
            assertThat(c.token()).isEqualTo("secret");
        }
    }

    @Test
    void tokenWithInvalidCharactersIsRejectedWithoutEchoingIt() {
        assertThatThrownBy(() -> Config.load(null, null,
                Map.of("CONFLUENCE_URL", "https://wiki.firma.ch", "CONFLUENCE_TOKEN", "TOPSECRET\u00a0TOKEN"),
                missing(), err))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("ungültige Zeichen")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("TOPSECRET"));
        assertThatThrownBy(() -> new Config(URI.create("https://wiki.example"), null, "max", "geheimXYZ\u200b1"))
                .isInstanceOf(ConfigException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("geheimXYZ"));
    }
}
