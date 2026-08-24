package redditTxtToImg;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

import javax.imageio.ImageIO;

public final class P0SmokeTest {
    private P0SmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path temp = Files.createTempDirectory("threadgens-p0-smoke");
        try {
            testNovelty(temp);
            testLargeHistoryParsing(temp);
            testStrictHistoryValidation(temp);
            testFormats(temp);
            testVideoTimelineCadence();
            testIntegrityAndVisuals(temp);
            testEntrypointArgumentIsolation(temp);
            testContentAwareFormatSelection(temp);
            testFormatCooldownAndVariantRotation(temp);
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

    private static void testLargeHistoryParsing(Path temp) throws Exception {
        Path historyPath = temp.resolve("large-history.jsonl");
        NoveltyGuard guard = new NoveltyGuard(historyPath, 48, 50);
        String largeScript = "historytoken ".repeat(5000).trim();
        guard.record(largeScript, "large parser regression", ContentFormat.THREAD_STORY);

        List<String> formats = guard.recentFormats(5);
        require(formats.size() == 1 && "thread_story".equals(formats.get(0)),
                "Large Base64 history rows must parse without regex stack overflow.");
    }

    private static void testStrictHistoryValidation(Path temp) throws Exception {
        Path goodHistory = temp.resolve("strict-good.jsonl");
        NoveltyGuard guard = new NoveltyGuard(goodHistory, 48, 50);
        guard.record("A valid historical script with enough words to compare.",
                "valid history", ContentFormat.CONFESSION);
        List<String> scripts = SemanticNoveltyGuard.loadRecentScripts(goodHistory, 10);
        require(scripts.size() == 1 && scripts.get(0).contains("valid historical script"),
                "Strict semantic history loader must read NoveltyGuard records.");

        Path badHistory = temp.resolve("strict-bad.jsonl");
        Files.writeString(badHistory, "{\"created\":\"now\",\"broken\":true}\n");
        boolean failedClosed = false;
        try {
            SemanticNoveltyGuard.loadRecentScripts(badHistory, 10);
        } catch (IOException expected) {
            failedClosed = true;
        }
        require(failedClosed,
                "Malformed existing novelty history must fail closed instead of behaving like empty history.");
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

    private static void testVideoTimelineCadence() {
        String narration = String.join(" ",
                "one two three four five six seven eight nine ten",
                "eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty",
                "twentyone twentytwo twentythree twentyfour twentyfive twentysix twentyseven twentyeight twentynine thirty",
                "thirtyone thirtytwo thirtythree thirtyfour thirtyfive thirtysix thirtyseven thirtyeight thirtynine forty",
                "fortyone fortytwo fortythree fortyfour fortyfive fortysix fortyseven fortyeight fortynine fifty",
                "fiftyone fiftytwo fiftythree fiftyfour fiftyfive fiftysix fiftyseven fiftyeight fiftynine sixty",
                "sixtyone sixtytwo sixtythree sixtyfour sixtyfive sixtysix sixtyseven sixtyeight sixtynine seventy",
                "seventyone seventytwo seventythree seventyfour seventyfive seventysix seventyseven seventyeight seventynine eighty");
        double duration = 20.0;
        List<VideoTimeline.State> states = VideoTimeline.fromNarration(narration, duration);
        require(states.size() >= 8,
                "Twenty seconds of narration should create enough visual states to avoid long holds.");
        double weightSum = 0.0;
        double longest = 0.0;
        for (VideoTimeline.State state : states) {
            require(!state.focusText().isBlank(), "Every timed state should have focus text.");
            weightSum += state.weight();
            longest = Math.max(longest, state.weight() * duration);
        }
        require(Math.abs(weightSum - 1.0) < 0.0001, "Timeline state weights must sum to one.");
        require(longest <= 3.0,
                "Normal timed visual states should not hold essentially unchanged for more than about three seconds.");
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
                "Legacy synthetic verification blue should be removed by the safety net.");

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
                        "--format", "auto",
                        "--embedding-model", "nomic-embed-text"
                },
                generated,
                ContentFormat.CONFESSION,
                ContentVariant.PRIVATE_NOTE
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
        require(joined.contains("--format-variant|private_note"),
                "Resolved format substyle must be explicit during rendering.");
        require(joined.contains("--embedding-model|nomic-embed-text"),
                "Semantic novelty options must preserve option/value pairing until P0 consumes them.");

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

    private static void testFormatCooldownAndVariantRotation(Path temp) throws Exception {
        NoveltyGuard formatGuard = new NoveltyGuard(temp.resolve("format_cooldown_history.jsonl"));
        Set<ContentFormat> selectedFormats = new HashSet<>();
        for (int i = 0; i < ContentFormat.values().length; i++) {
            ContentFormat selected = FormatSelector.resolve(
                    "auto", formatGuard, "Why does this happen?", "A broad question with many valid structures");
            selectedFormats.add(selected);
            formatGuard.record("Unique format rotation script " + i, "rotation", selected);
        }
        require(selectedFormats.size() == ContentFormat.values().length,
                "Question prompts must keep all five formats reachable before repeating one.");

        NoveltyGuard variantGuard = new NoveltyGuard(temp.resolve("variant_rotation_history.jsonl"));
        Set<ContentVariant> variants = new HashSet<>();
        int expected = ContentVariant.forFormat(ContentFormat.THREAD_STORY).size();
        for (int i = 0; i < expected; i++) {
            ContentVariant variant = ContentVariant.resolve("auto", ContentFormat.THREAD_STORY, variantGuard);
            variants.add(variant);
            variantGuard.record("Unique substyle rotation script " + i, "rotation",
                    ContentFormat.THREAD_STORY, variant);
        }
        require(variants.size() == expected,
                "Automatic substyles must rotate through every variant before reuse.");
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
                ContentVariant.EXPERT_PANEL,
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
