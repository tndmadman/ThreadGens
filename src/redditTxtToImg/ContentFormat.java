package redditTxtToImg;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Distinct content/editing formats used by the P0 pipeline.
 *
 * The prompt guidance changes narrative structure while the dynamic compositor
 * changes presentation grammar, so formats are more than cosmetic skins.
 */
enum ContentFormat {
    THREAD_STORY(
            "thread_story",
            "THREAD STORY",
            "Write this as a threaded story: the first entry establishes a concrete event, later replies add genuinely new information, disagreement, or consequences, and the final entries resolve or sharply reframe the situation. Avoid repetitive one-liners and avoid using the same reply cadence throughout."
    ),
    CONFESSION(
            "confession",
            "CONFESSION",
            "Write this as a first-person confession. The opening must contain a specific personal hook, the middle should reveal details progressively, and later replies should react to different parts of the confession instead of repeating the same judgment. Keep the narrative cohesive rather than turning every line into a punchline."
    ),
    DEBATE(
            "debate",
            "DEBATE",
            "Write this as a two-sided debate. Alternate meaningfully different positions, make each side answer the strongest point from the other side, and let the discussion evolve instead of restating slogans. Include concrete reasoning and at least one concession or change in position."
    ),
    BEST_ANSWERS(
            "best_answers",
            "BEST ANSWERS",
            "Write this as a question followed by independent best answers. Each answer must take a different angle, example, or strategy. Do not make the answers form one continuous story and do not reuse the same joke or sentence pattern."
    ),
    ESCALATING_CONVERSATION(
            "escalating_conversation",
            "CONVERSATION",
            "Write this as an escalating conversation. Use shorter turns, make each new turn respond directly to the previous one, reveal context in stages, and increase stakes or absurdity without repeating wording. Finish with a clear payoff or resolution."
    );

    private final String id;
    private final String label;
    private final String promptGuide;

    ContentFormat(String id, String label, String promptGuide) {
        this.id = id;
        this.label = label;
        this.promptGuide = promptGuide;
    }

    String id() {
        return id;
    }

    String label() {
        return label;
    }

    String promptGuide() {
        return promptGuide;
    }

    String augmentTopic(String originalTopic, String retryHint) {
        StringBuilder result = new StringBuilder();
        result.append("Content-format instruction (do not quote this instruction in the output): ")
                .append(promptGuide);
        if (retryHint != null && !retryHint.isBlank()) {
            result.append(" Novelty correction: ").append(retryHint.trim());
        }
        result.append(" Original topic: ")
                .append(originalTopic == null || originalTopic.isBlank() ? "general discussion" : originalTopic.trim());
        return result.toString();
    }

    static ContentFormat resolve(String requested, NoveltyGuard history) {
        if (requested != null && !requested.isBlank() && !"auto".equalsIgnoreCase(requested.trim())) {
            String normalized = requested.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            for (ContentFormat format : values()) {
                if (format.id.equals(normalized) || format.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                    return format;
                }
            }
            throw new IllegalArgumentException(
                    "Unknown --format " + requested + ". Supported: auto, " + supportedValues());
        }

        List<String> recent = history == null ? List.of() : history.recentFormats(20);
        Map<ContentFormat, Integer> counts = new EnumMap<>(ContentFormat.class);
        Map<ContentFormat, Integer> mostRecentIndex = new EnumMap<>(ContentFormat.class);
        for (ContentFormat format : values()) {
            counts.put(format, 0);
            mostRecentIndex.put(format, Integer.MAX_VALUE);
        }

        for (int i = 0; i < recent.size(); i++) {
            ContentFormat parsed = fromIdOrNull(recent.get(i));
            if (parsed == null) {
                continue;
            }
            counts.put(parsed, counts.get(parsed) + 1);
            if (mostRecentIndex.get(parsed) == Integer.MAX_VALUE) {
                mostRecentIndex.put(parsed, i);
            }
        }

        ContentFormat best = values()[0];
        for (ContentFormat candidate : values()) {
            int candidateCount = counts.get(candidate);
            int bestCount = counts.get(best);
            if (candidateCount < bestCount) {
                best = candidate;
                continue;
            }
            if (candidateCount == bestCount
                    && mostRecentIndex.get(candidate) > mostRecentIndex.get(best)) {
                best = candidate;
            }
        }
        return best;
    }

    static ContentFormat fromIdOrNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (ContentFormat format : values()) {
            if (format.id.equals(normalized)
                    || format.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return format;
            }
        }
        return null;
    }

    static String supportedValues() {
        List<String> ids = new ArrayList<>();
        for (ContentFormat format : values()) {
            ids.add(format.id);
        }
        return String.join(", ", ids);
    }
}
