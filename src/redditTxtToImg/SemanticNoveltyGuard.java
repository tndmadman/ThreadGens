package redditTxtToImg;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Semantic cross-video novelty check backed by Ollama embeddings.
 *
 * The deterministic NoveltyGuard remains the first, cheap filter. This second
 * pass catches substantially reworded versions of the same underlying story,
 * joke, confession or argument. History is loaded strictly: a corrupt existing
 * history file is an error for automatic generation rather than silently being
 * treated as an empty history.
 */
final class SemanticNoveltyGuard {
    static final String DEFAULT_MODEL = "nomic-embed-text";
    static final double DEFAULT_THRESHOLD = 0.86;
    static final int DEFAULT_HISTORY_LIMIT = 50;

    record Result(boolean accepted, double highestSimilarity, String closestExcerpt, String reason) {
        String feedbackForRegeneration() {
            if (accepted) {
                return "";
            }
            return "The candidate is semantically too close to a recent script ("
                    + String.format(Locale.US, "%.0f%%", highestSimilarity * 100.0)
                    + "). Change the underlying premise, event sequence, central conflict, examples and ending; do not merely paraphrase it.";
        }
    }

    private final HttpClient client;
    private final URI endpoint;
    private final String model;
    private final double threshold;

    SemanticNoveltyGuard(String ollamaGenerateUrl, String model, double threshold) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.endpoint = URI.create(toEmbedUrl(ollamaGenerateUrl));
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        this.threshold = Math.max(0.0, Math.min(1.0, threshold));
    }

    Result assess(String candidate, List<String> history) throws IOException, InterruptedException {
        String cleanCandidate = candidate == null ? "" : candidate.trim();
        if (cleanCandidate.isBlank() || history == null || history.isEmpty()) {
            return new Result(true, 0.0, "", "No prior semantic history to compare.");
        }

        List<String> inputs = new ArrayList<>();
        inputs.add(cleanCandidate);
        inputs.addAll(history);
        List<double[]> embeddings = requestEmbeddings(inputs);
        if (embeddings.size() != inputs.size()) {
            throw new IOException("Ollama embedding response returned " + embeddings.size()
                    + " vectors for " + inputs.size() + " inputs.");
        }

        double[] candidateVector = embeddings.get(0);
        double highest = -1.0;
        int closestIndex = -1;
        for (int i = 1; i < embeddings.size(); i++) {
            double similarity = cosine(candidateVector, embeddings.get(i));
            if (similarity > highest) {
                highest = similarity;
                closestIndex = i - 1;
            }
        }
        highest = Math.max(0.0, highest);
        boolean accepted = highest < threshold;
        String excerpt = closestIndex < 0 ? "" : excerpt(history.get(closestIndex), 180);
        String reason = accepted
                ? String.format(Locale.US, "Semantic similarity %.0f%% is below the %.0f%% threshold.",
                        highest * 100.0, threshold * 100.0)
                : String.format(Locale.US, "Semantic similarity %.0f%% meets/exceeds the %.0f%% threshold.",
                        highest * 100.0, threshold * 100.0);
        return new Result(accepted, highest, excerpt, reason);
    }

    static List<String> loadRecentScripts(Path historyFile, int limit) throws IOException {
        if (historyFile == null || !Files.exists(historyFile)) {
            return List.of();
        }
        List<String> lines = Files.readAllLines(historyFile, StandardCharsets.UTF_8);
        List<String> scripts = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isBlank()) {
                continue;
            }

            String encoded;
            try {
                encoded = JsonText.extractString(line, "script_b64");
            } catch (IOException e) {
                throw new IOException("Novelty history is malformed at line " + (i + 1)
                        + " in " + historyFile + ". Refusing to treat corrupt history as empty.", e);
            }
            if (encoded == null) {
                throw new IOException("Novelty history is malformed at line " + (i + 1)
                        + " in " + historyFile + ". Refusing to treat corrupt history as empty.");
            }

            try {
                String decoded = decodeHistoryBase64(encoded).trim();
                if (decoded.isBlank()) {
                    throw new IllegalArgumentException("empty decoded script");
                }
                scripts.add(decoded);
            } catch (IllegalArgumentException e) {
                throw new IOException("Novelty history contains invalid script data at line " + (i + 1)
                        + " in " + historyFile + ".", e);
            }
        }
        int safeLimit = Math.max(1, limit);
        int start = Math.max(0, scripts.size() - safeLimit);
        return List.copyOf(scripts.subList(start, scripts.size()));
    }

    private static String decodeHistoryBase64(String encoded) {
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException urlFailure) {
            try {
                decoded = Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException standardFailure) {
                standardFailure.addSuppressed(urlFailure);
                throw standardFailure;
            }
        }
        return new String(decoded, StandardCharsets.UTF_8);
    }

    private List<double[]> requestEmbeddings(List<String> inputs) throws IOException, InterruptedException {
        StringBuilder json = new StringBuilder();
        json.append("{\"model\":\"").append(escapeJson(model)).append("\",\"input\":[");
        for (int i = 0; i < inputs.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('\"').append(escapeJson(inputs.get(i))).append('\"');
        }
        json.append("]}");

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Semantic novelty embedding request failed with HTTP " + response.statusCode()
                    + ". Ensure Ollama is running and `ollama pull " + model + "` has completed. Response: "
                    + compact(response.body(), 600));
        }
        return parseEmbeddings(response.body());
    }

    private static List<double[]> parseEmbeddings(String json) throws IOException {
        int key = json == null ? -1 : json.indexOf("\"embeddings\"");
        if (key < 0) {
            throw new IOException("Ollama embedding response did not contain an embeddings array.");
        }
        int outerStart = json.indexOf('[', key);
        if (outerStart < 0) {
            throw new IOException("Ollama embedding response contained an invalid embeddings array.");
        }

        List<double[]> vectors = new ArrayList<>();
        List<Double> current = null;
        StringBuilder number = new StringBuilder();
        int depth = 0;
        for (int i = outerStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') {
                depth++;
                if (depth == 2) {
                    current = new ArrayList<>();
                    number.setLength(0);
                }
                continue;
            }
            if (c == ']') {
                if (depth == 2 && current != null) {
                    flushNumber(number, current);
                    double[] vector = new double[current.size()];
                    for (int j = 0; j < current.size(); j++) {
                        vector[j] = current.get(j);
                    }
                    if (vector.length == 0) {
                        throw new IOException("Ollama returned an empty embedding vector.");
                    }
                    vectors.add(vector);
                    current = null;
                }
                depth--;
                if (depth == 0) {
                    break;
                }
                continue;
            }
            if (depth == 2 && current != null) {
                if (c == ',') {
                    flushNumber(number, current);
                } else if (!Character.isWhitespace(c)) {
                    number.append(c);
                }
            }
        }
        if (vectors.isEmpty()) {
            throw new IOException("Ollama embedding response did not contain any vectors.");
        }
        return vectors;
    }

    private static void flushNumber(StringBuilder number, List<Double> target) throws IOException {
        if (number.isEmpty()) {
            return;
        }
        try {
            target.add(Double.parseDouble(number.toString()));
        } catch (NumberFormatException e) {
            throw new IOException("Invalid number in Ollama embedding response: " + number, e);
        } finally {
            number.setLength(0);
        }
    }

    private static double cosine(double[] a, double[] b) throws IOException {
        if (a.length != b.length || a.length == 0) {
            throw new IOException("Embedding vector dimensions do not match.");
        }
        double dot = 0.0;
        double aa = 0.0;
        double bb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            aa += a[i] * a[i];
            bb += b[i] * b[i];
        }
        if (aa <= 0.0 || bb <= 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(aa) * Math.sqrt(bb));
    }

    private static String toEmbedUrl(String generateUrl) {
        String value = generateUrl == null || generateUrl.isBlank()
                ? "http://localhost:11434/api/generate"
                : generateUrl.trim();
        if (value.endsWith("/api/generate")) {
            return value.substring(0, value.length() - "/api/generate".length()) + "/api/embed";
        }
        if (value.endsWith("/api/chat")) {
            return value.substring(0, value.length() - "/api/chat".length()) + "/api/embed";
        }
        if (value.endsWith("/")) {
            return value + "api/embed";
        }
        return value + "/api/embed";
    }

    private static String escapeJson(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> result.append("\\\\");
                case '\"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20) {
                        result.append(String.format("\\u%04x", (int) c));
                    } else {
                        result.append(c);
                    }
                }
            }
        }
        return result.toString();
    }

    private static String excerpt(String text, int maxChars) {
        return compact(text == null ? "" : text.replaceAll("\\s+", " ").trim(), maxChars);
    }

    private static String compact(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String clean = value.replaceAll("\\s+", " ").trim();
        if (clean.length() <= maxChars) {
            return clean;
        }
        return clean.substring(0, Math.max(1, maxChars - 3)).trim() + "...";
    }
}
