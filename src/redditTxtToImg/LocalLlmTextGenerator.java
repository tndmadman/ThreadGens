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
import java.util.List;

public class LocalLlmTextGenerator {
    private final HttpClient httpClient;
    private final URI endpoint;
    private final String model;

    public LocalLlmTextGenerator(String endpointUrl, String model) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.endpoint = URI.create(endpointUrl);
        this.model = model;
    }

    public List<String> generateLines(String topic, int count) throws IOException, InterruptedException {
        if (count <= 0) {
            return List.of();
        }

        List<String> lines = new ArrayList<>();
        int attempts = 0;
        int maxAttempts = 4;

        while (lines.size() < count && attempts < maxAttempts) {
            attempts++;
            int remaining = count - lines.size();
            String prompt = attempts == 1 || lines.isEmpty()
                    ? buildPrompt(topic, count)
                    : buildContinuationPrompt(topic, remaining, lines);

            String generatedText = requestGeneration(prompt);
            List<String> cleanedLines = cleanGeneratedLines(generatedText, remaining);

            for (String line : cleanedLines) {
                if (lines.size() >= count) {
                    break;
                }
                if (!line.isBlank() && !lines.contains(line)) {
                    lines.add(line);
                }
            }
        }

        if (lines.size() < count) {
            throw new IOException("Local LLM returned only " + lines.size() + " usable lines out of " + count
                    + ". Try again, lower the count, or use a stronger Ollama model.");
        }

        return new ArrayList<>(lines.subList(0, count));
    }

    public Path generateToFile(String topic, int count, Path outputFile) throws IOException, InterruptedException {
        List<String> lines = generateLines(topic, count);
        if (outputFile.getParent() != null) {
            Files.createDirectories(outputFile.getParent());
        }
        Files.write(outputFile, lines, StandardCharsets.UTF_8);
        return outputFile;
    }

    private String requestGeneration(String prompt) throws IOException, InterruptedException {
        String json = "{"
                + "\"model\":\"" + escapeJson(model) + "\","
                + "\"prompt\":\"" + escapeJson(prompt) + "\","
                + "\"stream\":false"
                + "}";

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Ollama request failed with HTTP " + response.statusCode() + ": " + response.body());
        }

        String generatedText = extractJsonString(response.body(), "response");
        if (generatedText == null || generatedText.isBlank()) {
            throw new IOException("Ollama response did not include generated text.");
        }

        return generatedText;
    }

    private static String buildPrompt(String topic, int count) {
        String safeTopic = topic == null || topic.isBlank() ? "weird everyday stories" : topic.trim();
        int replyCount = Math.max(0, count - 1);
        return "Generate exactly " + count + " lines for a Reddit thread about: " + safeTopic + "\n\n"
                + "Structure:\n"
                + "- Line 1 must be the original Reddit post. Make it sound like the main post/body.\n"
                + "- Lines 2 through " + count + " must be comments or replies reacting to that original post.\n"
                + "- If there are " + replyCount + " replies, make them feel like a real thread conversation.\n\n"
                + "Rules:\n"
                + "- Return exactly " + count + " lines.\n"
                + "- Return one post/comment per line.\n"
                + "- No numbering, bullets, quotes, markdown, explanations, titles, or labels.\n"
                + "- Do not prefix lines with POST, OP, COMMENT, or REPLY.\n"
                + "- Each line should be 1 or 2 sentences.\n"
                + "- Make each line punchy, readable aloud, and safe for a general audience.\n"
                + "- Do not mention that you are an AI.\n";
    }

    private static String buildContinuationPrompt(String topic, int count, List<String> existingLines) {
        String safeTopic = topic == null || topic.isBlank() ? "weird everyday stories" : topic.trim();
        StringBuilder prompt = new StringBuilder();
        prompt.append("Generate exactly ").append(count)
                .append(" more Reddit comments/replies for the same thread about: ")
                .append(safeTopic).append("\n\n")
                .append("Rules:\n")
                .append("- Return exactly ").append(count).append(" lines.\n")
                .append("- Return one comment/reply per line.\n")
                .append("- These are follow-up comments, not a second original post.\n")
                .append("- No numbering, bullets, quotes, markdown, explanations, titles, or labels.\n")
                .append("- Do not repeat these already accepted lines:\n");
        for (String existingLine : existingLines) {
            prompt.append(existingLine).append("\n");
        }
        return prompt.toString();
    }

    private static List<String> cleanGeneratedLines(String text, int count) {
        List<String> lines = new ArrayList<>();
        String[] rawLines = text.replace('\r', '\n').split("\\n+");

        for (String rawLine : rawLines) {
            String line = rawLine.trim();
            line = line.replaceAll("^[-*•]+\\s*", "");
            line = line.replaceAll("^\\d+[.)-]\\s*", "");
            line = line.replaceAll("^(?i)(post|op|original post|comment|reply)\\s*[:.-]\\s*", "");
            line = stripMatchingQuotes(line);
            if (!line.isBlank()) {
                lines.add(line);
            }
            if (lines.size() >= count) {
                break;
            }
        }

        if (lines.isEmpty()) {
            String fallback = stripMatchingQuotes(text.trim().replaceAll("\\s+", " "));
            if (!fallback.isBlank()) {
                lines.add(fallback);
            }
        }

        return lines;
    }

    private static String stripMatchingQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }

    private static String extractJsonString(String json, String key) throws IOException {
        String needle = "\"" + key + "\"";
        int keyIndex = json.indexOf(needle);
        if (keyIndex < 0) {
            return null;
        }

        int colonIndex = json.indexOf(':', keyIndex + needle.length());
        if (colonIndex < 0) {
            return null;
        }

        int quoteIndex = json.indexOf('"', colonIndex + 1);
        if (quoteIndex < 0) {
            return null;
        }

        StringBuilder result = new StringBuilder();
        boolean escaping = false;
        for (int i = quoteIndex + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaping) {
                switch (c) {
                    case 'n': result.append('\n'); break;
                    case 'r': result.append('\r'); break;
                    case 't': result.append('\t'); break;
                    case 'b': result.append('\b'); break;
                    case 'f': result.append('\f'); break;
                    case 'u':
                        if (i + 4 >= json.length()) {
                            throw new IOException("Bad unicode escape in Ollama response.");
                        }
                        String hex = json.substring(i + 1, i + 5);
                        try {
                            result.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw new IOException("Bad unicode escape in Ollama response: " + hex, e);
                        }
                        i += 4;
                        break;
                    default: result.append(c); break;
                }
                escaping = false;
                continue;
            }

            if (c == '\\') {
                escaping = true;
            } else if (c == '"') {
                return result.toString();
            } else {
                result.append(c);
            }
        }

        throw new IOException("Unterminated response string in Ollama response.");
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': escaped.append("\\\\"); break;
                case '"': escaped.append("\\\""); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
            }
        }
        return escaped.toString();
    }
}
