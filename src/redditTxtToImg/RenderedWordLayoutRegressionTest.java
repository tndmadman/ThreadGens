package redditTxtToImg;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Production regressions for deterministic renderer-owned narration geometry. */
public final class RenderedWordLayoutRegressionTest {
    private static final String FAILURE_TITLE =
            "Why does my grandma's antique music box always play the same song when it starts raining?";
    private static final String FAILURE_BODY =
            "It's been happening for months now - every time it rains outside, the old music box in Grandma's living room will start playing a familiar tune from our childhood. "
                    + "But whenever we try to rewind or fast forward through the song, it seems stuck on this one track. "
                    + "We've tried cleaning the mechanism and adjusting the volume, but nothing seems to work. "
                    + "Can anyone explain why this music box is so stubbornly connected to the weather?";
    private static final String FITTING_TITLE = "Why does grandma's music box react to rain?";

    private RenderedWordLayoutRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path temp = Files.createTempDirectory("threadgens-rendered-word-layout-");
        try {
            verifyOriginalFailureIsRejectedBeforeTruncation(temp);
            verifyProductionRedditGeometryWithFailureBody(temp);
            verifyPunctuationTokenGeometry(temp);
            verifyOversizeRedditFailsBeforeTruncation(temp);
            verifyXNarratesOnlyVisibleText(temp);
            System.out.println("Rendered word layout regressions passed.");
        } finally {
            System.clearProperty("threadgens.requireSmoothReveal");
            deleteTree(temp);
        }
    }

    private static void verifyOriginalFailureIsRejectedBeforeTruncation(Path temp) throws Exception {
        String narration = RenderedWordLayout.narrationForReddit(FAILURE_TITLE, FAILURE_BODY);
        require(RenderedWordLayout.countWords(narration) == 92,
                "Regression fixture must remain the original 92-word narration case.");

        Path comments = temp.resolve("original-failure-comments.txt");
        Path output = temp.resolve("original-failure-images");
        Files.writeString(comments, FAILURE_BODY + System.lineSeparator(), StandardCharsets.UTF_8);

        boolean failed = false;
        try {
            RedditScreenshotGenerator.main(new String[]{
                    comments.toString(), output.toString(),
                    "--count", "1",
                    "--prefix", "originalfailure",
                    "--post-title", FAILURE_TITLE,
                    "--top",
                    "--no-identity-history"
            });
        } catch (IllegalStateException expected) {
            failed = expected.getMessage().contains("Reddit rendering failed")
                    && expected.getCause() != null;
        }
        require(failed,
                "The original 92-word case must be rejected before rendering because its full title cannot fit without truncation.");
        Path image = output.resolve("0originalfailure.png");
        require(!Files.exists(image),
                "The original non-fitting case must not leave a truncated image that could be narrated as if complete.");
        require(!Files.exists(RenderedWordLayout.sidecarFor(image)),
                "The original non-fitting case must not leave a stale word-layout sidecar.");
    }

    private static void verifyProductionRedditGeometryWithFailureBody(Path temp) throws Exception {
        Path comments = temp.resolve("fitting-case-comments.txt");
        Path output = temp.resolve("fitting-case-images");
        Files.writeString(comments, FAILURE_BODY + System.lineSeparator(), StandardCharsets.UTF_8);

        RedditScreenshotGenerator.main(new String[]{
                comments.toString(), output.toString(),
                "--count", "1",
                "--prefix", "fitting",
                "--post-title", FITTING_TITLE,
                "--top",
                "--no-identity-history"
        });

        Path image = output.resolve("0fitting.png");
        require(Files.isRegularFile(image), "Production Reddit geometry fixture was not generated.");
        String narration = RenderedWordLayout.narrationForReddit(FITTING_TITLE, FAILURE_BODY);
        RenderedWordLayout.Layout sourceLayout = RenderedWordLayout.read(image);
        require(sourceLayout.words().size() == RenderedWordLayout.countWords(narration),
                "Renderer-owned geometry must produce one exact box per visible/spoken Reddit word.");
        require(RenderedWordLayout.sameNarration(sourceLayout.narration(), narration),
                "Renderer geometry narration must exactly track the spoken Reddit narration.");

        System.setProperty("threadgens.requireSmoothReveal", "true");
        List<TimedVisualStateRenderer.RenderedState> states = TimedVisualStateRenderer.renderStates(
                image,
                narration,
                ContentFormat.BEST_ANSWERS,
                0,
                1,
                8.0,
                temp.resolve("fitting-case-states"),
                "fitting");
        try {
            require(states.size() == 1,
                    "Strict Reddit production frame should use one smooth-reveal state.");
            require(TimedVisualStateRenderer.hasSmoothRevealAssets(states.get(0).imagePath()),
                    "Strict Reddit production frame must create smooth reveal assets from renderer geometry.");
            TimedVisualStateRenderer.RevealLayout reveal = TimedVisualStateRenderer.readLayout(states.get(0).imagePath());
            require(reveal.words().size() == RenderedWordLayout.countWords(narration),
                    "Final smooth reveal layout must retain every renderer-owned word box.");
        } finally {
            TimedVisualStateRenderer.cleanup(states);
            System.clearProperty("threadgens.requireSmoothReveal");
        }
    }

    private static void verifyPunctuationTokenGeometry(Path temp) throws Exception {
        String text = "It's grandma's 2:13 A.M. alarm - \"really\" C++ #42 costs $5.00.";
        BufferedImage image = new BufferedImage(1600, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        List<RenderedWordLayout.Box> boxes = new ArrayList<>();
        try {
            Font font = new Font("Arial", Font.PLAIN, 48);
            g.setFont(font);
            FontMetrics metrics = g.getFontMetrics(font);
            RenderedWordLayout.addLineBoxes(boxes, text, metrics, 40, 120, image.getWidth(), image.getHeight());
        } finally {
            g.dispose();
        }
        require(boxes.size() == RenderedWordLayout.countWords(text),
                "Contractions, punctuation, symbols, and numbers must stay one box per whitespace word.");

        Path fakeImage = temp.resolve("punctuation.png");
        javax.imageio.ImageIO.write(image, "png", fakeImage.toFile());
        RenderedWordLayout.write(fakeImage, text, image.getWidth(), image.getHeight(), boxes);
        RenderedWordLayout.Layout loaded = RenderedWordLayout.read(fakeImage);
        require(loaded.words().size() == RenderedWordLayout.words(text).size(),
                "Serialized punctuation geometry changed word count.");
    }

    private static void verifyOversizeRedditFailsBeforeTruncation(Path temp) throws Exception {
        Path comments = temp.resolve("oversize-comments.txt");
        Path output = temp.resolve("oversize-images");
        String body = String.join(" ", java.util.Collections.nCopies(450, "oversized"));
        Files.writeString(comments, body + System.lineSeparator(), StandardCharsets.UTF_8);

        boolean failed = false;
        try {
            RedditScreenshotGenerator.main(new String[]{
                    comments.toString(), output.toString(),
                    "--count", "1",
                    "--prefix", "oversize",
                    "--post-title", "Short visible title",
                    "--top",
                    "--no-identity-history"
            });
        } catch (IllegalStateException expected) {
            failed = expected.getMessage().contains("Reddit rendering failed")
                    && expected.getCause() != null;
        }
        require(failed, "Oversize Reddit narration must be rejected instead of drawing an ellipsis.");
        Path image = output.resolve("0oversize.png");
        require(!Files.exists(image), "Rejected oversize Reddit content must not leave a misleading fresh image.");
        require(!Files.exists(RenderedWordLayout.sidecarFor(image)),
                "Rejected oversize Reddit content must not leave a geometry sidecar.");
    }

    private static void verifyXNarratesOnlyVisibleText(Path temp) throws Exception {
        String hiddenReplyStyle = "wrong answers only";
        String visible = "It's 2:13 - grandma's clock, not the hidden prompt.";
        Path comments = temp.resolve("x-comments.txt");
        Path output = temp.resolve("x-images");
        Files.writeString(comments, visible + System.lineSeparator(), StandardCharsets.UTF_8);

        XThreadGenerator.main(new String[]{
                comments.toString(), output.toString(),
                "--count", "1",
                "--prefix", "xcase",
                "--post-title", hiddenReplyStyle,
                "--no-identity-history"
        });

        Path image = output.resolve("0xcase.png");
        RenderedWordLayout.Layout sourceLayout = RenderedWordLayout.read(image);
        require(RenderedWordLayout.sameNarration(sourceLayout.narration(), visible),
                "X source geometry must contain only the visible post text.");
        require(!sourceLayout.narration().contains(hiddenReplyStyle),
                "Hidden X reply-style instructions must never enter visible/spoken narration geometry.");
        require(sourceLayout.words().size() == RenderedWordLayout.countWords(visible),
                "X renderer geometry must map every visible word exactly once.");

        System.setProperty("threadgens.requireSmoothReveal", "true");
        List<TimedVisualStateRenderer.RenderedState> states = TimedVisualStateRenderer.renderStates(
                image,
                visible,
                ContentFormat.ESCALATING_CONVERSATION,
                0,
                1,
                3.0,
                temp.resolve("x-states"),
                "xcase");
        try {
            require(states.size() == 1 && TimedVisualStateRenderer.hasSmoothRevealAssets(states.get(0).imagePath()),
                    "Strict X production frame must consume renderer-owned geometry.");
        } finally {
            TimedVisualStateRenderer.cleanup(states);
            System.clearProperty("threadgens.requireSmoothReveal");
        }
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
