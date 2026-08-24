package redditTxtToImg;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;

public final class P1SmokeTest {
    private P1SmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path temp = Files.createTempDirectory("threadgens-p1-smoke");
        try {
            testCaptionTiming(temp);
            testSceneVariation(temp);
            testVoicePlanning();
            testIdentityCooldown(temp);
            testIdentityConcurrency(temp);
            testProvenance(temp);
            testGeneratedOriginFlag(temp);
            System.out.println("P1 smoke tests passed.");
        } finally {
            deleteRecursively(temp);
        }
    }

    private static void testCaptionTiming(Path temp) throws Exception {
        CaptionTimeline timeline = CaptionTimeline.create(
                "First sentence has several words. The second sentence changes the visual scene.",
                7.25,
                "word",
                4);
        require(timeline.cues().size() >= 3, "Word captions should create multiple compact cues.");
        double previousEnd = 0.0;
        for (CaptionTimeline.Cue cue : timeline.cues()) {
            require(cue.startSeconds() >= previousEnd - 0.001, "Caption cues must be monotonic.");
            require(cue.endSeconds() > cue.startSeconds(), "Caption cues must have positive duration.");
            previousEnd = cue.endSeconds();
        }
        require(Math.abs(previousEnd - 7.25) < 0.02, "Caption timing should cover the measured WAV duration.");
        require(timeline.scenes(6).size() >= 2, "Sentence boundaries should produce visual scene changes.");

        Path ass = timeline.writeAss(temp.resolve("captions.ass"), 1080, 1920);
        String assText = Files.readString(ass, StandardCharsets.UTF_8);
        require(assText.contains("{\\kf"), "Word caption mode should emit ASS karaoke timing.");

        CaptionTimeline sentence = CaptionTimeline.create("One. Two.", 2.0, "sentence", 6);
        require(sentence.cues().size() == 2, "Sentence mode should preserve sentence boundaries.");
        require(CaptionTimeline.create("Text", 1.0, "off", 6).mode() == CaptionTimeline.Mode.OFF,
                "Captions should support an explicit off mode.");

        CaptionTimeline dense = CaptionTimeline.create(
                "one two three four five six seven eight nine ten eleven twelve",
                0.25,
                "word",
                1);
        require(dense.cues().stream().allMatch(cue -> cue.endSeconds() > cue.startSeconds()),
                "Very short audio must still assign every word a positive time range.");
    }

    private static void testSceneVariation(Path temp) throws Exception {
        BufferedImage source = new BufferedImage(540, 960, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = source.createGraphics();
        g.setColor(new Color(30, 34, 40));
        g.fillRect(0, 0, source.getWidth(), source.getHeight());
        g.dispose();
        Path sourcePath = temp.resolve("scene-source.png");
        ImageIO.write(source, "png", sourcePath.toFile());

        String narration = "The first visual beat starts here. The second visual beat changes the focus.";
        CaptionTimeline timeline = CaptionTimeline.create(narration, 5.0, "word", 4);
        List<TimedVisualStateRenderer.RenderedState> states = TimedVisualStateRenderer.renderStates(
                sourcePath,
                narration,
                ContentFormat.THREAD_STORY,
                0,
                3,
                timeline.scenes(4),
                5.0,
                temp,
                "caption-aligned");
        require(states.size() >= 2, "Narration timing should still create multiple timed states.");
        require(java.util.Arrays.equals(
                        Files.readAllBytes(states.get(0).imagePath()),
                        Files.readAllBytes(states.get(1).imagePath())),
                "Non-Reddit frames must not gain a duplicate narration overlay just to force scene variation.");
        TimedVisualStateRenderer.cleanup(states);
    }

    private static void testVoicePlanning() throws Exception {
        VoicePlan.Delivery calm = VoicePlan.Delivery.resolve("calm", null, "a", null);
        require(Math.abs(calm.speed() - 0.92) < 0.001 && calm.sentencePauseMs() == 280,
                "Calm delivery should resolve its speed and pause preset.");

        VoicePlan series = new VoicePlan(
                "kokoro", "python", Path.of("af_heart"), "af_bella,am_adam",
                Path.of("voices"), "series", "show-42", calm, 120);
        require(series.voiceFor(0).equals(series.voiceFor(7)),
                "Series voice selection must remain stable within one series.");

        HashSet<Path> batchVoices = new HashSet<>();
        for (int slot = 1; slot <= 4; slot++) {
            VoicePlan batchSeries = new VoicePlan(
                    "kokoro", "python", Path.of("af_heart"),
                    "af_heart,af_bella,af_nicole,bf_emma", Path.of("voices"), "series",
                    String.format("threadgens-reddit-slot-%04d", slot), calm, 120);
            batchVoices.add(batchSeries.voiceFor(0));
        }
        require(batchVoices.size() == 4,
                "Automatic batch series IDs should cycle through all four high-end voices.");

        VoicePlan rotating = new VoicePlan(
                "kokoro", "python", Path.of("af_heart"), "af_bella,am_adam",
                Path.of("voices"), "per-slide", "show-42", calm, 120);
        require(!rotating.voiceFor(0).equals(rotating.voiceFor(1)),
                "Per-slide voice selection should rotate through the configured pool.");

        P0Runner.RunConfig explicit = P0Runner.RunConfig.fromArgs(new String[]{
                "data/comments.txt", "output", "--tts-delivery", "calm",
                "--tts-speed", "1.0", "--tts-sentence-pause-ms", "180"});
        require(Math.abs(explicit.ttsSpeed - 1.0) < 0.001 && explicit.ttsSentencePauseMs == 180,
                "Explicit natural speed and pause values must override a delivery preset.");
    }

    private static void testIdentityCooldown(Path temp) throws Exception {
        Path historyPath = temp.resolve("identity-history.jsonl");
        IdentityHistory history = new IdentityHistory(historyPath, 20, true);
        List<String> names = List.of("alpha", "bravo", "charlie", "delta");
        List<String> images = List.of("a.png", "b.png", "c.png", "d.png");
        List<IdentityHistory.Identity> first = history.selectAndRecord(
                names, images, List.of("a.png", "b.png"), 2, "run-one");
        List<IdentityHistory.Identity> second = history.selectAndRecord(
                names, images, List.of("c.png", "d.png"), 2, "run-two");

        require(first.get(0).profileImage().equals("a.png") || first.get(0).profileImage().equals("b.png"),
                "Every selected slide should prefer the configured AI profile pool.");
        require(first.stream().allMatch(identity -> List.of("a.png", "b.png").contains(identity.profileImage())),
                "AI profiles should be used for every slide while that pool is available.");
        require(disjoint(first.stream().map(IdentityHistory.Identity::name).toList(),
                        second.stream().map(IdentityHistory.Identity::name).toList()),
                "Recent names should not repeat while unused names remain.");
        require(disjoint(first.stream().map(IdentityHistory.Identity::profileImage).toList(),
                        second.stream().map(IdentityHistory.Identity::profileImage).toList()),
                "Recent profile images should not repeat while unused images remain.");
        require(Files.readString(historyPath).lines().count() == 4,
                "Identity history should persist one record per selected identity.");

        List<IdentityHistory.Identity> exhausted = new IdentityHistory(
                temp.resolve("small-history.jsonl"), 20, false).selectAndRecord(
                List.of("one", "two"), List.of("one.png", "two.png"), List.of(), 5, "small");
        long firstNameCount = exhausted.stream().filter(identity -> identity.name().equals("one")).count();
        require(firstNameCount >= 2 && firstNameCount <= 3,
                "Exhausted identity pools should rotate evenly instead of collapsing to one candidate.");
    }

    private static void testIdentityConcurrency(Path temp) throws Exception {
        Path historyPath = temp.resolve("concurrent-identity-history.jsonl");
        IdentityHistory history = new IdentityHistory(historyPath, 20, true);
        List<String> names = List.of("a", "b", "c", "d", "e", "f");
        List<String> images = List.of("1.png", "2.png", "3.png", "4.png", "5.png", "6.png");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> history.selectAndRecord(names, images, List.of(), 2, "parallel-one"));
            var second = executor.submit(() -> history.selectAndRecord(names, images, List.of(), 2, "parallel-two"));
            List<IdentityHistory.Identity> firstResult = first.get();
            List<IdentityHistory.Identity> secondResult = second.get();
            require(disjoint(
                            firstResult.stream().map(IdentityHistory.Identity::name).toList(),
                            secondResult.stream().map(IdentityHistory.Identity::name).toList()),
                    "Concurrent selections should observe committed cooldown history.");
            require(Files.readString(historyPath).lines().count() == 4,
                    "Concurrent identity updates must preserve every history record.");
        } finally {
            executor.shutdownNow();
        }
    }

    private static void testProvenance(Path temp) throws Exception {
        P0Runner.RunConfig config = new P0Runner.RunConfig();
        config.outputPrefix = "p1test";
        config.commentsFile = temp.resolve("script.txt");
        config.outputDirectory = temp.resolve("images");
        config.audioDirectory = temp.resolve("audio");
        config.videoDirectory = temp.resolve("video");
        config.metadataDirectory = temp.resolve("metadata");
        config.finalVideoName = "final.mp4";
        config.ttsEngine = "kokoro";
        config.voiceSeries = "af_heart,af_bella";
        config.captionMode = "word";
        config.contentOrigin = "ai";
        Files.createDirectories(config.outputDirectory);
        Files.createDirectories(config.audioDirectory);
        Files.createDirectories(config.videoDirectory);
        Files.writeString(config.commentsFile, "Synthetic test script");
        Files.writeString(config.imagePath(0), "image");
        Files.writeString(config.audioPath(0), "audio");
        Files.writeString(config.videoPath(0), "video");
        Files.writeString(config.videoDirectory.resolve(config.finalVideoName), "final");
        Files.writeString(config.voiceMetadataPath(0),
                "{\"engine\":\"kokoro\",\"voice\":\"af_bella\"}");

        Path manifest = ProvenanceManifest.write(config, ContentFormat.DEBATE, 1, null);
        String json = Files.readString(manifest, StandardCharsets.UTF_8);
        require(json.contains(ProvenanceManifest.SCHEMA), "Provenance should identify its schema.");
        require(json.contains("af_bella"), "Provenance should record the selected segment voice.");
        require(json.contains("sha256"), "Provenance should hash generated artifacts.");
        require(json.contains(ProvenanceManifest.GENERATOR_VERSION),
                "Provenance should use the release version.");
        require(!json.contains(temp.toAbsolutePath().toString()),
                "Distributed provenance should not expose an absolute local workspace path.");
        require(Files.exists(config.videoDirectory.resolve("final.mp4.provenance.json")),
                "Final videos should receive an adjacent provenance sidecar.");
    }

    private static void testGeneratedOriginFlag(Path temp) {
        String[] transformed = P0Entrypoint.prepareGeneratedScriptArgs(
                new String[]{"input.txt", "output", "--auto", "--content-origin", "manual"},
                temp.resolve("generated.txt"),
                ContentFormat.CONFESSION);
        require(String.join("|", transformed).contains("--content-origin|ai"),
                "Auto generation should be marked as AI-originated for provenance.");
        require(!String.join("|", transformed).contains("--content-origin|manual"),
                "Auto generation must not retain a caller-supplied manual origin.");
    }

    private static boolean disjoint(List<String> left, List<String> right) {
        HashSet<String> values = new HashSet<>(left);
        return right.stream().noneMatch(values::contains);
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
