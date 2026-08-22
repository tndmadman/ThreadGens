package redditTxtToImg;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

/** Focused regression for narration-timed reveal of the rasterized Reddit text. */
public final class InImageRevealRegressionTest {
    private InImageRevealRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path temp = Files.createTempDirectory("threadgens-in-image-reveal-");
        try {
            Path source = temp.resolve("reddit-source.png");
            createRedditLikeSource(source);

            List<CaptionTimeline.Scene> scenes = List.of(
                    new CaptionTimeline.Scene("first line", 0.0, 0.5),
                    new CaptionTimeline.Scene("second line", 0.5, 1.0),
                    new CaptionTimeline.Scene("third line", 1.0, 1.5),
                    new CaptionTimeline.Scene("fourth line", 1.5, 2.0)
            );

            List<TimedVisualStateRenderer.RenderedState> states = TimedVisualStateRenderer.renderStates(
                    source,
                    "first line second line third line fourth line",
                    ContentFormat.THREAD_STORY,
                    1,
                    3,
                    scenes,
                    2.0,
                    temp.resolve("thread-states"),
                    "thread");
            require(states.size() >= 4, "reveal should produce multiple narration-timed states");

            BufferedImage first = ImageIO.read(states.get(0).imagePath().toFile());
            BufferedImage last = ImageIO.read(states.get(states.size() - 1).imagePath().toFile());
            int firstBright = countBright(first, 140, 440, 990, 820);
            int lastBright = countBright(last, 140, 440, 990, 820);
            require(lastBright > firstBright * 1.8,
                    "later states must expose substantially more of the same rasterized Reddit text");

            TimedVisualStateRenderer.cleanup(states);

            List<TimedVisualStateRenderer.RenderedState> bestAnswerStates = TimedVisualStateRenderer.renderStates(
                    source,
                    "first line second line third line fourth line",
                    ContentFormat.BEST_ANSWERS,
                    1,
                    3,
                    scenes,
                    2.0,
                    temp.resolve("best-states"),
                    "best");
            BufferedImage cleanBottom = ImageIO.read(
                    bestAnswerStates.get(bestAnswerStates.size() - 1).imagePath().toFile());
            int bottomBright = countBright(cleanBottom, 40, 1460, 1040, 1880);
            require(bottomBright < 3500,
                    "best_answers lower duplicate narration area should be removed");
            TimedVisualStateRenderer.cleanup(bestAnswerStates);

            System.out.println("In-image narration reveal regression passed.");
        } finally {
            deleteTree(temp);
        }
    }

    private static void createRedditLikeSource(Path path) throws Exception {
        BufferedImage image = new BufferedImage(1080, 1920, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(15, 16, 18));
        g.fillRect(0, 0, 1080, 1920);
        g.setColor(new Color(31, 31, 33));
        g.fillRoundRect(136, 260, 880, 1330, 34, 34);

        // Brand-colored upper-right badge so the production Reddit detector is exercised.
        g.setColor(new Color(230, 70, 10));
        g.fillRoundRect(806, 308, 168, 72, 24, 24);

        g.setColor(new Color(225, 225, 228));
        g.setFont(new Font("Arial", Font.PLAIN, 54));
        g.drawString("first line has enough visible pixels", 178, 500);
        g.drawString("second line has enough visible pixels", 178, 564);
        g.drawString("third line has enough visible pixels", 178, 628);
        g.drawString("fourth line has enough visible pixels", 178, 692);
        g.dispose();
        ImageIO.write(image, "png", path.toFile());
    }

    private static int countBright(BufferedImage image, int x0, int y0, int x1, int y1) {
        int count = 0;
        for (int y = Math.max(0, y0); y < Math.min(image.getHeight(), y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(image.getWidth(), x1); x++) {
                Color c = new Color(image.getRGB(x, y));
                if ((c.getRed() + c.getGreen() + c.getBlue()) / 3 >= 150) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
