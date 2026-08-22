package redditTxtToImg;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
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
 * Converts one social frame into multiple genuinely different visual states for
 * the duration of a spoken segment. The base format composition stays stable,
 * while the active narration phrase, focus treatment and progress change at
 * sentence/clause boundaries.
 */
final class TimedVisualStateRenderer {
    record RenderedState(Path imagePath, double weight, int index, int total) {
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
        Files.createDirectories(outputDirectory);
        Path baseFrame = outputDirectory.resolve(baseName + "_base.png");
        DynamicVisualRenderer.render(sourceImage, narration, format, itemIndex, itemTotal, baseFrame);
        BufferedImage base = ImageIO.read(baseFrame.toFile());
        if (base == null) {
            throw new IOException("Could not decode dynamic base frame: " + baseFrame);
        }

        List<VideoTimeline.State> timeline = VideoTimeline.fromNarration(narration, audioDurationSeconds);
        List<RenderedState> rendered = new ArrayList<>();
        for (VideoTimeline.State state : timeline) {
            BufferedImage image = copy(base);
            Graphics2D g = image.createGraphics();
            configure(g);
            drawStateOverlay(g, image.getWidth(), image.getHeight(), state, format, itemIndex);
            g.dispose();

            Path statePath = outputDirectory.resolve(baseName + "_state_" + state.index() + ".png");
            ImageIO.write(image, "png", statePath.toFile());
            rendered.add(new RenderedState(
                    statePath,
                    state.weight(),
                    state.index(),
                    state.total()
            ));
        }
        Files.deleteIfExists(baseFrame);
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

    private static void drawStateOverlay(
            Graphics2D g,
            int width,
            int height,
            VideoTimeline.State state,
            ContentFormat format,
            int itemIndex
    ) {
        Color accent = accent(format, itemIndex);
        int margin = Math.max(34, width / 22);
        int cardWidth = width - (margin * 2);
        int cardHeight = Math.max(210, height / 7);
        int y = focusY(format, height, cardHeight, margin);

        // Slightly dim the frame outside the active narration state. This makes
        // each state visibly different even with audio muted.
        g.setColor(new Color(0, 0, 0, 54));
        g.fillRect(0, 0, width, height);

        g.setColor(new Color(10, 11, 14, 226));
        g.fillRoundRect(margin, y, cardWidth, cardHeight, 42, 42);
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 235));
        g.setStroke(new BasicStroke(Math.max(4f, width / 230f)));
        g.drawRoundRect(margin, y, cardWidth, cardHeight, 42, 42);

        int eyebrowSize = Math.max(20, width / 42);
        g.setFont(new Font("Arial", Font.BOLD, eyebrowSize));
        g.setColor(accent);
        String eyebrow = stateLabel(format) + "  " + (state.index() + 1) + "/" + state.total();
        g.drawString(eyebrow, margin + 28, y + 46);

        int textSize = Math.max(30, width / 30);
        drawWrappedText(
                g,
                compact(state.focusText(), 260),
                margin + 28,
                y + 76,
                cardWidth - 56,
                textSize,
                4
        );

        int progressX = margin + 28;
        int progressY = y + cardHeight - 28;
        int progressWidth = cardWidth - 56;
        int progressHeight = Math.max(7, height / 260);
        g.setColor(new Color(255, 255, 255, 34));
        g.fillRoundRect(progressX, progressY, progressWidth, progressHeight, progressHeight, progressHeight);
        double fraction = (double) (state.index() + 1) / Math.max(1, state.total());
        g.setColor(accent);
        g.fillRoundRect(progressX, progressY, Math.max(progressHeight, (int) Math.round(progressWidth * fraction)),
                progressHeight, progressHeight, progressHeight);
    }

    private static int focusY(ContentFormat format, int height, int cardHeight, int margin) {
        return switch (format) {
            case THREAD_STORY -> Math.max(margin, height - cardHeight - Math.max(120, height / 12));
            case CONFESSION -> Math.max(margin, height - cardHeight - Math.max(78, height / 18));
            case DEBATE -> Math.max(margin, height - cardHeight - Math.max(100, height / 14));
            case BEST_ANSWERS -> Math.max(margin, height - cardHeight - Math.max(88, height / 16));
            case ESCALATING_CONVERSATION -> Math.max(margin, height - cardHeight - Math.max(64, height / 20));
        };
    }

    private static String stateLabel(ContentFormat format) {
        return switch (format) {
            case THREAD_STORY -> "STORY BEAT";
            case CONFESSION -> "REVEAL";
            case DEBATE -> "ACTIVE POINT";
            case BEST_ANSWERS -> "KEY ANSWER";
            case ESCALATING_CONVERSATION -> "CURRENT MESSAGE";
        };
    }

    private static Color accent(ContentFormat format, int itemIndex) {
        return switch (format) {
            case THREAD_STORY -> new Color(116, 137, 166);
            case CONFESSION -> new Color(184, 116, 161);
            case DEBATE -> itemIndex % 2 == 0 ? new Color(95, 157, 220) : new Color(218, 113, 108);
            case BEST_ANSWERS -> new Color(89, 183, 164);
            case ESCALATING_CONVERSATION -> new Color(108, 152, 216);
        };
    }

    private static void drawWrappedText(
            Graphics2D g,
            String text,
            int x,
            int y,
            int maxWidth,
            int fontSize,
            int maxLines
    ) {
        Font font = new Font("Arial", Font.BOLD, fontSize);
        g.setFont(font);
        g.setColor(new Color(246, 246, 248));
        FontMetrics metrics = g.getFontMetrics(font);
        List<String> lines = wrap(text, metrics, maxWidth);
        int lineHeight = fontSize + Math.max(8, fontSize / 4);
        int baseline = y + metrics.getAscent();
        for (int i = 0; i < lines.size() && i < maxLines; i++) {
            String line = lines.get(i);
            if (i == maxLines - 1 && lines.size() > maxLines && !line.endsWith("…")) {
                line = line + "…";
            }
            g.drawString(line, x, baseline);
            baseline += lineHeight;
        }
    }

    private static List<String> wrap(String text, FontMetrics metrics, int maxWidth) {
        if (text == null || text.isBlank()) {
            return List.of("");
        }
        List<String> result = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && metrics.stringWidth(candidate) > maxWidth) {
                result.add(line.toString());
                line = new StringBuilder(word);
            } else {
                if (!line.isEmpty()) {
                    line.append(' ');
                }
                line.append(word);
            }
        }
        if (!line.isEmpty()) {
            result.add(line.toString());
        }
        return result;
    }

    private static String compact(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String clean = value.replaceAll("\\s+", " ").trim();
        if (clean.length() <= maxChars) {
            return clean;
        }
        return clean.substring(0, Math.max(1, maxChars - 1)).trim() + "…";
    }

    private static void configure(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }
}
