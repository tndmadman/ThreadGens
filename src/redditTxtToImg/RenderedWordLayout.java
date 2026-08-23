package redditTxtToImg;

import java.awt.FontMetrics;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exact word geometry emitted by the social renderers.
 *
 * Production smooth reveal consumes this sidecar instead of trying to recover
 * word boundaries from raster pixels after rendering. Word boundaries use the
 * same whitespace-delimited model as the Kokoro timing alignment.
 */
final class RenderedWordLayout {
    static final String HEADER = "threadgens-rendered-word-layout-v1";
    private static final Pattern WORD_PATTERN = Pattern.compile("\\S+");

    record Box(int left, int top, int right, int bottom) {
        Box {
            if (left < 0 || top < 0 || right < left || bottom < top) {
                throw new IllegalArgumentException("Invalid rendered word box.");
            }
        }
    }

    record Layout(String narration, int width, int height, List<Box> words) {
        Layout {
            narration = narration == null ? "" : narration;
            words = List.copyOf(words == null ? List.of() : words);
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Rendered word layout dimensions must be positive.");
            }
        }
    }

    private RenderedWordLayout() {
    }

    static Path sidecarFor(Path imagePath) {
        return imagePath.resolveSibling(imagePath.getFileName().toString() + ".words.tsv");
    }

    static String narrationForReddit(String title, String body) {
        String safeBody = body == null ? "" : body.trim();
        String safeTitle = title == null ? "" : title.trim();
        if (safeTitle.isEmpty()) {
            return safeBody;
        }
        return safeBody.isEmpty() ? safeTitle : safeTitle + ". " + safeBody;
    }

    static String narrationForVisibleText(String text) {
        return text == null ? "" : text.trim();
    }

    static List<String> words(String text) {
        String safe = text == null ? "" : text.trim();
        if (safe.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        Matcher matcher = WORD_PATTERN.matcher(safe);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return List.copyOf(result);
    }

    static int countWords(String text) {
        return words(text).size();
    }

    static boolean sameNarration(String left, String right) {
        return normalizeWhitespace(left).equals(normalizeWhitespace(right));
    }

    static void addLineBoxes(
            List<Box> destination,
            String line,
            FontMetrics metrics,
            int x,
            int baseline,
            int imageWidth,
            int imageHeight
    ) {
        if (destination == null || metrics == null || imageWidth <= 0 || imageHeight <= 0) {
            throw new IllegalArgumentException("Rendered word geometry arguments are invalid.");
        }
        String safeLine = line == null ? "" : line;
        Matcher matcher = WORD_PATTERN.matcher(safeLine);
        while (matcher.find()) {
            int before = metrics.stringWidth(safeLine.substring(0, matcher.start()));
            int through = metrics.stringWidth(safeLine.substring(0, matcher.end()));
            int left = clamp(x + before - 2, 0, imageWidth - 1);
            int right = clamp(x + through + 2, left, imageWidth - 1);
            int top = clamp(baseline - metrics.getAscent() - 3, 0, imageHeight - 1);
            int bottom = clamp(baseline + metrics.getDescent() + 3, top, imageHeight - 1);
            destination.add(new Box(left, top, right, bottom));
        }
    }

    static void write(Path imagePath, String narration, int width, int height, List<Box> boxes) throws IOException {
        Layout layout = new Layout(narration, width, height, boxes);
        int expected = countWords(layout.narration());
        if (layout.words().size() != expected) {
            throw new IOException(
                    "Rendered word layout count does not match narration: narration=" + expected
                            + ", boxes=" + layout.words().size() + ", image=" + imagePath);
        }

        String encodedNarration = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(layout.narration().getBytes(StandardCharsets.UTF_8));
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        lines.add("meta\t" + width + "\t" + height + "\t" + encodedNarration);
        for (Box box : layout.words()) {
            lines.add("word\t" + box.left() + "\t" + box.top() + "\t" + box.right() + "\t" + box.bottom());
        }
        Files.write(sidecarFor(imagePath), lines, StandardCharsets.UTF_8);
    }

    static Layout read(Path imagePath) throws IOException {
        Path path = sidecarFor(imagePath);
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.size() < 2 || !HEADER.equals(lines.get(0).trim())) {
            throw new IOException("Unsupported rendered word layout: " + path);
        }
        String[] meta = lines.get(1).split("\\t", 4);
        if (meta.length != 4 || !"meta".equals(meta[0])) {
            throw new IOException("Malformed rendered word layout metadata: " + path);
        }

        int width;
        int height;
        String narration;
        try {
            width = Integer.parseInt(meta[1]);
            height = Integer.parseInt(meta[2]);
            narration = new String(Base64.getUrlDecoder().decode(meta[3]), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw new IOException("Malformed rendered word layout metadata: " + path, e);
        }

        List<Box> boxes = new ArrayList<>();
        for (int i = 2; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\t");
            if (parts.length != 5 || !"word".equals(parts[0])) {
                throw new IOException("Malformed rendered word box at line " + (i + 1) + ": " + path);
            }
            try {
                Box box = new Box(
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3]),
                        Integer.parseInt(parts[4]));
                if (box.right() >= width || box.bottom() >= height) {
                    throw new IllegalArgumentException("Rendered word box is outside the source image.");
                }
                boxes.add(box);
            } catch (RuntimeException e) {
                throw new IOException("Invalid rendered word box at line " + (i + 1) + ": " + path, e);
            }
        }

        Layout layout = new Layout(narration, width, height, boxes);
        int expected = countWords(layout.narration());
        if (layout.words().size() != expected) {
            throw new IOException(
                    "Rendered word layout is incomplete: narration=" + expected
                            + ", boxes=" + layout.words().size() + ", sidecar=" + path);
        }
        return layout;
    }

    private static String normalizeWhitespace(String value) {
        String safe = value == null ? "" : value.trim();
        return safe.isEmpty() ? "" : safe.replaceAll("\\s+", " ");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
