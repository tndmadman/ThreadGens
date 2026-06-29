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

    public List<String> generateLines(String postTitle, String originalStory, int count) throws IOException, InterruptedException {
        if (count <= 0) {
            return List.of();
        }

        String cleanTitle = cleanPostTitle(postTitle);
        String cleanStory = cleanOriginalStory(originalStory, cleanTitle);
        ResponseMode responseMode = detectResponseMode(cleanTitle);
        System.out.println("Detected reply mode from title: " + responseMode.label);

        List<String> lines = new ArrayList<>();
        lines.add(cleanStory);

        int replyTarget = count - 1;
        int attempts = 0;
        int maxAttempts = 4;

        while (lines.size() - 1 < replyTarget && attempts < maxAttempts) {
            attempts++;
            int remaining = count - lines.size();
            String prompt = buildReplyPrompt(cleanTitle, cleanStory, responseMode, remaining, lines);

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
            throw new IOException("Local LLM returned only " + (lines.size() - 1) + " usable replies out of " + replyTarget
                    + ". Try again, lower the count, or use a stronger Ollama model.");
        }

        return new ArrayList<>(lines.subList(0, count));
    }

    public Path generateToFile(String postTitle, String originalStory, int count, Path outputFile) throws IOException, InterruptedException {
        List<String> lines = generateLines(postTitle, originalStory, count);
        if (outputFile.getParent() != null) {
            Files.createDirectories(outputFile.getParent());
        }
        Files.write(outputFile, lines, StandardCharsets.UTF_8);
        return outputFile;
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

    private static String buildReplyPrompt(String postTitle, String originalStory, ResponseMode responseMode, int count, List<String> existingLines) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Generate exactly ").append(count)
                .append(" Reddit comments/replies for this thread.\n\n")
                .append("Post title:\n")
                .append(postTitle).append("\n\n")
                .append("Original post body:\n")
                .append(originalStory).append("\n\n")
                .append("Detected response mode from the title: ").append(responseMode.label).append("\n\n")
                .append("Mode-specific rules:\n");

        appendModeRules(prompt, responseMode);

        prompt.append("\nGlobal rules:\n")
                .append("- Return exactly ").append(count).append(" lines.\n")
                .append("- Return one comment/reply per line.\n")
                .append("- Do not write a new original post.\n")
                .append("- Do not repeat the post title.\n")
                .append("- Do not repeat the original post body.\n")
                .append("- No numbering, bullets, quotes, markdown, explanations, titles, or labels.\n")
                .append("- Do not prefix lines with POST, OP, COMMENT, or REPLY.\n")
                .append("- Each line should be 1 or 2 sentences and readable aloud.\n")
                .append("- Do not repeat these already accepted lines:\n");
        for (String existingLine : existingLines) {
            prompt.append(existingLine).append("\n");
        }
        return prompt.toString();
    }

    private static void appendModeRules(StringBuilder prompt, ResponseMode responseMode) {
        switch (responseMode) {
            case STORY_CONTINUATION:
                prompt.append("- The replies must collectively finish the story as a chronological chain.\n")
                        .append("- Each reply must continue from the original post or the previous accepted reply.\n")
                        .append("- Every reply must add a new event, escalation, discovery, reveal, twist, or ending.\n")
                        .append("- Do not merely react, judge, summarize, warn, call authorities, or say the story is wild unless the reply also adds the next story beat.\n")
                        .append("- The final reply in this batch should feel like an ending, cliffhanger, or strong twist.\n");
                break;
            case ABSURD_JOKES:
                prompt.append("- The replies should be intentionally wrong, absurd, and funny.\n")
                        .append("- Each reply should answer or react in a clearly ridiculous way.\n")
                        .append("- Do not give serious advice unless it is obviously part of the joke.\n");
                break;
            case ROAST:
                prompt.append("- The replies should roast the original poster or situation.\n")
                        .append("- Keep the roasts playful, punchy, and varied.\n")
                        .append("- Do not continue the story unless the roast adds a joke beat.\n");
                break;
            case ADVICE:
                prompt.append("- The replies should give advice, options, warnings, or practical next steps.\n")
                        .append("- Make the comments feel like different Reddit users with different perspectives.\n")
                        .append("- Do not force a chronological story chain.\n");
                break;
            case DEBATE_SUPPORT:
                prompt.append("- The replies should debate whether the original poster is right, wrong, or missing something.\n")
                        .append("- Mix support, skepticism, questions, and alternate explanations.\n")
                        .append("- Do not force a chronological story chain.\n");
                break;
            case REDDIT_REACTIONS:
            default:
                prompt.append("- The replies should feel like normal Reddit comments reacting to the original post.\n")
                        .append("- Mix jokes, observations, questions, and short personal reactions.\n")
                        .append("- Do not force a chronological story chain unless the title asks for one.\n");
                break;
        }
    }

    private static ResponseMode detectResponseMode(String postTitle) {
        String title = postTitle == null ? "" : postTitle.toLowerCase();
        title = title.replaceAll("[^a-z0-9 ?!']+", " ").replaceAll("\\s+", " ").trim();

        if (title.contains("finish this story")
                || title.contains("finish the story")
                || title.contains("continue this story")
                || title.contains("continue the story")
                || title.contains("what happened next")
                || title.contains("finish it in the comments")) {
            return ResponseMode.STORY_CONTINUATION;
        }
        if (title.contains("wrong answers only") || title.contains("wrong answer only")) {
            return ResponseMode.ABSURD_JOKES;
        }
        if (title.contains("roast me") || title.contains("roast this") || title.contains("roast my")) {
            return ResponseMode.ROAST;
        }
        if (title.contains("what would you do")
                || title.contains("what should i do")
                || title.contains("what do i do")
                || title.contains("any advice")
                || title.contains("need advice")) {
            return ResponseMode.ADVICE;
        }
        if (title.contains("am i crazy")
                || title.contains("tell me i'm not crazy")
                || title.contains("tell me im not crazy")
                || title.contains("am i wrong")
                || title.contains("aita")) {
            return ResponseMode.DEBATE_SUPPORT;
        }
        return ResponseMode.REDDIT_REACTIONS;
    }

    private static String cleanPostTitle(String value) {
        if (value == null || value.isBlank()) {
            return "Finish this story in the comments";
        }
        return value.trim();
    }

    private static String cleanOriginalStory(String value, String postTitle) {
        String story = value == null || value.isBlank() ? "a weird everyday story" : value.trim();
        story = story.replaceAll("(?i)^" + java.util.regex.Pattern.quote(postTitle) + "\\s*[:.-]?\\s*", "").trim();
        story = story.replaceAll("(?i)^finish this story in the comments\\s*[:.-]?\\s*", "").trim();
        if (story.isBlank()) {
            return "a weird everyday story";
        }
        return story;
    }

    private static List<String> cleanGeneratedLines(String text, int count) {
        List<String> lines = new ArrayList<>();
        String[] rawLines = text.replace('\r', '\n').split("\\n+");

        for (String rawLine : rawLines) {
            String line = rawLine.trim();
            line = line.replaceAll("^[-*•]+\\s*", "");
            line = line.replaceAll("^\\d+[.)-]\\s*", "");
            line = line.replaceAll("^(?i)(post|op|original post|comment|reply|title)\\s*[:.-]\\s*", "");
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

    private enum ResponseMode {
        STORY_CONTINUATION("story_continuation"),
        ABSURD_JOKES("absurd_jokes"),
        ROAST("roast"),
        ADVICE("advice"),
        DEBATE_SUPPORT("debate_support"),
        REDDIT_REACTIONS("reddit_reactions");

        final String label;

        ResponseMode(String label) {
            this.label = label;
        }
    }
}
