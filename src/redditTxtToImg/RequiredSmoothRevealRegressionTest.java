package redditTxtToImg;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

/** Ensures production never silently downgrades a recognized social frame. */
public final class RequiredSmoothRevealRegressionTest {
    private RequiredSmoothRevealRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("threadgens.requireSmoothReveal", "true");
        Path temp = Files.createTempDirectory("threadgens-required-reveal-");
        try {
            Path source = temp.resolve("reddit.png");
            createRedditLikeSourceWithoutNarrationGlyphs(source);

            boolean failedClosed = false;
            try {
                TimedVisualStateRenderer.renderStates(
                        source,
                        "these narration words deliberately have no matching visible glyphs",
                        ContentFormat.THREAD_STORY,
                        1,
                        2,
                        2.0,
                        temp.resolve("states"),
                        "required");
            } catch (java.io.IOException expected) {
                failedClosed = expected.getMessage().contains("Smooth narration reveal is required");
            }
            require(failedClosed,
                    "recognized social frames with no provable narration mapping must fail instead of using legacy states");

            System.out.println("Required smooth reveal fail-closed regression passed.");
        } finally {
            System.clearProperty("threadgens.requireSmoothReveal");
            deleteTree(temp);
        }
    }

    private static void createRedditLikeSourceWithoutNarrationGlyphs(Path path) throws Exception {
        BufferedImage image = new BufferedImage(1080, 1920, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(15, 16, 18));
        g.fillRect(0, 0, 1080, 1920);
        g.setColor(new Color(31, 31, 33));
        g.fillRoundRect(136, 260, 880, 1330, 34, 34);

        // Large Reddit-orange badge in the same region as the real renderer.
        // This proves the frame is recognized as Reddit while intentionally
        // providing zero narration glyphs for the word mapper to consume.
        g.setColor(new Color(230, 70, 10));
        g.fillRoundRect(806, 308, 168, 72, 24, 24);
        g.dispose();
        ImageIO.write(image, "png", path.toFile());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
