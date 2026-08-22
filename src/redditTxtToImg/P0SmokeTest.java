package redditTxtToImg;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

public final class P0SmokeTest {
    private P0SmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path temp = Files.createTempDirectory("threadgens-p0-smoke");
        try {
            testNovelty(temp);
            testFormats(temp);
            testIntegrityAndVisuals(temp);
            testEntrypointArgumentIsolation(temp);
            testContentAwareFormatSelection(temp);
            testHiddenPromptDoesNotBecomeVisible();
            testStaleVideoCleanup(temp);
            System.out.println("P0 smoke tests passed.");
        } finally {
            deleteRecursively(temp);
        }
    }

    private static void testNovelty(Path temp) throws Exception {
        NoveltyGuard guard = new NoveltyGuard(temp.resolve("history.jsonl"), 48, 50);
        String original = "I found a locked toolbox in the attic. My neighbor recognized the initials and told me it belonged to the previous tenant.";
        NoveltyGuard.Result first = guard.assess(original);
        require(first.accepted(), "First script should be accepted with empty history.");
        guard.record(original, "attic mystery", ContentFormat.THREAD_STORY);

        NoveltyGuard.Result duplicate = guard.assess(original);
        require(!duplicate.accepted(), "Exact duplicate should be rejected.");

        NoveltyGuard.Result distinct = guard.assess(
                "What is the most useful habit you learned at a bad job? One person explains inventory notes, another talks about sleep, and a third gives a budgeting rule.");
        require(distinct.noveltyScore() > duplicate.noveltyScore(),
                "Distinct content should score above duplicate content.");
    }

    private static void testFormats(Path temp) throws Exception {
        NoveltyGuard guard = new NoveltyGuard(temp.resolve("format_history.jsonl"));
        ContentFormat first = ContentFormat.resolve("auto", guard);
        guard.record("unique one", "one", first);
        ContentFormat second = ContentFormat.resolve("auto", guard);
        require(first != second, "Auto format selection should rotate away from the only recently used format.");
        require(ContentFormat.resolve("debate", guard) == ContentFormat.DEBATE,
                "Explicit debate format should resolve.");
    }

    private static void testIntegrityAndVisuals(Path temp) throws Exception {
        int width = 540;
        int height = 960;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(24, 24, 25));
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(29, 155, 240));
        g.fillOval(180, 125, 18, 18);
        g.dispose();

        Path source = temp.resolve("source.png");
        ImageIO.write(image, "png", source.toFile());
        IntegritySanitizer.sanitize(source, "x");

        BufferedImage sanitized = ImageIO.read(source.toFile());
        Color badgePixel = new Color(sanitized.getRGB(188, 133));
        require(!(badgePixel.getBlue() > 200 && badgePixel.getGreen() > 120),
                "Synthetic verification blue should be removed.");

        for (ContentFormat format : ContentFormat.values()) {
            Path output = temp.resolve(format.id() + ".png");
            DynamicVisualRenderer.render(
                    source,
                    "A short narration used to verify the format-specific composition.",
                    format,
                    1,
                    5,
                    output
            );
            require(Files.exists(output) && Files.size(output) > 0,
                    "Dynamic visual renderer should create " + format.id());
        }
    }

    private static void testEntrypointArgumentIsolation(Path temp) {
        Path generated = temp.resolve("generated.txt");
        String[] transformed = P0Entrypoint.prepareGeneratedScriptArgs(
                new String[]{
                        "data/comments.txt", "output", "--auto",
                        "--topic", "VISIBLE ORIGINAL",
                        "--llm-model", "llama3.1:8b",
                        "--format", "auto"
                },
                generated,
                ContentFormat.CONFESSION
        );
        String joined = String.join("|", transformed);
        require(!joined.contains("--auto"),
                "Generated-script render must not invoke the legacy auto generator.");
        require(joined.contains(generated.toString()),
                "Generated script must replace the input comments file.");
        require(joined.contains("VISIBLE ORIGINAL"),
                "Visible topic must stay unchanged for rendering.");
        require(joined.contains("--format|confession"),
                "Resolved format must be explicit during rendering.");

        String[] manual = P0Entrypoint.protectManualScriptInput(
                new String[]{
                        "data/comments.txt", "output", "--script-out",
                        "output/script/generated_comments.txt"
                });
        require(!String.join("|", manual).contains("output/script/generated_comments.txt"),
                "Manual runs must not read a stale generated script path.");
    }

    private static void testContentAwareFormatSelection(Path temp) {
        NoveltyGuard guard = new NoveltyGuard(temp.resolve("selector_history.jsonl"));
        require(FormatSelector.resolve("auto", guard, "Am I wrong here?", "We disagree about the lease")
                        == ContentFormat.DEBATE,
                "Dispute prompts should prefer debate format.");
        require(FormatSelector.resolve("auto", guard, "Wrong answers only", "Why is there a cart here?")
                        == ContentFormat.BEST_ANSWERS,
                "Wrong-answer prompts should prefer independent answer format.");
        ContentFormat story = FormatSelector.resolve(
                "auto", guard, "Finish this story in the replies", "The basement door opened by itself");
        require(story == ContentFormat.THREAD_STORY || story == ContentFormat.ESCALATING_CONVERSATION,
                "Story-continuation prompts should select a story-compatible format.");
    }

    private static void testHiddenPromptDoesNotBecomeVisible() throws Exception {
        FormatAwareTextGenerator generator = new FormatAwareTextGenerator(
                "http://127.0.0.1:9/api/generate", "not-used");
        String visible = "This exact sentence must remain the visible original post.";
        var lines = generator.generateLines(
                "reddit",
                "A visible title",
                visible,
                1,
                ContentFormat.DEBATE,
                "hidden retry instruction");
        require(lines.size() == 1 && visible.equals(lines.get(0)),
                "Hidden format/novelty guidance must never replace the visible OP.");
        require(!lines.get(0).contains("hidden retry instruction"),
                "Hidden novelty feedback must not leak into visible output.");
    }

    private static void testStaleVideoCleanup(Path temp) throws Exception {
        P0Runner.RunConfig config = new P0Runner.RunConfig();
        config.createVideo = true;
        config.outputPrefix = "cleanup";
        config.videoDirectory = temp.resolve("video-cleanup");
        config.finalVideoName = "final.mp4";
        Files.createDirectories(config.videoDirectory);
        Files.writeString(config.videoPath(0), "stale");
        Files.writeString(config.videoPath(1), "stale");
        Files.writeString(config.videoDirectory.resolve(config.finalVideoName), "stale");

        P0Runner.clearVideoOutputs(config, 2);
        require(!Files.exists(config.videoPath(0)) && !Files.exists(config.videoPath(1)),
                "Old segment videos must be removed before a new run.");
        require(!Files.exists(config.videoDirectory.resolve(config.finalVideoName)),
                "Old final video must be removed before a new run.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(item -> {
                        try {
                            Files.deleteIfExists(item);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }
}
