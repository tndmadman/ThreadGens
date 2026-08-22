package redditTxtToImg;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a small, deterministic visual timeline for each narrated segment.
 * P0 intentionally uses sentence/clause timing derived from the real audio
 * duration; word-level alignment can be layered on later without changing the
 * compositor contract.
 */
final class VideoTimeline {
    record State(String focusText, double weight, int index, int total) {
    }

    private VideoTimeline() {
    }

    static List<State> fromNarration(String narration, double audioDurationSeconds) {
        String clean = narration == null ? "" : narration.replaceAll("\\s+", " ").trim();
        if (clean.isBlank()) {
            return List.of(new State("", 1.0, 0, 1));
        }

        int desiredStates;
        if (audioDurationSeconds < 3.2) {
            desiredStates = 2;
        } else if (audioDurationSeconds < 7.0) {
            desiredStates = 3;
        } else {
            desiredStates = 4;
        }

        List<String> units = sentenceUnits(clean);
        if (units.size() < desiredStates) {
            units = wordUnits(clean, desiredStates);
        }

        int stateCount = Math.max(1, Math.min(desiredStates, units.size()));
        List<String> chunks = combineEvenly(units, stateCount);
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

    private static List<String> sentenceUnits(String text) {
        List<String> units = new ArrayList<>();
        for (String part : text.split("(?<=[.!?])\\s+")) {
            String clean = part.trim();
            if (!clean.isBlank()) {
                units.add(clean);
            }
        }
        return units;
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

    private static List<String> combineEvenly(List<String> units, int count) {
        if (units.size() <= count) {
            return new ArrayList<>(units);
        }
        List<String> result = new ArrayList<>();
        int cursor = 0;
        for (int i = 0; i < count; i++) {
            int remaining = units.size() - cursor;
            int groups = count - i;
            int take = Math.max(1, (int) Math.ceil((double) remaining / groups));
            StringBuilder chunk = new StringBuilder();
            for (int j = 0; j < take && cursor < units.size(); j++, cursor++) {
                if (!chunk.isEmpty()) {
                    chunk.append(' ');
                }
                chunk.append(units.get(cursor));
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
