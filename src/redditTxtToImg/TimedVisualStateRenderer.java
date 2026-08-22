package redditTxtToImg;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Converts one social frame into narration-timed visual states.
 *
 * Production Reddit states progressively uncover the text that is already
 * rasterized inside the rendered Reddit card. We intentionally do not draw a
 * second focus-text card here: the social frame itself is the readable content.
 */
final class TimedVisualStateRenderer {
    private static final double TARGET_REVEAL_STEP_SECONDS = 0.55;

    record RenderedState(Path imagePath, double weight, int index, int total) {
    }

    private record TextBand(int top, int bottom, int minX, int maxX, double weight) {
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
        List<VideoTimeline.State> states = new ArrayList<>();
        List<CaptionTimeline.Scene> safeScenes = scenes == null ? List.of() : scenes;
        double safeDuration = Math.max(0.01, audioDurationSeconds);

        // The old visual states changed only every ~2.5 seconds, which made any
        // in-image reveal jump far ahead of the narration. Subdivide each timed
        // scene so the visible text advances roughly twice per second while
        // preserving the original measured narration timing.
        for (CaptionTimeline.Scene scene : safeScenes) {
            double sceneDuration = Math.max(0.01, scene.durationSeconds());
            int slices = Math.max(1, (int) Math.ceil(sceneDuration / TARGET_REVEAL_STEP_SECONDS));
            double sliceWeight = (sceneDuration / slices) / safeDuration;
            for (int slice = 0; slice < slices; slice++) {
                states.add(new VideoTimeline.State(scene.text(), sliceWeight, states.size(), 1));
            }
        }
        if (states.isEmpty()) {
            states.add(new VideoTimeline.State(narration == null ? "" : narration, 1.0, 0, 1));
        }

        // Normalize after subdivision and give every state the final total.
        double sum = states.stream().mapToDouble(VideoTimeline.State::weight).sum();
        List<VideoTimeline.State> normalized = new ArrayList<>();
        for (int i = 0; i < states.size(); i++) {
            VideoTimeline.State state = states.get(i);
            normalized.add(new VideoTimeline.State(
                    state.focusText(),
                    sum <= 0.0 ? 1.0 / states.size() : state.weight() / sum,
                    i,
                    states.size()));
        }
        return renderStates(
                sourceImage,
                narration,
                format,
                itemIndex,
                itemTotal,
                normalized,
                outputDirectory,
                baseName);
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

        boolean reddit = looksLikeReddit(source, itemIndex);
        List<TextBand> redditTextBands = reddit ? detectRedditTextBands(source, itemIndex) : List.of();
        double cumulativeProgress = 0.0;
        List<RenderedState> rendered = new ArrayList<>();

        for (VideoTimeline.State state : timeline) {
            cumulativeProgress = Math.min(1.0, cumulativeProgress + Math.max(0.0, state.weight()));
            BufferedImage sourceState = copy(source);
            if (reddit && !redditTextBands.isEmpty()) {
                applyProgressiveReveal(sourceState, redditTextBands, cumulativeProgress);
            }

            Path temporarySource = outputDirectory.resolve(baseName + "_source_" + state.index() + ".png");
            Path statePath = outputDirectory.resolve(baseName + "_state_" + state.index() + ".png");
            try {
                ImageIO.write(sourceState, "png", temporarySource.toFile());
                DynamicVisualRenderer.render(
                        temporarySource,
                        narration,
                        format,
                        itemIndex,
                        itemTotal,
                        statePath);

                BufferedImage composed = ImageIO.read(statePath.toFile());
                if (composed == null) {
                    throw new IOException("Could not decode dynamic state frame: " + statePath);
                }
                removeDuplicateNarrationArea(composed, format, itemIndex);
                ImageIO.write(composed, "png", statePath.toFile());
            } finally {
                Files.deleteIfExists(temporarySource);
            }

            rendered.add(new RenderedState(
                    statePath,
                    state.weight(),
                    state.index(),
                    state.total()
            ));
        }
        return List.copyOf(rendered);
    }

    static void cleanup(List<RenderedState> states) {
        if (states == null) {
            return;
        }
        for (RenderedState state : states) {
            try {
                Files.deleteIfExists(state.imagePath());
            } catch (IOException ignored) {
                // Leave a diagnostic frame behind if another process still owns it.
            }
        }
    }

    private static BufferedImage copy(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return copy;
    }

    /**
     * Reddit currently has a distinctive brand badge in the upper-right of its
     * rendered card. Detect that rather than applying Reddit geometry blindly
     * to X frames.
     */
    private static boolean looksLikeReddit(BufferedImage image, int itemIndex) {
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

    /**
     * Find the real rasterized text rows in the Reddit comment body. This is
     * deliberately pixel-based so it follows the renderer's actual font,
     * wrapping, title/body layout, and top/center alignment without re-drawing
     * a duplicate string elsewhere on screen.
     */
    private static List<TextBand> detectRedditTextBands(BufferedImage image, int itemIndex) {
        int w = image.getWidth();
        int h = image.getHeight();
        int x0 = clamp((int) Math.round(w * (itemIndex == 0 ? 0.085 : 0.145)), 0, w - 1);
        int x1 = clamp((int) Math.round(w * 0.91), x0 + 1, w);
        // Start below the author/reply pill. At 1080x1920 this is ~451px,
        // immediately above the first reply baseline and the OP title/body area.
        int y0 = clamp((int) Math.round(h * 0.235), 0, h - 1);
        int y1 = clamp((int) Math.round(h * 0.77), y0 + 1, h);
        int minimumBrightPixels = Math.max(5, (x1 - x0) / 175);

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

        List<TextBand> result = new ArrayList<>();
        int start = activeRows.get(0);
        int previous = start;
        for (int i = 1; i <= activeRows.size(); i++) {
            boolean end = i == activeRows.size();
            int current = end ? Integer.MAX_VALUE : activeRows.get(i);
            if (!end && current - previous <= 6) {
                previous = current;
                continue;
            }
            addBand(image, result, x0, x1, start, previous);
            if (!end) {
                start = current;
                previous = current;
            }
        }
        return List.copyOf(result);
    }

    private static void addBand(
            BufferedImage image,
            List<TextBand> result,
            int x0,
            int x1,
            int top,
            int bottom
    ) {
        int bandHeight = bottom - top + 1;
        if (bandHeight < 8 || bandHeight > 86) {
            return;
        }
        int minX = x1;
        int maxX = x0;
        for (int y = top; y <= bottom; y++) {
            for (int x = x0; x < x1; x++) {
                if (isTextLike(image.getRGB(x, y))) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                }
            }
        }
        if (maxX - minX < 18) {
            return;
        }
        result.add(new TextBand(top, bottom, minX, maxX, Math.max(1.0, maxX - minX + 1.0)));
    }

    private static boolean isTextLike(int rgb) {
        Color c = new Color(rgb);
        int max = Math.max(c.getRed(), Math.max(c.getGreen(), c.getBlue()));
        int min = Math.min(c.getRed(), Math.min(c.getGreen(), c.getBlue()));
        int average = (c.getRed() + c.getGreen() + c.getBlue()) / 3;
        return average >= 138 && (max - min) <= 58;
    }

    private static void applyProgressiveReveal(
            BufferedImage image,
            List<TextBand> bands,
            double progress
    ) {
        double totalWeight = bands.stream().mapToDouble(TextBand::weight).sum();
        if (totalWeight <= 0.0) {
            return;
        }
        double remaining = Math.max(0.0, Math.min(1.0, progress)) * totalWeight;
        Graphics2D g = image.createGraphics();
        configure(g);
        Color background = sampleCardBackground(image);

        for (TextBand band : bands) {
            if (remaining >= band.weight()) {
                remaining -= band.weight();
                continue;
            }

            double fraction = remaining <= 0.0 ? 0.0 : remaining / band.weight();
            int width = Math.max(1, band.maxX() - band.minX() + 1);
            int maskStart = fraction <= 0.0
                    ? band.minX() - 5
                    : band.minX() + (int) Math.floor(width * fraction) + 2;
            maskStart = clamp(maskStart, 0, image.getWidth() - 1);
            int maskRight = clamp((int) Math.round(image.getWidth() * 0.91) + 4,
                    maskStart + 1, image.getWidth());
            int maskTop = clamp(band.top() - 5, 0, image.getHeight() - 1);
            int maskBottom = clamp(band.bottom() + 7, maskTop + 1, image.getHeight());

            g.setColor(background);
            g.fillRect(maskStart, maskTop, maskRight - maskStart, maskBottom - maskTop);
            remaining = 0.0;
        }
        g.dispose();
    }

    /**
     * Both OP and reply cards end at x=1016 on the default 1080 renderer and
     * their wrapped text ends near x=974. Sampling at 93.5% width therefore
     * lands in a stable, empty strip of the actual card. Half-height is also
     * safely inside the card and away from header/footer decoration.
     */
    private static Color sampleCardBackground(BufferedImage image) {
        int sampleX = clamp((int) Math.round(image.getWidth() * 0.935), 0, image.getWidth() - 1);
        int sampleY = clamp((int) Math.round(image.getHeight() * 0.50), 0, image.getHeight() - 1);
        Color sampled = new Color(image.getRGB(sampleX, sampleY));
        int brightness = (sampled.getRed() + sampled.getGreen() + sampled.getBlue()) / 3;
        if (brightness <= 115) {
            return sampled;
        }

        // Fail visually dark rather than ever painting a bright unread-text mask
        // if a future renderer layout places decoration at the preferred sample.
        return new Color(31, 31, 33);
    }

    /**
     * DynamicVisualRenderer used to add another narration excerpt below the
     * social image for every non-thread format. Remove only that redundant area;
     * format framing and the progressively revealed social card remain intact.
     */
    private static void removeDuplicateNarrationArea(
            BufferedImage image,
            ContentFormat format,
            int itemIndex
    ) {
        if (format == ContentFormat.THREAD_STORY) {
            return;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        Graphics2D g = image.createGraphics();
        configure(g);

        switch (format) {
            case CONFESSION -> {
                int pad = Math.max(32, w / 18);
                int bottomSpace = Math.max(420, h / 4);
                int y = h - bottomSpace + 30;
                paintBackgroundPatch(g, image, pad - 4, y, w - (pad * 2) + 8, h - y - 24, pad - 12, y);
            }
            case DEBATE -> {
                int gutter = Math.max(28, w / 28);
                int panelWidth = (int) Math.round(w * 0.74);
                int panelHeight = (int) Math.round(h * 0.66);
                int panelX = itemIndex % 2 == 0 ? gutter : w - panelWidth - gutter;
                int panelY = Math.max(190, h / 9);
                int y = panelY + panelHeight + 34;
                paintBackgroundPatch(g, image, panelX - 4, y, panelWidth + 8, h - y - 24,
                        clamp(panelX - 12, 0, w - 1), y);
            }
            case BEST_ANSWERS -> {
                int pad = Math.max(44, w / 16);
                int panelY = Math.max(220, h / 8);
                int panelHeight = (int) Math.round(h * 0.62);
                int y = panelY + panelHeight + 24;
                paintBackgroundPatch(g, image, pad - 4, y, w - (pad * 2) + 8, h - y - 24,
                        clamp(pad - 12, 0, w - 1), y);
            }
            case ESCALATING_CONVERSATION -> {
                int pad = Math.max(42, w / 17);
                int imageY = Math.max(170, h / 10);
                int imageHeight = (int) Math.round(h * 0.50);
                int y = imageY + imageHeight + 48;
                paintBackgroundPatch(g, image, pad - 4, y, w - (pad * 2) + 8, h - y - 24,
                        clamp(pad - 12, 0, w - 1), y);
            }
            case THREAD_STORY -> {
                // handled above
            }
        }
        g.dispose();
    }

    private static void paintBackgroundPatch(
            Graphics2D g,
            BufferedImage image,
            int x,
            int y,
            int width,
            int height,
            int sampleX,
            int sampleY
    ) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int sx = clamp(sampleX, 0, image.getWidth() - 1);
        int syTop = clamp(sampleY, 0, image.getHeight() - 1);
        int syBottom = clamp(y + height - 1, 0, image.getHeight() - 1);
        Color top = new Color(image.getRGB(sx, syTop));
        Color bottom = new Color(image.getRGB(sx, syBottom));
        g.setPaint(new GradientPaint(0, y, top, 0, y + height, bottom));
        g.fillRect(clamp(x, 0, image.getWidth() - 1), clamp(y, 0, image.getHeight() - 1),
                Math.min(width, image.getWidth() - Math.max(0, x)),
                Math.min(height, image.getHeight() - Math.max(0, y)));
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static void configure(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }
}
