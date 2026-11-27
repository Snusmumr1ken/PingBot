package pingbot;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationTest {
    @Test
    void loadsRequiredSettingsAndDefaults() {
        Configuration configuration = Configuration.fromEnvironment(Map.of(
                "BOT_TOKEN", " token ", "HOST", " example.com "));

        assertEquals("token", configuration.botToken());
        assertEquals("example.com", configuration.host());
        assertEquals(80, configuration.port());
        assertEquals(60000, configuration.checkIntervalMillis());
        assertEquals(5000, configuration.connectTimeoutMillis());
        assertEquals(Path.of("chat_ids.txt"), configuration.chatIdsFile());
    }

    @Test
    void acceptsCustomSettingsAndIpv6() {
        Configuration configuration = Configuration.fromEnvironment(Map.of(
                "BOT_TOKEN", "token", "HOST", "::1", "PORT", "443",
                "CHECK_INTERVAL_MILLIS", "1000", "CONNECT_TIMEOUT_MILLIS", "250",
                "CHAT_IDS_FILE", "data/subscribers.txt"));

        assertEquals("::1", configuration.host());
        assertEquals(443, configuration.port());
        assertEquals(1000, configuration.checkIntervalMillis());
        assertEquals(250, configuration.connectTimeoutMillis());
        assertEquals(Path.of("data/subscribers.txt"), configuration.chatIdsFile());
    }

    @Test
    void rejectsMissingRequiredSettings() {
        for (String key : new String[]{"BOT_TOKEN", "HOST"}) {
            Map<String, String> environment = new HashMap<>(Map.of("BOT_TOKEN", "token", "HOST", "localhost"));
            environment.remove(key);
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> Configuration.fromEnvironment(environment)).getMessage().contains(key));
            environment.put(key, " ");
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> Configuration.fromEnvironment(environment)).getMessage().contains(key));
        }
    }

    @Test
    void rejectsUrlsAndInvalidOperationalSettings() {
        String[][] invalidSettings = {
                {"HOST", "https://example.com"}, {"HOST", "example.com/path"},
                {"HOST", "example .com"}, {"PORT", "0"}, {"PORT", "65536"},
                {"PORT", "abc"}, {"CONNECT_TIMEOUT_MILLIS", "-1"},
                {"CONNECT_TIMEOUT_MILLIS", "2147483648"}, {"CHECK_INTERVAL_MILLIS", "0"},
                {"CHECK_INTERVAL_MILLIS", "9223372036854775808"}, {"CHAT_IDS_FILE", " "}
        };
        for (String[] setting : invalidSettings) {
            Map<String, String> environment = new HashMap<>(Map.of("BOT_TOKEN", "token", "HOST", "localhost"));
            environment.put(setting[0], setting[1]);
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> Configuration.fromEnvironment(environment));
            assertTrue(exception.getMessage().contains(setting[0]), exception.getMessage());
        }
    }
}
