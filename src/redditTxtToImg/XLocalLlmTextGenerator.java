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
import java.util.Locale;

public class XLocalLlmTextGenerator {
    private final HttpClient httpClient;
    private final URI endpoint;
    private final String model;

    public XLocalLlmTextGenerator(String endpointUrl, String model) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.endpoint = URI.create(endpointUrl);
        this.model = model;
    }

    public Path generateToFile(String replyInstruction, String originalPost, int count, Path outputFile)
            throws IOException, InterruptedException {
        List<String> lines = generateLines(replyInstruction, originalPost, count);
        if (outputFile.getParent() != null) {
            Files.createDirectories(outputFile.getParent());
        }
        Files.write(outputFile, lines, StandardCharsets.UTF_8);
        return outputFile;
    }

    public List<String> generateLines(String replyInstruction, String originalPost, int count)
            throws IOException, InterruptedException {
        if (count <= 0) {
            return List.of();
        }

        String cleanPost = cleanOriginalPost(originalPost);
        String cleanInstruction = cleanReplyInstruction(replyInstruction);
        ReplyMode replyMode = detectReplyMode(cleanInstruction + " " + cleanPost);
        System.out.println("Detected X reply mode: " + replyMode.label);

        List<String> lines = new ArrayList<>();
        lines.add(cleanPost);

        int attempts = 0;
        while (lines.size() < count && attempts < 4) {
            attempts++;
            int remaining = count - lines.size();
            String prompt = buildPrompt(cleanInstruction, cleanPost, replyMode, remaining, lines);
            List<String> generatedLines = cleanGeneratedLines(requestGeneration(prompt), remaining);
            for (String line : generatedLines) {
                if (lines.size() >= count) {
                    break;
                }
                if (!line.isBlank() && !lines.contains(line)) {
                    lines.add(line);
                }
            }
        }

        if (lines.size() < count) {
            throw new IOException("Local LLM returned only " + (lines.size() - 1) + " usable X replies out of " + (count - 1)
                    + ". Try again, lower the count, or use a stronger Ollama model.");
        }
        return new ArrayList<>(lines.subList(0, count));
    }

    public void unloadModel() {
        try {
            String json = "{"
                    + "\"model\":\"" + escapeJson(model) + "\","
                    + "\"keep_alive\":0,"
                    + "\"stream\":false"
                    + "}";
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            System.out.println("Requested Ollama model unload: " + model);
        } catch (Exception e) {
            System.out.println("Could not unload Ollama model cleanly: " + e.getMessage());
        }
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

    private static String buildPrompt(String replyInstruction, String originalPost, ReplyMode replyMode,
                                      int count, List<String> existingLines) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Generate exactly ").append(count)
                .append(" realistic replies under this X post.\n\n")
                .append("Original X post text:\n")
                .append(originalPost).append("\n\n");
        if (!replyInstruction.isBlank()) {
            prompt.append("Reply style instruction, not visible text:\n")
                    .append(replyInstruction).append("\n\n");
        }
        prompt.append("Reply mode: ").append(replyMode.label).append("\n\n")
                .append("Rules:\n")
                .append("- Return exactly ").append(count).append(" lines.\n")
                .append("- Return one reply per line.\n")
                .append("- Do not write a title.\n")
                .append("- Do not write a new original post.\n")
                .append("- Do not repeat the original X post.\n")
                .append("- Do not mention Reddit, subreddits, post titles, upvotes, or OP.\n")
                .append("- Do not prefix lines with post, reply, tweet, title, user, or comment.\n")
                .append("- No numbering, bullets, markdown, quotes, labels, or explanations.\n")
                .append("- Make each reply sound like a different X user.\n")
                .append("- Keep replies casual, direct, and readable aloud.\n")
                .append("- Each reply should usually be 2 sentences, or 3 short sentences if the idea needs it.\n")
                .append("- Aim for roughly 22 to 48 words per reply.\n")
                .append("- Add one concrete detail per reply, such as a place, object, reaction, sound, time, or small action.\n")
                .append("- Keep it tight enough to still feel like X, not a full paragraph.\n");

        switch (replyMode) {
            case STORY:
                prompt.append("- The replies should continue the story beat by beat.\n")
                        .append("- Each reply should add a new event, reveal, escalation, or ending with a specific detail.\n");
                break;
            case WRONG_ANSWERS:
                prompt.append("- The replies should be intentionally wrong, absurd, and funny.\n")
                        .append("- Give each joke a concrete image or fake explanation so it is more than a one-liner.\n");
                break;
            case ADVICE:
                prompt.append("- Mix practical advice, warnings, questions, and short opinions.\n")
                        .append("- Include a specific reason, example, or consequence in each reply.\n");
                break;
            case DEBATE:
                prompt.append("- Mix agreement, skepticism, questions, and alternate explanations.\n")
                        .append("- Give each reply a clear clue, reason, or example instead of a bare reaction.\n");
                break;
            case NORMAL:
            default:
                prompt.append("- Mix jokes, reactions, observations, and questions.\n")
                        .append("- Make each reply feel like a complete thought with a small detail or angle.\n");
                break;
        }

        prompt.append("\nAlready accepted lines to avoid repeating:\n");
        for (String existingLine : existingLines) {
            prompt.append(existingLine).append("\n");
        }
        return prompt.toString();
    }

    private static ReplyMode detectReplyMode(String value) {
        String text = value == null ? "" : value.toLowerCase(Locale.ROOT);
        text = text.replaceAll("[^a-z0-9 ?!']+", " ").replaceAll("\\s+", " ").trim();
        if (text.contains("finish this story") || text.contains("continue this story")
                || text.contains("what happened next") || text.contains("finish it in the replies")) {
            return ReplyMode.STORY;
        }
        if (text.contains("wrong answers only") || text.contains("wrong answer only")) {
            return ReplyMode.WRONG_ANSWERS;
        }
        if (text.contains("what would you do") || text.contains("what should i do")
                || text.contains("any advice") || text.contains("need advice")) {
            return ReplyMode.ADVICE;
        }
        if (text.contains("am i crazy") || text.contains("am i wrong") || text.contains("aita")) {
            return ReplyMode.DEBATE;
        }
        return ReplyMode.NORMAL;
    }

    private static String cleanOriginalPost(String value) {
        String post = value == null || value.isBlank()
                ? "I just saw something weird and I need someone else to explain it."
                : value.trim();
        post = post.replaceAll("(?i)^finish this story in the comments\\s*[:.-]?\\s*", "").trim();
        post = post.replaceAll("(?i)^finish this story in the replies\\s*[:.-]?\\s*", "").trim();
        if (post.isBlank()) {
            return "I just saw something weird and I need someone else to explain it.";
        }
        return post;
    }

    private static String cleanReplyInstruction(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String instruction = value.trim();
        if ("Finish this story in the comments".equalsIgnoreCase(instruction)) {
            return "Finish this story in the replies";
        }
        return instruction;
    }

    private static List<String> cleanGeneratedLines(String text, int count) {
        List<String> lines = new ArrayList<>();
        String[] rawLines = text.replace('\r', '\n').split("\\n+");
        for (String rawLine : rawLines) {
            String line = rawLine.trim();
            line = line.replaceAll("^[-*•]+\\s*", "");
            line = line.replaceAll("^\\d+[.)-]\\s*", "");
            line = line.replaceAll("^(?i)(post|reply|tweet|title|user|comment)\\s*[:.-]\\s*", "");
            line = stripMatchingQuotes(line);
            if (!line.isBlank()) {
                lines.add(line);
            }
            if (lines.size() >= count) {
                break;
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
        if (keyIndex < 0) return null;
        int colonIndex = json.indexOf(':', keyIndex + needle.length());
        if (colonIndex < 0) return null;
        int quoteIndex = json.indexOf('"', colonIndex + 1);
        if (quoteIndex < 0) return null;

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
                        if (i + 4 >= json.length()) throw new IOException("Bad unicode escape in Ollama response.");
                        String hex = json.substring(i + 1, i + 5);
                        try {
                            result.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            throw new IOException("Bad unicode escape in Ollama response: " + hex);
                        }
                        break;
                    default: result.append(c); break;
                }
                escaping = false;
            } else if (c == '\\') {
                escaping = true;
            } else if (c == '"') {
                return result.toString();
            } else {
                result.append(c);
            }
        }
        throw new IOException("Unterminated JSON string in Ollama response.");
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private enum ReplyMode {
        STORY("story"), WRONG_ANSWERS("wrong_answers"), ADVICE("advice"), DEBATE("debate"), NORMAL("normal");
        final String label;
        ReplyMode(String label) {
            this.label = label;
        }
    }
}
