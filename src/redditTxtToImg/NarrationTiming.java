package redditTxtToImg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/** Reads model timing emitted beside TTS audio and provides a safe fallback. */
final class NarrationTiming {
    static final String HEADER = "threadgens-kokoro-timing-v1";

    record Word(String text, double startSeconds, double endSeconds) {
        Word {
            text = text == null ? "" : text;
            if (!Double.isFinite(startSeconds) || !Double.isFinite(endSeconds)
                    || startSeconds < 0.0 || endSeconds <= startSeconds) {
                throw new IllegalArgumentException("Invalid narration word timing.");
            }
        }
    }

    private NarrationTiming() {
    }

    static Path sidecarFor(Path audioFile) {
        String name = audioFile.getFileName().toString();
        String stem = name.toLowerCase(Locale.ROOT).endsWith(".wav")
                ? name.substring(0, name.length() - 4)
                : name;
        return audioFile.resolveSibling(stem + ".timing.tsv");
    }

    static List<Word> load(Path audioFile) throws IOException {
        Path sidecar = sidecarFor(audioFile);
        if (!Files.isRegularFile(sidecar)) {
            return List.of();
        }
        List<String> lines = Files.readAllLines(sidecar, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !HEADER.equals(lines.get(0).trim())) {
            throw new IOException("Unsupported narration timing sidecar: " + sidecar);
        }
        List<Word> result = new ArrayList<>();
        double previousStart = -1.0;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\t", 4);
            if (parts.length != 4 || !"word".equals(parts[0])) {
                throw new IOException("Malformed narration timing sidecar at line " + (i + 1) + ": " + sidecar);
            }
            try {
                double start = Double.parseDouble(parts[1]);
                double end = Double.parseDouble(parts[2]);
                String text = new String(Base64.getUrlDecoder().decode(parts[3]), StandardCharsets.UTF_8);
                if (start + 0.0001 < previousStart) {
                    throw new IOException("Narration timing is not monotonic at line " + (i + 1) + ": " + sidecar);
                }
                result.add(new Word(text, start, end));
                previousStart = start;
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid narration timing at line " + (i + 1) + ": " + sidecar, e);
            }
        }
        return List.copyOf(result);
    }

    static List<Word> estimate(String narration, double durationSeconds) {
        CaptionTimeline timeline = CaptionTimeline.create(narration, durationSeconds, "word", 1);
        List<Word> result = new ArrayList<>();
        for (CaptionTimeline.Cue cue : timeline.cues()) {
            for (CaptionTimeline.Word word : cue.words()) {
                result.add(new Word(word.text(), word.startSeconds(), word.endSeconds()));
            }
        }
        return List.copyOf(result);
    }

    static List<Word> fitToCount(
            List<Word> measured,
            String narration,
            double durationSeconds,
            int expectedCount
    ) {
        if (expectedCount <= 0) {
            return List.of();
        }
        if (measured != null && measured.size() == expectedCount) {
            return List.copyOf(measured);
        }
        List<Word> estimated = estimate(narration, durationSeconds);
        if (estimated.size() == expectedCount) {
            return estimated;
        }

        // Last-resort monotonic mapping. This should only be used when rendered
        // text was intentionally truncated or a non-Kokoro engine has unusual
        // tokenization; it still keeps the reveal smooth and bounded.
        List<String> visibleWords = splitWords(narration);
        List<Word> result = new ArrayList<>();
        for (int i = 0; i < expectedCount; i++) {
            double start = durationSeconds * i / expectedCount;
            double end = durationSeconds * (i + 1.0) / expectedCount;
            String text = i < visibleWords.size() ? visibleWords.get(i) : "";
            result.add(new Word(text, start, Math.max(start + 0.005, end)));
        }
        return List.copyOf(result);
    }

    private static List<String> splitWords(String narration) {
        String text = narration == null ? "" : narration.trim();
        if (text.isEmpty()) {
            return List.of();
        }
        return List.of(text.split("\\s+"));
    }
}
