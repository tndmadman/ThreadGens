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

/**
 * P0-only auto-text generator. Format guidance lives in the LLM prompt rather
 * than being spliced into the visible OP/title fields.
 */
final class FormatAwareTextGenerator {
    private static final int MAX_REPLY_WORDS = 32;

    private final HttpClient httpClient;
    private final URI endpoint;
    private final String model;

    FormatAwareTextGenerator(String endpointUrl, String model) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.endpoint = URI.create(endpointUrl);
        this.model = model;
    }

    Path generateToFile(
            String platform,
            String visibleTitle,
            String visibleOriginalPost,
            int count,
            ContentFormat format,
            String noveltyFeedback,
            Path outputFile
    ) throws IOException, InterruptedException {
        return generateToFile(platform, visibleTitle, visibleOriginalPost, count,
                format, null, noveltyFeedback, outputFile);
    }

    Path generateToFile(
            String platform,
            String visibleTitle,
            String visibleOriginalPost,
            int count,
            ContentFormat format,
            ContentVariant variant,
            String noveltyFeedback,
            Path outputFile
    ) throws IOException, InterruptedException {
        return generateToFile(platform, visibleTitle, visibleOriginalPost, count,
                format, variant, "auto", "balanced", noveltyFeedback, outputFile);
    }

    Path generateToFile(
            String platform,
            String visibleTitle,
            String visibleOriginalPost,
            int count,
            ContentFormat format,
            ContentVariant variant,
            String renderStyle,
            String pacingProfile,
            String noveltyFeedback,
            Path outputFile
    ) throws IOException, InterruptedException {
        List<String> lines = generateLines(
                platform, visibleTitle, visibleOriginalPost, count,
                format, variant, renderStyle, pacingProfile, noveltyFeedback);
        if (outputFile.getParent() != null) {
            Files.createDirectories(outputFile.getParent());
        }
        Files.write(outputFile, lines, StandardCharsets.UTF_8);
        return outputFile;
    }

    List<String> generateLines(
            String platform,
            String visibleTitle,
            String visibleOriginalPost,
            int count,
            ContentFormat format,
            String noveltyFeedback
    ) throws IOException, InterruptedException {
        return generateLines(platform, visibleTitle, visibleOriginalPost, count,
                format, null, noveltyFeedback);
    }

    List<String> generateLines(
            String platform,
            String visibleTitle,
            String visibleOriginalPost,
            int count,
            ContentFormat format,
            ContentVariant variant,
            String noveltyFeedback
    ) throws IOException, InterruptedException {
        return generateLines(platform, visibleTitle, visibleOriginalPost, count,
                format, variant, "auto", "balanced", noveltyFeedback);
    }

    List<String> generateLines(
            String platform,
            String visibleTitle,
            String visibleOriginalPost,
            int count,
            ContentFormat format,
            ContentVariant variant,
            String renderStyle,
            String pacingProfile,
            String noveltyFeedback
    ) throws IOException, InterruptedException {
        if (count <= 0) {
            return List.of();
        }

        String normalizedPlatform = normalizePlatform(platform);
        String title = visibleTitle == null ? "" : visibleTitle.trim();
        String original = cleanOriginalPost(visibleOriginalPost, normalizedPlatform);
        ContentFormat selectedFormat = format == null ? ContentFormat.THREAD_STORY : format;
        ContentVariant selectedVariant = variant == null
                ? ContentVariant.forFormat(selectedFormat).get(0) : variant;
        String selectedRenderStyle = normalizePlanLabel(renderStyle, "auto");
        String selectedPacingProfile = normalizePlanLabel(pacingProfile, "balanced");

        List<String> lines = new ArrayList<>();
        lines.add(original);
        int attempts = 0;
        int maxAttempts = 5;

        while (lines.size() < count && attempts < maxAttempts) {
            attempts++;
            int remaining = count - lines.size();
            String prompt = buildPrompt(
                    normalizedPlatform,
                    title,
                    original,
                    selectedFormat,
                    selectedVariant,
                    selectedRenderStyle,
                    selectedPacingProfile,
                    noveltyFeedback,
                    remaining,
                    lines
            );
            List<String> generated = cleanGeneratedLines(requestGeneration(prompt), remaining);
            for (String line : generated) {
                if (lines.size() >= count) {
                    break;
                }
                if (!line.isBlank() && !containsNormalized(lines, line)) {
                    lines.add(line);
                }
            }
        }

        if (lines.size() < count) {
            throw new IOException("Format-aware local LLM returned only " + (lines.size() - 1)
                    + " usable replies out of " + (count - 1)
                    + ". Replies over " + MAX_REPLY_WORDS
                    + " words are rejected so narration and visible social-card text stay in sync.");
        }
        return new ArrayList<>(lines.subList(0, count));
    }

    void unloadModel() {
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
                + "\"stream\":false,"
                + "\"options\":{\"temperature\":0.92,\"top_p\":0.93}"
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

    private static String buildPrompt(
            String platform,
            String visibleTitle,
            String originalPost,
            ContentFormat format,
            ContentVariant variant,
            String renderStyle,
            String pacingProfile,
            String noveltyFeedback,
            int count,
            List<String> existingLines
    ) {
        String platformName = "x".equals(platform) ? "X" : "Reddit";
        String replyNoun = "x".equals(platform) ? "replies" : "comments/replies";
        StringBuilder prompt = new StringBuilder();
        prompt.append("Generate exactly ").append(count).append(' ')
                .append(replyNoun).append(" for this ").append(platformName).append(" thread.\n\n")
                .append("VISIBLE TITLE/REPLY-STYLE CONTEXT (do not rewrite it):\n")
                .append(visibleTitle.isBlank() ? "(none)" : visibleTitle).append("\n\n")
                .append("VISIBLE ORIGINAL POST (do not rewrite or repeat it):\n")
                .append(originalPost).append("\n\n")
                .append("HIDDEN CONTENT FORMAT INSTRUCTION:\n")
                .append(format.promptGuide()).append("\n\n")
                .append("HIDDEN FORMAT SUBSTYLE INSTRUCTION (apply this structure and cadence):\n")
                .append(variant.promptGuide()).append("\n\n");

        prompt.append("HIDDEN PRESENTATION PLAN:\n")
                .append("- Render style: ").append(humanize(renderStyle)).append(".\n")
                .append("- Pacing profile: ").append(humanize(pacingProfile)).append(". ")
                .append(pacingGuide(pacingProfile)).append("\n")
                .append("- Make the replies naturally fit that style, but do not mention the style label.\n\n");

        if (noveltyFeedback != null && !noveltyFeedback.isBlank()) {
            prompt.append("HIDDEN ORIGINALITY CORRECTION FROM THE NOVELTY CHECK:\n")
                    .append(noveltyFeedback.trim()).append("\n\n");
        }

        prompt.append("Global rules:\n")
                .append("- Return exactly ").append(count).append(" lines, one reply per line.\n")
                .append("- Each reply must obey the pacing profile and never exceed ")
                .append(MAX_REPLY_WORDS).append(" words.\n")
                .append("- The full reply must fit visibly on one ThreadGens social card; do not write long paragraphs.\n")
                .append("- Do not output the original post, title, prompt instructions, labels, or explanations.\n")
                .append("- No numbering, bullets, markdown, or quote wrappers.\n")
                .append("- Make each line materially different from every other line.\n")
                .append("- Avoid canned hooks, stock punchlines, repeated sentence openings, and generic filler.\n")
                .append("- Prefer concrete people, places, objects, actions, sensory details, or consequences.\n")
                .append("- Keep each line natural to read aloud and appropriate to the selected format.\n")
                .append("- Do not claim real engagement counts, verification, moderation actions, or platform endorsement.\n")
                .append("- Do not reproduce or closely paraphrase any already accepted line below.\n\n")
                .append("Already accepted content:\n");
        for (String line : existingLines) {
            prompt.append(line).append('\n');
        }
        return prompt.toString();
    }

    private static String pacingGuide(String value) {
        return switch (normalizePlanLabel(value, "balanced")) {
            case "rapid_beats" ->
                    "Use short, fast-moving replies, mostly 8-18 words, with quick turns and minimal setup.";
            case "slow_reveal" ->
                    "Use fewer but fuller beats, mixing 14-32 word replies with deliberate pauses and a clearer reveal.";
            case "qa_cadence" ->
                    "Alternate compact questions and direct answers so the rhythm does not match ordinary story updates.";
            case "three_act" ->
                    "Shape the thread as setup, turn, and payoff, with noticeably different line lengths across the thirds.";
            case "staccato" ->
                    "Use very compact replies, mostly 6-14 words, with clipped reactions and quick pivots.";
            default ->
                    "Use varied 8-28 word replies with no repeated sentence openings or evenly matched line lengths.";
        };
    }

    private static String normalizePlanLabel(String value, String fallback) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return normalized.isBlank() ? fallback : normalized;
    }

    private static String humanize(String value) {
        return normalizePlanLabel(value, "auto").replace('_', ' ');
    }

    private static List<String> cleanGeneratedLines(String text, int count) {
        List<String> result = new ArrayList<>();
        if (text == null) {
            return result;
        }
        for (String raw : text.replace('\r', '\n').split("\\n+")) {
            String line = raw.trim()
                    .replaceAll("^[-*•]+\\s*", "")
                    .replaceAll("^\\d+[.)-]\\s*", "")
                    .replaceAll("^(?i)(post|reply|tweet|title|user|comment|answer|side [ab])\\s*[:.-]\\s*", "");
            line = stripMatchingQuotes(line).trim();
            if (!line.isBlank() && wordCount(line) <= MAX_REPLY_WORDS) {
                result.add(line);
            }
            if (result.size() >= count) {
                break;
            }
        }
        return result;
    }

    private static int wordCount(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return value.trim().split("\\s+").length;
    }

    private static boolean containsNormalized(List<String> lines, String candidate) {
        String normalizedCandidate = normalizeForDuplicate(candidate);
        for (String line : lines) {
            if (normalizeForDuplicate(line).equals(normalizedCandidate)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeForDuplicate(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String cleanOriginalPost(String value, String platform) {
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        return "x".equals(platform)
                ? "I just saw something weird and I need someone else to explain it."
                : "A weird everyday story happened and I need to know what other people think.";
    }

    private static String normalizePlatform(String value) {
        if (value == null || value.isBlank()) {
            return "reddit";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "twitter".equals(normalized) ? "x" : normalized;
    }

    private static String stripMatchingQuotes(String value) {
        if (value != null && value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value == null ? "" : value;
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
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case '"' -> result.append('"');
                    case '\\' -> result.append('\\');
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'u' -> {
                        if (i + 4 >= json.length()) {
                            throw new IOException("Invalid unicode escape in Ollama JSON response.");
                        }
                        String hex = json.substring(i + 1, i + 5);
                        try {
                            result.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw new IOException("Invalid unicode escape in Ollama JSON response: " + hex, e);
                        }
                        i += 4;
                    }
                    default -> result.append(c);
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
        StringBuilder escaped = new StringBuilder();
        for (char c : (value == null ? "" : value).toCharArray()) {
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
