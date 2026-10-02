package dev.infrai.fintech;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public final class InfraiErrorsClient {
    // Canonical capability: infrai.errors.capture
    private static final String CAPTURE_PATH = "/v1/errors/capture";
    private final RuntimeConfig config;
    private final HttpClient http;
    private final Consumer<Duration> sleeper;

    public InfraiErrorsClient(RuntimeConfig config) {
        this(config, HttpClient.newBuilder().connectTimeout(config.timeout()).build(), duration -> {
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted during retry delay", interrupted);
            }
        });
    }

    InfraiErrorsClient(RuntimeConfig config, HttpClient http, Consumer<Duration> sleeper) {
        this.config = Objects.requireNonNull(config);
        this.http = Objects.requireNonNull(http);
        this.sleeper = Objects.requireNonNull(sleeper);
    }

    public String capture(JobFailure failure, FailureDecision decision) throws IOException, InterruptedException {
        String requestBody = payload(failure, decision);
        for (int attempt = 1; attempt <= config.maxAttempts(); attempt++) {
            HttpRequest request = HttpRequest.newBuilder(config.baseUri().resolve(CAPTURE_PATH))
                    .timeout(config.timeout())
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", failure.eventId())
                    .method("POST", HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            Envelope envelope = Envelope.decode(response.body());

            if (response.statusCode() == 429 && attempt < config.maxAttempts()) {
                sleeper.accept(retryDelay(response, attempt));
                continue;
            }
            if (!envelope.ok()) {
                throw new InfraiException(envelope.errorCode(), envelope.errorMessage(), response.statusCode());
            }
            if (response.statusCode() >= 500) {
                throw new IOException("Infrai transport response: HTTP " + response.statusCode());
            }
            return envelope.data();
        }
        throw new IOException("Retry attempts exhausted");
    }

    private static Duration retryDelay(HttpResponse<?> response, int attempt) {
        String value = response.headers().firstValue("Retry-After").orElse("").trim();
        if (!value.isEmpty()) {
            try {
                return Duration.ofSeconds(Math.max(0, Long.parseLong(value)));
            } catch (NumberFormatException ignored) {
                try {
                    Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                    return Duration.between(Instant.now(), retryAt).isNegative()
                            ? Duration.ZERO : Duration.between(Instant.now(), retryAt);
                } catch (DateTimeParseException ignoredDate) {
                    // Fall through to bounded exponential delay.
                }
            }
        }
        return Duration.ofSeconds(1L << Math.min(attempt - 1, 5));
    }

    private static String payload(JobFailure failure, FailureDecision decision) {
        return "{" +
                "\"title\":\"" + json(failure.jobName() + " failed") + "\"," +
                "\"message\":\"" + json(failure.reason()) + "\"," +
                "\"exception\":\"" + json(failure.exceptionText()) + "\"," +
                "\"level\":\"error\"," +
                "\"fingerprint\":[\"" + json(failure.jobName()) + "\",\"" + json(failure.paymentRail()) + "\"]," +
                "\"context\":{" +
                "\"event_id\":\"" + json(failure.eventId()) + "\"," +
                "\"payment_rail\":\"" + json(failure.paymentRail()) + "\"," +
                "\"amount_minor\":" + failure.amountMinor() + "," +
                "\"currency\":\"" + json(failure.currency()) + "\"," +
                "\"risk_tier\":\"" + failure.riskTier() + "\"," +
                "\"action\":\"" + decision.action() + "\"}," +
                "\"idempotency_key\":\"" + json(failure.eventId()) + "\"}";
    }

    private static String json(String value) {
        StringBuilder escaped = new StringBuilder();
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20) escaped.append(String.format("\\u%04x", (int) ch));
                    else escaped.append(ch);
                }
            }
        }
        return escaped.toString();
    }

    record Envelope(boolean ok, String data, String errorCode, String errorMessage) {
        static Envelope decode(String json) throws IOException {
            JsonObject root = JsonObject.parse(json);
            boolean ok = root.booleanValue("ok");
            String data = root.rawValue("data");
            JsonObject error = root.objectValue("error");
            return new Envelope(ok, data, error.stringValue("code"), error.stringValue("message"));
        }
    }

    static final class JsonObject {
        private final String source;

        private JsonObject(String source) { this.source = source; }

        static JsonObject parse(String source) throws IOException {
            String trimmed = source == null ? "" : source.trim();
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
                throw new IOException("Response is not a JSON object");
            }
            return new JsonObject(trimmed);
        }

        boolean booleanValue(String key) throws IOException {
            String raw = rawValue(key);
            if ("true".equals(raw)) return true;
            if ("false".equals(raw)) return false;
            throw new IOException("Envelope field is not boolean: " + key);
        }

        String stringValue(String key) throws IOException {
            String raw = rawValue(key);
            if ("null".equals(raw)) return "";
            if (raw.length() < 2 || raw.charAt(0) != '"') throw new IOException("Field is not a string: " + key);
            return unescape(raw.substring(1, raw.length() - 1));
        }

        JsonObject objectValue(String key) throws IOException {
            String raw = rawValue(key);
            return "null".equals(raw) ? new JsonObject("{}") : parse(raw);
        }

        String rawValue(String key) throws IOException {
            int keyAt = source.indexOf("\"" + key + "\"");
            if (keyAt < 0) return "null";
            int colon = source.indexOf(':', keyAt + key.length() + 2);
            int start = skipSpace(source, colon + 1);
            if (start >= source.length()) throw new IOException("Missing value: " + key);
            char first = source.charAt(start);
            if (first == '"') return source.substring(start, stringEnd(source, start) + 1);
            if (first == '{' || first == '[') return source.substring(start, compositeEnd(source, start) + 1);
            int end = start;
            while (end < source.length() && ",}".indexOf(source.charAt(end)) < 0) end++;
            return source.substring(start, end).trim();
        }

        private static int skipSpace(String value, int at) {
            while (at < value.length() && Character.isWhitespace(value.charAt(at))) at++;
            return at;
        }

        private static int stringEnd(String value, int start) throws IOException {
            boolean escaped = false;
            for (int i = start + 1; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (ch == '"' && !escaped) return i;
                escaped = ch == '\\' && !escaped;
                if (ch != '\\') escaped = false;
            }
            throw new IOException("Unterminated JSON string");
        }

        private static int compositeEnd(String value, int start) throws IOException {
            char open = value.charAt(start);
            char close = open == '{' ? '}' : ']';
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int i = start; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (inString) {
                    if (ch == '"' && !escaped) inString = false;
                    escaped = ch == '\\' && !escaped;
                    if (ch != '\\') escaped = false;
                } else if (ch == '"') inString = true;
                else if (ch == open) depth++;
                else if (ch == close && --depth == 0) return i;
            }
            throw new IOException("Unterminated JSON value");
        }

        private static String unescape(String value) {
            return value.replace("\\\"", "\"").replace("\\n", "\n")
                    .replace("\\r", "\r").replace("\\t", "\t").replace("\\\\", "\\");
        }
    }

    public static final class InfraiException extends IOException {
        private static final long serialVersionUID = 1L;
        private final String code;
        private final int statusCode;

        InfraiException(String code, String message, int statusCode) {
            super(message == null || message.isBlank() ? code : message);
            this.code = code;
            this.statusCode = statusCode;
        }

        public String code() { return code; }
        public int statusCode() { return statusCode; }
    }
}
