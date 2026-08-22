package redditTxtToImg;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a deterministic visual timeline for each narrated segment.
 *
 * State count scales with real audio duration and narration text is divided into
 * near-equal spoken chunks, keeping ordinary visual holds near 2.5 seconds even
 * when one sentence is much longer than the others. Exact word timestamps can
 * replace these proportional timings later without changing the compositor API.
 */
final class VideoTimeline {
    private static final double TARGET_STATE_SECONDS = 2.5;
    private static final int MAX_STATES = 20;

    record State(String focusText, double weight, int index, int total) {
    }

    private VideoTimeline() {
    }

    static List<State> fromNarration(String narration, double audioDurationSeconds) {
        String clean = narration == null ? "" : narration.replaceAll("\\s+", " ").trim();
        if (clean.isBlank()) {
            return List.of(new State("", 1.0, 0, 1));
        }

        double safeDuration = Math.max(0.1, audioDurationSeconds);
        int desiredStates = Math.max(2,
                Math.min(MAX_STATES, (int) Math.ceil(safeDuration / TARGET_STATE_SECONDS)));

        // Equal word groups are deliberate here. Sentence-only groups can leave a
        // single long sentence unchanged for most of a clip, violating the P0
        // cadence requirement.
        List<String> chunks = wordUnits(clean, desiredStates);
        double totalWeight = 0.0;
        List<Double> rawWeights = new ArrayList<>();
        for (String chunk : chunks) {
            double weight = Math.max(1.0, wordCount(chunk));
            rawWeights.add(weight);
            totalWeight += weight;
        }

        List<State> states = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            double normalized = totalWeight <= 0.0 ? 1.0 / chunks.size() : rawWeights.get(i) / totalWeight;
            states.add(new State(chunks.get(i), normalized, i, chunks.size()));
        }
        return List.copyOf(states);
    }

    private static List<String> wordUnits(String text, int desiredStates) {
        String[] words = text.split("\\s+");
        int count = Math.max(1, Math.min(desiredStates, words.length));
        List<String> result = new ArrayList<>();
        int cursor = 0;
        for (int i = 0; i < count; i++) {
            int remainingWords = words.length - cursor;
            int remainingGroups = count - i;
            int take = Math.max(1, (int) Math.ceil((double) remainingWords / remainingGroups));
            StringBuilder chunk = new StringBuilder();
            for (int j = 0; j < take && cursor < words.length; j++, cursor++) {
                if (!chunk.isEmpty()) {
                    chunk.append(' ');
                }
                chunk.append(words[cursor]);
            }
            result.add(chunk.toString());
        }
        return result;
    }

    private static int wordCount(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return value.trim().split("\\s+").length;
    }
}
