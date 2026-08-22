package redditTxtToImg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Persistent cross-video originality guard.
 *
 * This intentionally has no external dependency so it is available in the same
 * offline/local environments as the rest of ThreadGens. It combines exact
 * hashing, token cosine similarity, token-shingle overlap, hook similarity and
 * structural similarity. The deterministic checks are used even when no local
 * embedding model is installed.
 */
final class NoveltyGuard {
    static final int DEFAULT_THRESHOLD = 48;
    static final int DEFAULT_HISTORY_LIMIT = 500;

    private static final Pattern JSON_FIELD =
            Pattern.compile("\"([a-zA-Z0-9_]+)\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"");
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "been", "but", "by", "for",
            "from", "had", "has", "have", "he", "her", "hers", "him", "his", "i",
            "if", "in", "into", "is", "it", "its", "me", "my", "of", "on", "or",
            "our", "ours", "she", "so", "that", "the", "their", "theirs", "them",
            "then", "there", "they", "this", "to", "too", "up", "us", "was", "we",
            "were", "what", "when", "where", "which", "who", "why", "with", "you",
            "your", "yours"
    );

    private final Path historyFile;
    private final int threshold;
    private final int historyLimit;

    NoveltyGuard(Path historyFile) {
        this(historyFile, DEFAULT_THRESHOLD, DEFAULT_HISTORY_LIMIT);
    }

    NoveltyGuard(Path historyFile, int threshold, int historyLimit) {
        this.historyFile = historyFile == null
                ? Path.of("data", "generation_history.jsonl")
                : historyFile;
        this.threshold = clamp(threshold, 0, 100);
        this.historyLimit = Math.max(1, historyLimit);
    }

    Result assess(String script) {
        String candidate = script == null ? "" : script.trim();
        if (candidate.isBlank()) {
            return new Result(true, 100, 0.0, "", List.of("No script text was available for comparison."));
        }

        Fingerprint candidateFingerprint = Fingerprint.from(candidate);
        List<Entry> entries = readEntries();
        if (entries.isEmpty()) {
            return new Result(true, 100, 0.0, "", List.of("No prior generation history yet."));
        }

        double highestSimilarity = 0.0;
        Entry closest = null;
        Similarity closestComponents = null;
        boolean exactDuplicate = false;
        boolean hardNearDuplicate = false;

        int start = Math.max(0, entries.size() - historyLimit);
        for (int i = start; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            Fingerprint historical = Fingerprint.from(entry.script);
            Similarity similarity = compare(candidateFingerprint, historical);

            if (candidateFingerprint.hash.equals(historical.hash)) {
                exactDuplicate = true;
            }
            if (similarity.shingle >= 0.72
                    || (similarity.hook >= 0.84 && similarity.cosine >= 0.55)
                    || (similarity.cosine >= 0.82 && similarity.structure >= 0.82)) {
                hardNearDuplicate = true;
            }

            if (similarity.overall > highestSimilarity) {
                highestSimilarity = similarity.overall;
                closest = entry;
                closestComponents = similarity;
            }
        }

        int repeatedLinePenalty = repeatedLinePenalty(candidate);
        int noveltyScore = clamp((int) Math.round((1.0 - highestSimilarity) * 100.0) - repeatedLinePenalty, 0, 100);
        boolean accepted = !exactDuplicate && !hardNearDuplicate && noveltyScore >= threshold;

        List<String> reasons = new ArrayList<>();
        if (exactDuplicate) {
            reasons.add("Exact normalized duplicate of prior content.");
        }
        if (hardNearDuplicate && !exactDuplicate) {
            reasons.add("Near-duplicate wording/hook/structure crossed a hard similarity limit.");
        }
        if (noveltyScore < threshold) {
            reasons.add("Novelty score " + noveltyScore + "/100 is below threshold " + threshold + ".");
        }
        if (repeatedLinePenalty > 0) {
            reasons.add("Candidate repeats substantially identical lines internally.");
        }
        if (closestComponents != null) {
            reasons.add(String.format(Locale.US,
                    "Closest match: overall %.0f%%, wording %.0f%%, hook %.0f%%, structure %.0f%%.",
                    closestComponents.overall * 100.0,
                    closestComponents.cosine * 100.0,
                    closestComponents.hook * 100.0,
                    closestComponents.structure * 100.0));
        }
        if (accepted && reasons.isEmpty()) {
            reasons.add("Candidate is sufficiently distinct from recent history.");
        }

        String excerpt = closest == null ? "" : excerpt(closest.script, 180);
        return new Result(accepted, noveltyScore, highestSimilarity, excerpt, List.copyOf(reasons));
    }

    void record(String script, String topic, ContentFormat format) throws IOException {
        if (script == null || script.isBlank()) {
            return;
        }
        if (historyFile.getParent() != null) {
            Files.createDirectories(historyFile.getParent());
        }
        Fingerprint fingerprint = Fingerprint.from(script);
        String line = "{"
                + "\"created\":\"" + jsonEscape(Instant.now().toString()) + "\","
                + "\"format\":\"" + jsonEscape(format == null ? "unknown" : format.id()) + "\","
                + "\"hash\":\"" + fingerprint.hash + "\","
                + "\"topic_b64\":\"" + encode(topic == null ? "" : topic) + "\","
                + "\"script_b64\":\"" + encode(script) + "\""
                + "}" + System.lineSeparator();
        Files.writeString(historyFile, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        compactIfNeeded();
    }

    List<String> recentFormats(int limit) {
        List<Entry> entries = readEntries();
        if (entries.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0 && result.size() < Math.max(1, limit); i--) {
            String format = entries.get(i).format;
            if (format != null && !format.isBlank() && !"unknown".equals(format)) {
                result.add(format);
            }
        }
        return result;
    }

    Path historyFile() {
        return historyFile;
    }

    private void compactIfNeeded() throws IOException {
        if (!Files.exists(historyFile)) {
            return;
        }
        List<String> lines = Files.readAllLines(historyFile, StandardCharsets.UTF_8);
        int maxLines = Math.max(historyLimit * 2, 1000);
        if (lines.size() <= maxLines) {
            return;
        }
        int keep = Math.max(historyLimit, 500);
        List<String> tail = lines.subList(Math.max(0, lines.size() - keep), lines.size());
        Files.write(historyFile, tail, StandardCharsets.UTF_8);
    }

    private List<Entry> readEntries() {
        if (!Files.exists(historyFile)) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(historyFile, StandardCharsets.UTF_8);
            List<Entry> entries = new ArrayList<>();
            for (String line : lines) {
                Entry parsed = parseEntry(line);
                if (parsed != null && parsed.script != null && !parsed.script.isBlank()) {
                    entries.add(parsed);
                }
            }
            return entries;
        } catch (IOException e) {
            System.err.println("Warning: could not read novelty history " + historyFile + ": " + e.getMessage());
            return List.of();
        }
    }

    private static Entry parseEntry(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        Map<String, String> fields = new HashMap<>();
        Matcher matcher = JSON_FIELD.matcher(json);
        while (matcher.find()) {
            fields.put(matcher.group(1), jsonUnescape(matcher.group(2)));
        }
        String script = decode(fields.get("script_b64"));
        if (script == null) {
            return null;
        }
        return new Entry(
                fields.getOrDefault("created", ""),
                fields.getOrDefault("format", "unknown"),
                decode(fields.getOrDefault("topic_b64", "")),
                script
        );
    }

    private static Similarity compare(Fingerprint a, Fingerprint b) {
        double shingle = jaccard(a.shingles, b.shingles);
        double hook = jaccard(a.hookShingles, b.hookShingles);
        double cosine = cosine(a.termFrequency, b.termFrequency);
        double structure = structuralSimilarity(a.structure, b.structure);

        double blended = (cosine * 0.58) + (structure * 0.22) + (shingle * 0.20);
        double hookWeighted = (hook * 0.72) + (cosine * 0.28);
        double overall = Math.max(shingle, Math.max(blended, hookWeighted));
        return new Similarity(overall, shingle, hook, cosine, structure);
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private static double cosine(Map<String, Integer> a, Map<String, Integer> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        double dot = 0.0;
        double aa = 0.0;
        double bb = 0.0;
        for (int value : a.values()) {
            aa += (double) value * value;
        }
        for (int value : b.values()) {
            bb += (double) value * value;
        }
        for (Map.Entry<String, Integer> entry : a.entrySet()) {
            Integer other = b.get(entry.getKey());
            if (other != null) {
                dot += (double) entry.getValue() * other;
            }
        }
        if (aa == 0.0 || bb == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(aa) * Math.sqrt(bb));
    }

    private static double structuralSimilarity(String a, String b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        int max = Math.max(a.length(), b.length());
        if (max == 0) {
            return 0.0;
        }
        int distance = levenshtein(a, b);
        return Math.max(0.0, 1.0 - ((double) distance / max));
    }

    private static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                        Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost
                );
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private static int repeatedLinePenalty(String script) {
        String[] rawLines = script.split("\\R+");
        Set<String> seen = new HashSet<>();
        int duplicates = 0;
        for (String raw : rawLines) {
            String normalized = normalize(raw);
            if (normalized.split(" ").length < 5) {
                continue;
            }
            if (!seen.add(normalized)) {
                duplicates++;
            }
        }
        return Math.min(20, duplicates * 7);
    }

    private static String excerpt(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String collapsed = text.replaceAll("\\s+", " ").trim();
        if (collapsed.length() <= maxChars) {
            return collapsed;
        }
        return collapsed.substring(0, Math.max(0, maxChars - 3)).trim() + "...";
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("https?://\\S+", " url ")
                .replaceAll("@[a-z0-9_]+", " user ")
                .replaceAll("\\d+", " # ")
                .replaceAll("[^a-z#?!]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static List<String> tokens(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String token : normalized.split(" ")) {
            if (!token.isBlank()) {
                result.add(token);
            }
        }
        return result;
    }

    private static Set<String> shingles(List<String> tokens, int width) {
        if (tokens.isEmpty()) {
            return Set.of();
        }
        int actualWidth = Math.min(Math.max(1, width), tokens.size());
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i <= tokens.size() - actualWidth; i++) {
            result.add(String.join(" ", tokens.subList(i, i + actualWidth)));
        }
        return result;
    }

    private static Map<String, Integer> termFrequency(List<String> tokens) {
        Map<String, Integer> result = new HashMap<>();
        for (String token : tokens) {
            if (token.length() <= 1 || STOP_WORDS.contains(token) || "?".equals(token) || "!".equals(token)) {
                continue;
            }
            result.merge(token, 1, Integer::sum);
        }
        return result;
    }

    private static String structure(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        String[] lines = text.split("\\R+");
        result.append("L").append(Math.min(9, lines.length));
        String[] sentences = text.trim().split("(?<=[.!?])\\s+|\\R+");
        result.append("S").append(Math.min(9, sentences.length));
        int take = Math.min(16, sentences.length);
        for (int i = 0; i < take; i++) {
            String sentence = sentences[i].trim();
            int words = sentence.isBlank() ? 0 : sentence.split("\\s+").length;
            char bucket = words <= 5 ? 'A'
                    : words <= 10 ? 'B'
                    : words <= 18 ? 'C'
                    : words <= 30 ? 'D' : 'E';
            char ending = sentence.endsWith("?") ? 'Q'
                    : sentence.endsWith("!") ? 'X' : 'P';
            result.append(bucket).append(ending);
        }
        return result.toString();
    }

    private static String sha256(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            return "";
        }
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String jsonEscape(String value) {
        return (value == null ? "" : value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private static String jsonUnescape(String value) {
        if (value == null || value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder result = new StringBuilder();
        boolean escaped = false;
        for (char c : value.toCharArray()) {
            if (escaped) {
                result.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                result.append(c);
            }
        }
        if (escaped) {
            result.append('\\');
        }
        return result.toString();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    record Result(
            boolean accepted,
            int noveltyScore,
            double closestSimilarity,
            String closestExcerpt,
            List<String> reasons
    ) {
        String feedbackForRegeneration() {
            StringBuilder feedback = new StringBuilder(
                    "Create a substantially different scenario, hook, sentence pattern, and progression.");
            if (closestExcerpt != null && !closestExcerpt.isBlank()) {
                feedback.append(" Do not mirror this recent content: \"")
                        .append(closestExcerpt.replace("\"", "'"))
                        .append("\".");
            }
            if (reasons != null && !reasons.isEmpty()) {
                feedback.append(" Rejection reason: ").append(reasons.get(0));
            }
            return feedback.toString();
        }
    }

    private record Entry(String created, String format, String topic, String script) {
    }

    private record Similarity(
            double overall,
            double shingle,
            double hook,
            double cosine,
            double structure
    ) {
    }

    private record Fingerprint(
            String hash,
            Set<String> shingles,
            Set<String> hookShingles,
            Map<String, Integer> termFrequency,
            String structure
    ) {
        static Fingerprint from(String text) {
            String normalized = normalize(text);
            List<String> tokens = tokens(normalized);
            List<String> hookTokens = tokens.subList(0, Math.min(tokens.size(), 28));
            return new Fingerprint(
                    sha256(normalized),
                    NoveltyGuard.shingles(tokens, 4),
                    NoveltyGuard.shingles(hookTokens, 3),
                    NoveltyGuard.termFrequency(tokens),
                    NoveltyGuard.structure(text)
            );
        }
    }
}
