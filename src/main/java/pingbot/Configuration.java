package pingbot;

import java.nio.file.Path;
import java.util.Map;

record Configuration(String botToken, String host, int port, long checkIntervalMillis,
                     int connectTimeoutMillis, Path chatIdsFile) {
    Configuration {
        botToken = required("BOT_TOKEN", botToken);
        host = required("HOST", host);
        if (host.contains("/") || host.contains("?") || host.contains("#")
                || host.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("HOST must be a hostname or IP address, without a URL scheme or path");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("PORT must be between 1 and 65535");
        }
        if (checkIntervalMillis < 1) {
            throw new IllegalArgumentException("CHECK_INTERVAL_MILLIS must be positive");
        }
        if (connectTimeoutMillis < 1) {
            throw new IllegalArgumentException("CONNECT_TIMEOUT_MILLIS must be positive");
        }
    }

    static Configuration fromEnvironment(Map<String, String> environment) {
        return new Configuration(
                environment.get("BOT_TOKEN"),
                environment.get("HOST"),
                integer(environment, "PORT", "80"),
                number(environment, "CHECK_INTERVAL_MILLIS", "60000"),
                integer(environment, "CONNECT_TIMEOUT_MILLIS", "5000"),
                Path.of(required("CHAT_IDS_FILE", environment.getOrDefault("CHAT_IDS_FILE", "chat_ids.txt"))));
    }

    private static String required(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.strip();
    }

    private static long number(Map<String, String> environment, String name, String fallback) {
        try {
            return Long.parseLong(environment.getOrDefault(name, fallback).strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a whole number");
        }
    }

    private static int integer(Map<String, String> environment, String name, String fallback) {
        long value = number(environment, name, fallback);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " is outside the supported integer range");
        }
        return (int) value;
    }
}
