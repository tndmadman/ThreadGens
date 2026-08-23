package redditTxtToImg;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/** Focused regression for smooth narration-synced in-image text reveal. */
public final class InImageRevealRegressionTest {
    private InImageRevealRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path temp = Files.createTempDirectory("threadgens-in-image-reveal-");
        try {
            verifyRedditReveal(temp);
            verifyXReveal(temp);
            System.out.println("Smooth Reddit/X in-image narration reveal regression passed.");
        } finally {
            deleteTree(temp);
        }
    }

    private static void verifyRedditReveal(Path temp) throws Exception {
        String narration = "first line has enough visible pixels second line stays hidden initially";
        Path source = temp.resolve("reddit-source.png");
        createRedditLikeSource(source);

        List<CaptionTimeline.Scene> scenes = List.of(
                new CaptionTimeline.Scene("first line has enough visible pixels", 0.0, 1.0),
                new CaptionTimeline.Scene("second line stays hidden initially", 1.0, 2.0)
        );

        List<TimedVisualStateRenderer.RenderedState> states = TimedVisualStateRenderer.renderStates(
                source,
                narration,
                ContentFormat.THREAD_STORY,
                1,
                3,
                scenes,
                2.0,
                temp.resolve("thread-states"),
                "thread");
        require(states.size() == 1,
                "recognized Reddit frames should use one full frame plus a clean base, not block-step states");

        Path fullPath = states.get(0).imagePath();
        require(TimedVisualStateRenderer.hasSmoothRevealAssets(fullPath),
                "Reddit smooth reveal must create base and word-layout sidecars");
        TimedVisualStateRenderer.RevealLayout layout = TimedVisualStateRenderer.readLayout(fullPath);
        require(layout.words().size() == narration.split("\\s+").length,
                "Reddit word-box detection should exactly match narration word count");

        BufferedImage full = ImageIO.read(fullPath.toFile());
        BufferedImage base = ImageIO.read(TimedVisualStateRenderer.basePath(fullPath).toFile());
        int fullBright = countBright(full, 130, 430, 1010, 720);
        int baseBright = countBright(base, 130, 430, 1010, 720);
        System.out.println("Reddit reveal preparation bright pixels: full=" + fullBright + ", base=" + baseBright);
        require(fullBright > 1500, "Reddit fixture must contain substantial visible text");
        require(baseBright < fullBright / 12,
                "Reddit clean base must physically remove essentially all narration glyph pixels");
        requireWordBoxesAreClean(base, layout.words(), "Reddit");
        verifyContinuousMask(layout, narration);

        TimedVisualStateRenderer.cleanup(states);
        require(!Files.exists(fullPath), "cleanup should remove Reddit smooth full frame");
        require(!Files.exists(TimedVisualStateRenderer.basePath(fullPath)),
                "cleanup should remove Reddit smooth base frame");
        require(!Files.exists(TimedVisualStateRenderer.layoutPath(fullPath)),
                "cleanup should remove Reddit smooth reveal layout");
    }

    private static void verifyXReveal(Path temp) throws Exception {
        String narration = "first reply glides smoothly second line follows narration exactly";
        Path source = temp.resolve("x-source.png");
        createXLikeSource(source);

        List<CaptionTimeline.Scene> scenes = List.of(
                new CaptionTimeline.Scene("first reply glides smoothly", 0.0, 1.0),
                new CaptionTimeline.Scene("second line follows narration exactly", 1.0, 2.0)
        );
        List<TimedVisualStateRenderer.RenderedState> states = TimedVisualStateRenderer.renderStates(
                source,
                narration,
                ContentFormat.ESCALATING_CONVERSATION,
                1,
                3,
                scenes,
                2.0,
                temp.resolve("x-states"),
                "x");
        require(states.size() == 1,
                "recognized X frames should use one full frame plus a clean base, not block-step states");

        Path fullPath = states.get(0).imagePath();
        require(TimedVisualStateRenderer.hasSmoothRevealAssets(fullPath),
                "X smooth reveal must create base and word-layout sidecars");
        TimedVisualStateRenderer.RevealLayout layout = TimedVisualStateRenderer.readLayout(fullPath);
        require(layout.words().size() == narration.split("\\s+").length,
                "X word-box detection should exactly match narration word count");

        BufferedImage full = ImageIO.read(fullPath.toFile());
        BufferedImage base = ImageIO.read(TimedVisualStateRenderer.basePath(fullPath).toFile());
        int fullBright = countBright(full, 180, 380, 1020, 650);
        int baseBright = countBright(base, 180, 380, 1020, 650);
        System.out.println("X reveal preparation bright pixels: full=" + fullBright + ", base=" + baseBright);
        require(fullBright > 1200, "X fixture must contain substantial visible text");
        require(baseBright < fullBright / 12,
                "X clean base must physically remove essentially all narration glyph pixels");
        requireWordBoxesAreClean(base, layout.words(), "X");
        verifyContinuousMask(layout, narration);
        TimedVisualStateRenderer.cleanup(states);
    }

    private static void verifyContinuousMask(
            TimedVisualStateRenderer.RevealLayout layout,
            String narration
    ) {
        List<NarrationTiming.Word> timing = new ArrayList<>();
        String[] words = narration.split("\\s+");
        for (int i = 0; i < words.length; i++) {
            timing.add(new NarrationTiming.Word(words[i], i * 0.16, (i + 1) * 0.16));
        }
        String mask = DynamicVideoGenerator.buildSmoothRevealMask(layout, timing, 1080, 1920, 30);
        require(mask.contains("N/30.0"), "smooth reveal mask must be evaluated every video frame");
        require(mask.contains("clip(("), "active word should interpolate continuously across its duration");
        require(mask.contains(String.valueOf(layout.words().get(0).left() - 1)),
                "zero-progress reveal edge must begin before first glyph column to prevent leakage");
    }

    private static void requireWordBoxesAreClean(
            BufferedImage base,
            List<TimedVisualStateRenderer.WordBox> boxes,
            String label
    ) {
        for (TimedVisualStateRenderer.WordBox box : boxes) {
            int leaked = countBright(base, box.left(), box.top(), box.right() + 1, box.bottom() + 1);
            require(leaked <= 3,
                    label + " hidden word rectangle leaked bright glyph pixels before reveal: " + box + " -> " + leaked);
        }
    }

    private static void createRedditLikeSource(Path path) throws Exception {
        BufferedImage image = new BufferedImage(1080, 1920, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(15, 16, 18));
        g.fillRect(0, 0, 1080, 1920);
        g.setColor(new Color(31, 31, 33));
        g.fillRoundRect(136, 260, 880, 1330, 34, 34);

        g.setColor(new Color(230, 70, 10));
        g.fillRoundRect(806, 308, 168, 72, 24, 24);

        g.setColor(new Color(225, 225, 228));
        g.setFont(new Font("Arial", Font.PLAIN, 54));
        g.drawString("first line has enough visible pixels", 178, 500);
        g.drawString("second line stays hidden initially", 178, 574);
        g.dispose();
        ImageIO.write(image, "png", path.toFile());
    }

    private static void createXLikeSource(Path path) throws Exception {
        BufferedImage image = new BufferedImage(1080, 1920, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, 1080, 1920);
        g.setColor(new Color(47, 51, 54));
        g.drawRoundRect(64, 76, 952, 1768, 46, 46);
        g.drawLine(64, 208, 1016, 208);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 48));
        g.drawString("X", 524, 162);

        g.setFont(new Font("Arial", Font.PLAIN, 44));
        g.drawString("first reply glides smoothly", 220, 455);
        g.drawString("second line follows narration exactly", 220, 520);
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
