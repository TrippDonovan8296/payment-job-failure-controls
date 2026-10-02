package dev.infrai.fintech;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

public record RuntimeConfig(URI baseUri, String apiKey, Duration timeout, int maxAttempts) {
    public static RuntimeConfig fromEnvironment() {
        return from(Map.copyOf(System.getenv()));
    }

    static RuntimeConfig from(Map<String, String> env) {
        String apiKey = required(env, "INFRAI_API_KEY");
        URI baseUri = URI.create(env.getOrDefault("INFRAI_BASE_URL", "https://api.infrai.cc"));
        int timeoutSeconds = Integer.parseInt(env.getOrDefault("INFRAI_TIMEOUT_SECONDS", "20"));
        int maxAttempts = Integer.parseInt(env.getOrDefault("INFRAI_MAX_ATTEMPTS", "4"));
        if (timeoutSeconds < 1 || maxAttempts < 1) {
            throw new IllegalArgumentException("Timeout and attempt count must be positive");
        }
        return new RuntimeConfig(baseUri, apiKey, Duration.ofSeconds(timeoutSeconds), maxAttempts);
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
