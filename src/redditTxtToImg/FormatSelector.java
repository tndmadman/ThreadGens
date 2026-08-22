package redditTxtToImg;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Selects an auto format that fits the prompt while avoiding recent repetition. */
final class FormatSelector {
    private FormatSelector() {
    }

    static ContentFormat resolve(
            String requested,
            NoveltyGuard history,
            String title,
            String topic
    ) {
        if (requested != null && !requested.isBlank() && !"auto".equalsIgnoreCase(requested.trim())) {
            return ContentFormat.resolve(requested, history);
        }

        List<ContentFormat> candidates = candidatesFor(title, topic);
        List<String> recent = history == null ? List.of() : history.recentFormats(24);
        Map<ContentFormat, Integer> counts = new EnumMap<>(ContentFormat.class);
        Map<ContentFormat, Integer> recency = new EnumMap<>(ContentFormat.class);
        for (ContentFormat format : ContentFormat.values()) {
            counts.put(format, 0);
            recency.put(format, Integer.MAX_VALUE);
        }
        for (int i = 0; i < recent.size(); i++) {
            ContentFormat parsed = ContentFormat.fromIdOrNull(recent.get(i));
            if (parsed == null) {
                continue;
            }
            counts.put(parsed, counts.get(parsed) + 1);
            if (recency.get(parsed) == Integer.MAX_VALUE) {
                recency.put(parsed, i);
            }
        }

        ContentFormat best = candidates.get(0);
        for (ContentFormat candidate : candidates) {
            if (counts.get(candidate) < counts.get(best)) {
                best = candidate;
            } else if (counts.get(candidate).equals(counts.get(best))
                    && recency.get(candidate) > recency.get(best)) {
                best = candidate;
            }
        }
        return best;
    }

    static List<ContentFormat> candidatesFor(String title, String topic) {
        String text = ((title == null ? "" : title) + " " + (topic == null ? "" : topic))
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9?!']+", " ")
                .replaceAll("\\s+", " ")
                .trim();

        List<ContentFormat> candidates = new ArrayList<>();
        if (containsAny(text,
                "am i wrong", "aita", "who is right", "who's right", "debate", "agree or disagree",
                "change my mind", "unpopular opinion", "tell me i'm wrong", "tell me im wrong")) {
            candidates.add(ContentFormat.DEBATE);
            candidates.add(ContentFormat.ESCALATING_CONVERSATION);
            return candidates;
        }
        if (containsAny(text,
                "finish this story", "continue this story", "finish the story", "continue the story",
                "what happened next", "story in the comments", "story in the replies")) {
            candidates.add(ContentFormat.THREAD_STORY);
            candidates.add(ContentFormat.ESCALATING_CONVERSATION);
            candidates.add(ContentFormat.CONFESSION);
            return candidates;
        }
        if (containsAny(text,
                "wrong answers only", "wrong answer only", "best answer", "best answers", "give advice",
                "any advice", "need advice", "what should i do", "what would you do", "tips", "recommend")) {
            candidates.add(ContentFormat.BEST_ANSWERS);
            candidates.add(ContentFormat.DEBATE);
            return candidates;
        }
        if (containsAny(text,
                "confession", "i need to admit", "i have to admit", "i never told", "i'm ashamed",
                "im ashamed", "i screwed up", "i messed up", "off my chest")) {
            candidates.add(ContentFormat.CONFESSION);
            candidates.add(ContentFormat.THREAD_STORY);
            return candidates;
        }
        if (text.endsWith("?") || containsAny(text,
                "what is", "what are", "why do", "why does", "how do", "how did", "which one")) {
            candidates.add(ContentFormat.BEST_ANSWERS);
            candidates.add(ContentFormat.DEBATE);
            candidates.add(ContentFormat.ESCALATING_CONVERSATION);
            return candidates;
        }

        candidates.add(ContentFormat.THREAD_STORY);
        candidates.add(ContentFormat.CONFESSION);
        candidates.add(ContentFormat.DEBATE);
        candidates.add(ContentFormat.BEST_ANSWERS);
        candidates.add(ContentFormat.ESCALATING_CONVERSATION);
        return candidates;
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
