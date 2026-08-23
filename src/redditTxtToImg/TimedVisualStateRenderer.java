package redditTxtToImg;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Prepares social frames for narration-synced text reveal.
 *
 * Production social renderers emit exact word rectangles beside each source
 * image. Strict production consumes those deterministic coordinates and never
 * guesses word boundaries from pixels. Raster detection remains only as a
 * compatibility fallback for non-strict synthetic/legacy callers.
 */
final class TimedVisualStateRenderer {
    static final String LAYOUT_HEADER = "threadgens-smooth-reveal-v1";

    private enum SocialKind {
        REDDIT,
        X,
        UNKNOWN
    }

    record RenderedState(Path imagePath, double weight, int index, int total) {
    }

    record WordBox(int left, int top, int right, int bottom) {
        int width() {
            return Math.max(1, right - left + 1);
        }

        int height() {
            return Math.max(1, bottom - top + 1);
        }
    }

    record RevealLayout(String narration, int sourceWidth, int sourceHeight, List<WordBox> words) {
        RevealLayout {
            narration = narration == null ? "" : narration;
            words = List.copyOf(words);
        }
    }

    private TimedVisualStateRenderer() {
    }

    static List<RenderedState> renderStates(
            Path sourceImage,
            String narration,
            ContentFormat format,
            int itemIndex,
            int itemTotal,
            double audioDurationSeconds,
            Path outputDirectory,
            String baseName
    ) throws IOException {
        return renderStates(
                sourceImage,
                narration,
                format,
                itemIndex,
                itemTotal,
                VideoTimeline.fromNarration(narration, audioDurationSeconds),
                outputDirectory,
                baseName);
    }

    static List<RenderedState> renderStates(
            Path sourceImage,
            String narration,
            ContentFormat format,
            int itemIndex,
            int itemTotal,
            List<CaptionTimeline.Scene> scenes,
            double audioDurationSeconds,
            Path outputDirectory,
            String baseName
    ) throws IOException {
        List<VideoTimeline.State> timeline = new ArrayList<>();
        List<CaptionTimeline.Scene> safeScenes = scenes == null ? List.of() : scenes;
        double safeDuration = Math.max(0.01, audioDurationSeconds);
        for (CaptionTimeline.Scene scene : safeScenes) {
            timeline.add(new VideoTimeline.State(
                    scene.text(),
                    Math.max(0.01, scene.durationSeconds()) / safeDuration,
                    timeline.size(),
                    Math.max(1, safeScenes.size())));
        }
        if (timeline.isEmpty()) {
            timeline.add(new VideoTimeline.State(narration == null ? "" : narration, 1.0, 0, 1));
        }
        return renderStates(sourceImage, narration, format, itemIndex, itemTotal, timeline, outputDirectory, baseName);
    }

    private static List<RenderedState> renderStates(
            Path sourceImage,
            String narration,
            ContentFormat format,
            int itemIndex,
            int itemTotal,
            List<VideoTimeline.State> timeline,
            Path outputDirectory,
            String baseName
    ) throws IOException {
        Files.createDirectories(outputDirectory);
        BufferedImage source = ImageIO.read(sourceImage.toFile());
        if (source == null) {
            throw new IOException("Could not decode social source frame: " + sourceImage);
        }

        SocialKind socialKind = detectSocialKind(source);
        List<WordBox> boxes = resolveNarrationWordBoxes(sourceImage, source, narration, itemIndex, socialKind);
        if (boxes.isEmpty()) {
            if (socialKind != SocialKind.UNKNOWN && requireSmoothReveal()) {
                throw new IOException(
                        "Smooth narration reveal is required, but no exact renderer word layout matched the rendered "
                                + socialKind.name().toLowerCase()
                                + " text for item " + (itemIndex + 1)
                                + ". Expected " + countWords(narration)
                                + " visible narration words. The attempt was stopped instead of guessing from raster pixels.");
            }

            // Compatibility remains available for synthetic/non-social fixtures
            // and explicit callers that did not require the production reveal.
            List<RenderedState> result = new ArrayList<>();
            for (int i = 0; i < timeline.size(); i++) {
                Path statePath = outputDirectory.resolve(baseName + "_state_" + i + ".png");
                DynamicVisualRenderer.render(sourceImage, narration, format, itemIndex, itemTotal, statePath);
                result.add(new RenderedState(statePath, timeline.get(i).weight(), i, timeline.size()));
            }
            return List.copyOf(result);
        }

        BufferedImage cleanBase = buildCleanBase(source, boxes);
        Path rawBase = outputDirectory.resolve(baseName + "_smooth_base_raw.png");
        Path statePath = outputDirectory.resolve(baseName + "_smooth_full.png");
        Path cleanPath = basePath(statePath);
        try {
            ImageIO.write(cleanBase, "png", rawBase.toFile());
            DynamicVisualRenderer.render(sourceImage, narration, format, itemIndex, itemTotal, statePath);
            DynamicVisualRenderer.render(rawBase, narration, format, itemIndex, itemTotal, cleanPath);
        } finally {
            Files.deleteIfExists(rawBase);
        }

        writeLayout(layoutPath(statePath), new RevealLayout(
                narration,
                source.getWidth(),
                source.getHeight(),
                boxes));
        return List.of(new RenderedState(statePath, 1.0, 0, 1));
    }

    static boolean hasSmoothRevealAssets(Path statePath) {
        return statePath != null
                && Files.isRegularFile(statePath)
                && Files.isRegularFile(basePath(statePath))
                && Files.isRegularFile(layoutPath(statePath));
    }

    static Path basePath(Path statePath) {
        return statePath.resolveSibling(statePath.getFileName().toString() + ".reveal-base.png");
    }

    static Path layoutPath(Path statePath) {
        return statePath.resolveSibling(statePath.getFileName().toString() + ".reveal.tsv");
    }

    static RevealLayout readLayout(Path statePath) throws IOException {
        Path path = layoutPath(statePath);
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.size() < 2 || !LAYOUT_HEADER.equals(lines.get(0).trim())) {
            throw new IOException("Unsupported smooth reveal layout: " + path);
        }
        String[] meta = lines.get(1).split("\\t", 4);
        if (meta.length != 4 || !"meta".equals(meta[0])) {
            throw new IOException("Malformed smooth reveal metadata: " + path);
        }

        int width;
        int height;
        String narration;
        try {
            width = Integer.parseInt(meta[1]);
            height = Integer.parseInt(meta[2]);
            narration = new String(java.util.Base64.getUrlDecoder().decode(meta[3]), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw new IOException("Malformed smooth reveal metadata: " + path, e);
        }

        List<WordBox> boxes = new ArrayList<>();
        for (int i = 2; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\t");
            if (parts.length != 5 || !"word".equals(parts[0])) {
                throw new IOException("Malformed smooth reveal word box at line " + (i + 1) + ": " + path);
            }
            try {
                boxes.add(new WordBox(
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3]),
                        Integer.parseInt(parts[4])));
            } catch (RuntimeException e) {
                throw new IOException("Invalid smooth reveal word box at line " + (i + 1) + ": " + path, e);
            }
        }
        return new RevealLayout(narration, width, height, boxes);
    }

    static void cleanup(List<RenderedState> states) {
        if (states == null) {
            return;
        }
        for (RenderedState state : states) {
            try {
                Files.deleteIfExists(state.imagePath());
                Files.deleteIfExists(basePath(state.imagePath()));
                Files.deleteIfExists(layoutPath(state.imagePath()));
            } catch (IOException ignored) {
                // Keep diagnostics if another process still has them open.
            }
        }
    }

    private static void writeLayout(Path path, RevealLayout layout) throws IOException {
        String encodedNarration = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(layout.narration().getBytes(StandardCharsets.UTF_8));
        List<String> lines = new ArrayList<>();
        lines.add(LAYOUT_HEADER);
        lines.add("meta\t" + layout.sourceWidth() + "\t" + layout.sourceHeight() + "\t" + encodedNarration);
        for (WordBox box : layout.words()) {
            lines.add("word\t" + box.left() + "\t" + box.top() + "\t" + box.right() + "\t" + box.bottom());
        }
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private static List<WordBox> resolveNarrationWordBoxes(
            Path sourceImage,
            BufferedImage image,
            String narration,
            int itemIndex,
            SocialKind socialKind
    ) throws IOException {
        Path exactLayoutPath = RenderedWordLayout.sidecarFor(sourceImage);
        if (Files.isRegularFile(exactLayoutPath)) {
            RenderedWordLayout.Layout exact = RenderedWordLayout.read(sourceImage);
            if (exact.width() != image.getWidth() || exact.height() != image.getHeight()) {
                throw new IOException(
                        "Rendered word layout dimensions do not match source image: " + exactLayoutPath);
            }
            if (!RenderedWordLayout.sameNarration(exact.narration(), narration)) {
                throw new IOException(
                        "Rendered word layout narration does not match TTS narration for item " + (itemIndex + 1)
                                + ". Refusing to reveal text against the wrong timing sequence.");
            }
            int expected = countWords(narration);
            if (exact.words().size() != expected) {
                throw new IOException(
                        "Rendered word layout count does not match narration for item " + (itemIndex + 1)
                                + ": narration=" + expected + ", boxes=" + exact.words().size() + ".");
            }
            List<WordBox> result = new ArrayList<>();
            for (RenderedWordLayout.Box box : exact.words()) {
                result.add(new WordBox(box.left(), box.top(), box.right(), box.bottom()));
            }
            return List.copyOf(result);
        }

        if (socialKind != SocialKind.UNKNOWN && requireSmoothReveal()) {
            System.out.println("Exact renderer word layout is missing for strict social frame item "
                    + (itemIndex + 1) + ": " + exactLayoutPath);
            return List.of();
        }

        return detectNarrationWordBoxes(image, narration, itemIndex, socialKind);
    }

    private static List<WordBox> detectNarrationWordBoxes(
            BufferedImage image,
            String narration,
            int itemIndex,
            SocialKind socialKind
    ) {
        int expectedWords = countWords(narration);
        if (expectedWords <= 0 || socialKind == SocialKind.UNKNOWN) {
            return List.of();
        }

        List<WordBox> best = List.of();
        int bestDifference = Integer.MAX_VALUE;
        for (int gap = 3; gap <= 24; gap++) {
            List<WordBox> candidate = detectWordBoxes(image, socialKind, itemIndex, gap);
            int difference = Math.abs(candidate.size() - expectedWords);
            if (!candidate.isEmpty() && difference < bestDifference) {
                best = candidate;
                bestDifference = difference;
            }
            if (difference == 0) {
                break;
            }
        }

        if (bestDifference != 0) {
            System.out.println("Compatibility raster word mapping did not match item " + (itemIndex + 1)
                    + ": narration=" + expectedWords
                    + " words, closest raster detection=" + best.size() + " words.");
            return List.of();
        }
        return best;
    }

    private static List<WordBox> detectWordBoxes(
            BufferedImage image,
            SocialKind socialKind,
            int itemIndex,
            int wordGap
    ) {
        int w = image.getWidth();
        int h = image.getHeight();
        int x0;
        int x1 = clamp((int) Math.round(w * 0.94), 1, w);
        int y0;
        int y1 = clamp((int) Math.round(h * 0.76), 1, h);

        if (socialKind == SocialKind.REDDIT) {
            x0 = clamp((int) Math.round(w * (itemIndex == 0 ? 0.085 : 0.145)), 0, w - 1);
            y0 = clamp((int) Math.round(h * 0.195), 0, h - 1);
        } else {
            x0 = clamp((int) Math.round(w * (itemIndex == 0 ? 0.04 : 0.19)), 0, w - 1);
            y0 = clamp((int) Math.round(h * (itemIndex == 0 ? 0.17 : 0.19)), 0, h - 1);
        }

        int minimumBrightPixels = Math.max(4, (x1 - x0) / 220);
        List<Integer> activeRows = new ArrayList<>();
        for (int y = y0; y < y1; y++) {
            int bright = 0;
            for (int x = x0; x < x1; x += 2) {
                if (isTextLike(image.getRGB(x, y))) {
                    bright++;
                }
            }
            if (bright >= minimumBrightPixels) {
                activeRows.add(y);
            }
        }
        if (activeRows.isEmpty()) {
            return List.of();
        }

        int minimumBandHeight = Math.max(7, h / 100);
        List<int[]> bands = new ArrayList<>();
        int start = activeRows.get(0);
        int previous = start;
        for (int i = 1; i <= activeRows.size(); i++) {
            boolean end = i == activeRows.size();
            int current = end ? Integer.MAX_VALUE : activeRows.get(i);
            if (!end && current - previous <= 6) {
                previous = current;
                continue;
            }
            int bandHeight = previous - start + 1;
            if (bandHeight >= minimumBandHeight && bandHeight <= 92) {
                bands.add(new int[]{start, previous});
            }
            if (!end) {
                start = current;
                previous = current;
            }
        }

        List<WordBox> result = new ArrayList<>();
        for (int[] band : bands) {
            List<Integer> activeColumns = new ArrayList<>();
            for (int x = x0; x < x1; x++) {
                boolean active = false;
                for (int y = band[0]; y <= band[1]; y++) {
                    if (isTextLike(image.getRGB(x, y))) {
                        active = true;
                        break;
                    }
                }
                if (active) {
                    activeColumns.add(x);
                }
            }
            if (activeColumns.isEmpty()) {
                continue;
            }

            int wordStart = activeColumns.get(0);
            int last = wordStart;
            for (int i = 1; i <= activeColumns.size(); i++) {
                boolean end = i == activeColumns.size();
                int current = end ? Integer.MAX_VALUE : activeColumns.get(i);
                if (!end && current - last <= wordGap) {
                    last = current;
                    continue;
                }
                if (last - wordStart + 1 >= 3) {
                    int left = clamp(wordStart - 3, x0, x1 - 1);
                    int right = clamp(last + 3, left, x1 - 1);
                    int top = clamp(band[0] - 5, y0, y1 - 1);
                    int bottom = clamp(band[1] + 5, top, y1 - 1);
                    result.add(new WordBox(left, top, right, bottom));
                }
                if (!end) {
                    wordStart = current;
                    last = current;
                }
            }
        }
        result.sort(Comparator.comparingInt(WordBox::top).thenComparingInt(WordBox::left));
        return List.copyOf(result);
    }

    private static BufferedImage buildCleanBase(BufferedImage source, List<WordBox> boxes) {
        BufferedImage base = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = base.createGraphics();
        g.drawImage(source, 0, 0, null);
        for (WordBox box : boxes) {
            g.setColor(sampleLocalBackground(source, box));
            g.fillRect(box.left(), box.top(), box.width(), box.height());
        }
        g.dispose();
        return base;
    }

    private static Color sampleLocalBackground(BufferedImage image, WordBox box) {
        long r = 0;
        long g = 0;
        long b = 0;
        int count = 0;
        int x0 = clamp(box.left() - 10, 0, image.getWidth() - 1);
        int x1 = clamp(box.right() + 10, x0 + 1, image.getWidth());
        int y0 = clamp(box.top() - 7, 0, image.getHeight() - 1);
        int y1 = clamp(box.bottom() + 7, y0 + 1, image.getHeight());
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                Color c = new Color(image.getRGB(x, y));
                int max = Math.max(c.getRed(), Math.max(c.getGreen(), c.getBlue()));
                int min = Math.min(c.getRed(), Math.min(c.getGreen(), c.getBlue()));
                int average = (c.getRed() + c.getGreen() + c.getBlue()) / 3;
                if (average <= 105 && max - min <= 38) {
                    r += c.getRed();
                    g += c.getGreen();
                    b += c.getBlue();
                    count++;
                }
            }
        }
        if (count == 0) {
            return new Color(31, 31, 33);
        }
        return new Color((int) (r / count), (int) (g / count), (int) (b / count));
    }

    private static SocialKind detectSocialKind(BufferedImage image) {
        if (looksLikeReddit(image)) {
            return SocialKind.REDDIT;
        }
        if (looksLikeX(image)) {
            return SocialKind.X;
        }
        return SocialKind.UNKNOWN;
    }

    private static boolean looksLikeReddit(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int x0 = clamp((int) Math.round(w * 0.72), 0, w - 1);
        int x1 = clamp((int) Math.round(w * 0.94), x0 + 1, w);
        int y0 = clamp((int) Math.round(h * 0.145), 0, h - 1);
        int y1 = clamp((int) Math.round(h * 0.215), y0 + 1, h);
        int branded = 0;
        int sampled = 0;
        for (int y = y0; y < y1; y += 2) {
            for (int x = x0; x < x1; x += 2) {
                Color c = new Color(image.getRGB(x, y));
                sampled++;
                if (c.getRed() > 145
                        && c.getRed() > c.getGreen() * 1.35
                        && c.getRed() > c.getBlue() * 1.35) {
                    branded++;
                }
            }
        }
        return sampled > 0 && branded >= Math.max(18, sampled / 120);
    }

    private static boolean looksLikeX(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int black = 0;
        int sampled = 0;
        int x0 = clamp((int) Math.round(w * 0.05), 0, w - 1);
        int x1 = clamp((int) Math.round(w * 0.95), x0 + 1, w);
        int y0 = clamp((int) Math.round(h * 0.035), 0, h - 1);
        int y1 = clamp((int) Math.round(h * 0.105), y0 + 1, h);
        for (int y = y0; y < y1; y += 4) {
            for (int x = x0; x < x1; x += 4) {
                Color c = new Color(image.getRGB(x, y));
                sampled++;
                int average = (c.getRed() + c.getGreen() + c.getBlue()) / 3;
                int max = Math.max(c.getRed(), Math.max(c.getGreen(), c.getBlue()));
                int min = Math.min(c.getRed(), Math.min(c.getGreen(), c.getBlue()));
                if (average <= 12 && max - min <= 12) {
                    black++;
                }
            }
        }
        if (sampled == 0 || black < sampled * 0.55) {
            return false;
        }

        int brightCenter = 0;
        int cx0 = clamp((int) Math.round(w * 0.43), 0, w - 1);
        int cx1 = clamp((int) Math.round(w * 0.57), cx0 + 1, w);
        int cy0 = clamp((int) Math.round(h * 0.055), 0, h - 1);
        int cy1 = clamp((int) Math.round(h * 0.115), cy0 + 1, h);
        for (int y = cy0; y < cy1; y += 2) {
            for (int x = cx0; x < cx1; x += 2) {
                Color c = new Color(image.getRGB(x, y));
                int average = (c.getRed() + c.getGreen() + c.getBlue()) / 3;
                int max = Math.max(c.getRed(), Math.max(c.getGreen(), c.getBlue()));
                int min = Math.min(c.getRed(), Math.min(c.getGreen(), c.getBlue()));
                if (average >= 190 && max - min <= 45) {
                    brightCenter++;
                }
            }
        }
        return brightCenter >= Math.max(6, (w * h) / 250_000);
    }

    private static boolean isTextLike(int rgb) {
        Color c = new Color(rgb);
        int max = Math.max(c.getRed(), Math.max(c.getGreen(), c.getBlue()));
        int min = Math.min(c.getRed(), Math.min(c.getGreen(), c.getBlue()));
        int average = (c.getRed() + c.getGreen() + c.getBlue()) / 3;
        return average >= 118 && (max - min) <= 54;
    }

    private static boolean requireSmoothReveal() {
        return truthy(System.getProperty("threadgens.requireSmoothReveal"))
                || truthy(System.getenv("THREADGENS_REQUIRE_SMOOTH_REVEAL"));
    }

    private static boolean truthy(String value) {
        if (value == null) {
            return false;
        }
        return switch (value.trim().toLowerCase()) {
            case "1", "true", "yes", "y", "on" -> true;
            default -> false;
        };
    }

    private static int countWords(String narration) {
        return RenderedWordLayout.countWords(narration);
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
