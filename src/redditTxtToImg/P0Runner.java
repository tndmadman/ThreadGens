package redditTxtToImg;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * P0 render/orchestration engine.
 *
 * P0Entrypoint owns auto-generation. If --auto reaches this class directly, it
 * is routed back through that safe entry point so hidden prompt instructions
 * can never be transported through visible post fields.
 */
public final class P0Runner {
    private P0Runner() {
    }

    public static void main(String[] args) {
        try {
            runOrThrow(args == null ? new String[0] : args);
        } catch (Exception e) {
            System.err.println("P0 run failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static void runOrThrow(String[] args) throws IOException, InterruptedException {
        String[] safeArgs = args == null ? new String[0] : args.clone();

        if (contains(safeArgs, "--auto")) {
            P0Entrypoint.runOrThrow(safeArgs);
            return;
        }
        if (contains(safeArgs, "--list-voices") || contains(safeArgs, "--gui")) {
            CheckedRunner.runRawOrThrow(stripP0Options(safeArgs));
            return;
        }

        RunConfig config = RunConfig.fromArgs(safeArgs);

        // Clear every artifact owned by this output prefix before validating the
        // new input. A failed regeneration must never leave an older PNG/WAV/MP4
        // behind that can be mistaken for fresh output.
        clearRequestedOutputs(config);

        if (config.createVideo && !config.ttsEnabled()) {
            throw new IOException(
                    "Video was requested, but TTS is disabled. Use --tts kokoro or --tts piper before --video.");
        }

        NoveltyGuard noveltyGuard = new NoveltyGuard(
                config.historyFile, config.noveltyThreshold, config.historyLimit);
        ContentFormat format = ContentFormat.resolve(config.requestedFormat, noveltyGuard);
        ContentVariant variant = ContentVariant.resolve(config.requestedFormatVariant, format, noveltyGuard);
        System.out.println("P0 format: " + format.id() + " (" + format.label() + ")");
        System.out.println("P0 format variant: " + variant.id() + " (" + variant.pacingFamily() + " pacing)");
        System.out.println("P0 render style: " + config.renderStyle
                + " / pacing profile " + config.pacingProfile);
        System.out.println("P0 novelty history: " + noveltyGuard.historyFile());

        int artifactCount = config.expectedCount();

        String currentScript = config.readCurrentScript();
        NoveltyGuard.Result noveltyResult = null;
        if (config.noveltyEnabled && !currentScript.isBlank()) {
            noveltyResult = noveltyGuard.assess(currentScript);
            printNoveltyResult(noveltyResult);
            if (!noveltyResult.accepted()) {
                System.err.println(
                        "P0 novelty warning: supplied/manual content resembles recent output. "
                                + "It will be rendered because explicit input is authoritative, but it will not be re-recorded as a new history item.");
            }
        }

        String[] delegatedArgs = stripP0Options(safeArgs);
        if (config.createVideo) {
            delegatedArgs = stripVideoModeFlags(delegatedArgs);
        }
        CheckedRunner.runRawOrThrow(delegatedArgs);

        if (config.integritySanitize) {
            System.out.println("P0 integrity: validating rendered social frames...");
            for (int i = 0; i < artifactCount; i++) {
                IntegritySanitizer.sanitize(config.imagePath(i), config.platform);
            }
        }

        if (config.createVideo) {
            renderDynamicVideos(config, format, artifactCount);
        }

        if (config.noveltyEnabled
                && !currentScript.isBlank()
                && (noveltyResult == null || noveltyResult.accepted())) {
            noveltyGuard.record(currentScript, config.topic, format, variant);
            System.out.println("P0 novelty: accepted script recorded in history.");
        }

        if (config.provenanceEnabled) {
            Path manifest = ProvenanceManifest.write(config, format, variant, artifactCount, noveltyResult);
            System.out.println("P1 provenance manifest: " + manifest);
        }

        if (noveltyResult != null && !noveltyResult.accepted()) {
            System.out.println("P0 completed with a manual-content novelty warning.");
        } else {
            System.out.println("P0 pipeline complete.");
        }
    }

    static void clearRequestedOutputs(RunConfig config) throws IOException {
        if (config == null) {
            return;
        }
        deleteNumberedArtifacts(config.outputDirectory, config.outputPrefix, ".png");
        deleteNumberedArtifacts(config.audioDirectory, config.outputPrefix, ".wav");
        deleteNumberedArtifacts(config.audioDirectory, config.outputPrefix, ".txt");
        deleteNumberedArtifacts(config.audioDirectory, config.outputPrefix, ".voice.json");
        deleteNumberedArtifacts(config.videoDirectory, config.outputPrefix, ".mp4");
        clearProvenanceOutputs(config);
        if (config.createVideo) {
            Files.deleteIfExists(config.videoDirectory.resolve(config.finalVideoName));
        }
    }

    private static void deleteNumberedArtifacts(Path directory, String prefix, String suffix) throws IOException {
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        String safePrefix = prefix == null ? "" : prefix;
        Pattern pattern = Pattern.compile("\\d+" + Pattern.quote(safePrefix) + Pattern.quote(suffix));
        try (var entries = Files.list(directory)) {
            List<Path> matches = entries
                    .filter(Files::isRegularFile)
                    .filter(path -> pattern.matcher(path.getFileName().toString()).matches())
                    .toList();
            for (Path path : matches) {
                Files.deleteIfExists(path);
            }
        }
    }

    static void clearVideoOutputs(RunConfig config, int countHint) throws IOException {
        if (config == null || !config.createVideo) {
            return;
        }
        int count = Math.max(0, countHint);
        for (int i = 0; i < count; i++) {
            Files.deleteIfExists(config.videoPath(i));
        }
        Files.deleteIfExists(config.videoDirectory.resolve(config.finalVideoName));
    }

    static void clearProvenanceOutputs(RunConfig config) throws IOException {
        if (config == null) {
            return;
        }
        if (config.metadataDirectory != null) {
            Files.deleteIfExists(config.metadataDirectory.resolve(config.outputPrefix + "-provenance.json"));
        }
        Path finalVideo = config.videoDirectory.resolve(config.finalVideoName);
        Files.deleteIfExists(finalVideo.resolveSibling(finalVideo.getFileName() + ".provenance.json"));
    }

    private static void renderDynamicVideos(RunConfig config, ContentFormat format, int artifactCount)
            throws IOException, InterruptedException {
        Files.createDirectories(config.videoDirectory);
        Path frameDirectory = config.videoDirectory.resolve(".threadgens_frames");
        Files.createDirectories(frameDirectory);

        DynamicVideoGenerator dynamicVideo =
                new DynamicVideoGenerator(config.videoCommand, config.videoTimeoutSeconds, config.videoFps);
        List<Path> clips = new ArrayList<>();
        List<String> narrationFallback = config.readNarrationLines();
        config.renderedSceneCounts.clear();

        System.out.println("P0/P1 video: building caption-aligned multi-state " + format.id() + " compositions...");
        for (int i = 0; i < artifactCount; i++) {
            Path image = config.imagePath(i);
            Path audio = config.audioPath(i);
            Path clip = config.videoPath(i);
            String narration = config.readExactNarration(i, narrationFallback);
            double audioDuration = dynamicVideo.probeDurationSeconds(audio);
            CaptionTimeline timeline = CaptionTimeline.create(
                    narration, audioDuration, config.captionMode, config.captionWordsPerCue);
            List<CaptionTimeline.Scene> scenes = timeline.scenes(config.visualMaxScenes);
            config.renderedSceneCounts.add(scenes.size());

            List<TimedVisualStateRenderer.RenderedState> states = TimedVisualStateRenderer.renderStates(
                    image,
                    narration,
                    format,
                    i,
                    artifactCount,
                    scenes,
                    audioDuration,
                    frameDirectory,
                    config.baseName(i) + "_" + format.id()
            );
            Path captionFile = null;
            if (timeline.mode() != CaptionTimeline.Mode.OFF) {
                captionFile = frameDirectory.resolve(config.baseName(i) + ".ass");
                timeline.writeAss(captionFile, config.width, config.height);
            }
            java.util.Map<String, String> segmentMetadata = config.provenanceEnabled
                    ? ProvenanceManifest.videoMetadata(config, format, "segment " + (i + 1))
                    : java.util.Map.of();
            Path standaloneFinalizedClip = null;
            try {
                Files.deleteIfExists(clip);
                dynamicVideo.renderClip(
                        states,
                        audio,
                        captionFile,
                        clip,
                        config.width,
                        config.height,
                        format,
                        i,
                        segmentMetadata
                );
                if (!config.concatVideo) {
                    standaloneFinalizedClip = clip.resolveSibling(
                            clip.getFileName() + ".threadgens-final.mp4");
                    Files.deleteIfExists(standaloneFinalizedClip);
                    dynamicVideo.combineClips(
                            List.of(clip),
                            standaloneFinalizedClip,
                            format,
                            segmentMetadata);
                    Files.move(
                            standaloneFinalizedClip,
                            clip,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                if (standaloneFinalizedClip != null) {
                    Files.deleteIfExists(standaloneFinalizedClip);
                }
                TimedVisualStateRenderer.cleanup(states);
                if (captionFile != null) {
                    Files.deleteIfExists(captionFile);
                }
            }
            clips.add(clip);
            System.out.println("Generated timed-state clip: " + clip
                    + " [states=" + scenes.size()
                    + ", captions=" + timeline.mode().name().toLowerCase(Locale.ROOT)
                    + ", finalTexture=" + (!config.concatVideo) + "]");
        }

        if (config.concatVideo && !clips.isEmpty()) {
            Path finalVideo = config.videoDirectory.resolve(config.finalVideoName);
            Files.deleteIfExists(finalVideo);
            dynamicVideo.combineClips(
                    clips,
                    finalVideo,
                    format,
                    config.provenanceEnabled
                            ? ProvenanceManifest.videoMetadata(config, format, "final video")
                            : java.util.Map.of());
            System.out.println("Generated format-specific final video: " + finalVideo);
        }

        try {
            Files.deleteIfExists(frameDirectory);
        } catch (IOException ignored) {
            // Leave diagnostic intermediates only if another process has them open.
        }
    }

    private static void printNoveltyResult(NoveltyGuard.Result result) {
        System.out.println("P0 novelty score: " + result.noveltyScore() + "/100");
        for (String reason : result.reasons()) {
            System.out.println("  - " + reason);
        }
    }

    private static String[] stripP0Options(String[] args) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (isP0ValueOption(arg)) {
                if (i + 1 < args.length) {
                    i++;
                }
                continue;
            }
            if ("--no-novelty".equals(arg)
                    || "--no-integrity-sanitize".equals(arg)
                    || "--no-semantic-novelty".equals(arg)
                    || "--no-provenance-metadata".equals(arg)) {
                continue;
            }
            result.add(arg);
        }
        return result.toArray(new String[0]);
    }

    private static boolean isP0ValueOption(String arg) {
        return "--format".equals(arg)
                || "--format-variant".equals(arg)
                || "--history-file".equals(arg)
                || "--history-limit".equals(arg)
                || "--novelty-threshold".equals(arg)
                || "--novelty-retries".equals(arg)
                || "--embedding-model".equals(arg)
                || "--semantic-threshold".equals(arg)
                || "--semantic-history-limit".equals(arg)
                || "--captions".equals(arg)
                || "--caption-words".equals(arg)
                || "--visual-max-scenes".equals(arg)
                || "--render-style".equals(arg)
                || "--pacing-profile".equals(arg)
                || "--metadata-dir".equals(arg)
                || "--disclosure".equals(arg)
                || "--content-origin".equals(arg);
    }

    private static String[] stripVideoModeFlags(String[] args) {
        List<String> result = new ArrayList<>();
        for (String arg : args) {
            if (!"--video".equals(arg) && !"--concat-video".equals(arg)) {
                result.add(arg);
            }
        }
        return result.toArray(new String[0]);
    }

    private static boolean contains(String[] args, String value) {
        for (String arg : args) {
            if (value.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    static final class RunConfig {
        String platform = "reddit";
        Path commentsFile = Path.of("data", "comments.txt");
        Path outputDirectory = Path.of("output");
        Path audioDirectory = Path.of("output", "audio");
        Path videoDirectory = Path.of("output", "video");
        Path scriptOut = Path.of("output", "script", "generated_comments.txt");
        Path historyFile = Path.of("data", "generation_history.jsonl");
        Path identityHistoryFile = Path.of("data", "identity_history.jsonl");
        Path metadataDirectory;

        String outputPrefix = "aithread";
        String finalVideoName = "final.mp4";
        String ttsEngine = "none";
        String voiceSeries = "";
        String voiceSelection = "single";
        String seriesId = "";
        String ttsDelivery = "natural";
        String ttsLanguage = "a";
        String videoCommand = "ffmpeg";
        String postTitle = "Finish this story in the comments";
        String topic = "weird everyday stories";
        String requestedFormat = "auto";
        String requestedFormatVariant = "auto";
        String renderStyle = "auto";
        String pacingProfile = "balanced";
        String llmModel = "llama3.1:8b";
        String imageMode = "none";
        String imageCheckpoint = "";
        String captionMode = "word";
        String contentOrigin = "manual";
        String disclosureText = "AI-assisted fictional content with synthetic narration and identities; engagement is hidden.";

        int count = -1;
        int width = 1080;
        int height = 1920;
        int videoFps = 30;
        int videoTimeoutSeconds = 180;
        int noveltyThreshold = NoveltyGuard.DEFAULT_THRESHOLD;
        int noveltyRetries = 4;
        int historyLimit = NoveltyGuard.DEFAULT_HISTORY_LIMIT;
        int identityHistoryLimit = 500;
        int ttsSentencePauseMs = 180;
        int captionWordsPerCue = 6;
        int visualMaxScenes = 20;

        double ttsSpeed = 1.0;

        boolean autoGenerateText = false;
        boolean createVideo = false;
        boolean concatVideo = false;
        boolean noveltyEnabled = true;
        boolean integritySanitize = true;
        boolean identityHistoryEnabled = true;
        boolean provenanceEnabled = true;
        boolean ttsSpeedConfigured = false;
        boolean ttsSentencePauseConfigured = false;
        boolean disclosureConfigured = false;
        final List<Integer> renderedSceneCounts = new ArrayList<>();

        static RunConfig fromArgs(String[] args) throws IOException {
            RunConfig config = loadDefaults();
            int positional = 0;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg == null || arg.isBlank()) {
                    continue;
                }
                if (arg.startsWith("--")) {
                    switch (arg) {
                        case "--platform" -> {
                            if (i + 1 < args.length) config.platform = normalizePlatform(args[++i]);
                        }
                        case "--auto" -> config.autoGenerateText = true;
                        case "--video" -> config.createVideo = true;
                        case "--concat-video" -> {
                            config.createVideo = true;
                            config.concatVideo = true;
                        }
                        case "--count" -> {
                            if (i + 1 < args.length) config.count = parseInt(args[++i], config.count);
                        }
                        case "--prefix" -> {
                            if (i + 1 < args.length) config.outputPrefix = args[++i];
                        }
                        case "--post-title" -> {
                            if (i + 1 < args.length) config.postTitle = args[++i];
                        }
                        case "--topic" -> {
                            if (i + 1 < args.length) config.topic = args[++i];
                        }
                        case "--tts" -> {
                            if (i + 1 < args.length) config.ttsEngine = args[++i].toLowerCase(Locale.ROOT);
                        }
                        case "--voice-series" -> {
                            if (i + 1 < args.length) config.voiceSeries = args[++i];
                        }
                        case "--voice-selection" -> {
                            if (i + 1 < args.length) config.voiceSelection = args[++i];
                        }
                        case "--series-id" -> {
                            if (i + 1 < args.length) config.seriesId = args[++i];
                        }
                        case "--tts-delivery" -> {
                            if (i + 1 < args.length) config.ttsDelivery = args[++i];
                        }
                        case "--tts-speed" -> {
                            if (i + 1 < args.length) {
                                config.ttsSpeed = parseDouble(args[++i], config.ttsSpeed);
                                config.ttsSpeedConfigured = true;
                            }
                        }
                        case "--tts-language" -> {
                            if (i + 1 < args.length) config.ttsLanguage = args[++i];
                        }
                        case "--tts-sentence-pause-ms" -> {
                            if (i + 1 < args.length) {
                                config.ttsSentencePauseMs = parseInt(args[++i], config.ttsSentencePauseMs);
                                config.ttsSentencePauseConfigured = true;
                            }
                        }
                        case "--audio-dir" -> {
                            if (i + 1 < args.length) config.audioDirectory = Path.of(args[++i]);
                        }
                        case "--video-dir" -> {
                            if (i + 1 < args.length) config.videoDirectory = Path.of(args[++i]);
                        }
                        case "--video-command" -> {
                            if (i + 1 < args.length) config.videoCommand = args[++i];
                        }
                        case "--fps" -> {
                            if (i + 1 < args.length) config.videoFps = parseInt(args[++i], config.videoFps);
                        }
                        case "--video-timeout" -> {
                            if (i + 1 < args.length) {
                                config.videoTimeoutSeconds = parseInt(args[++i], config.videoTimeoutSeconds);
                            }
                        }
                        case "--final-video" -> {
                            if (i + 1 < args.length) config.finalVideoName = args[++i];
                        }
                        case "--script-out" -> {
                            if (i + 1 < args.length) config.scriptOut = Path.of(args[++i]);
                        }
                        case "--llm-model" -> {
                            if (i + 1 < args.length) config.llmModel = args[++i];
                        }
                        case "--image-mode" -> {
                            if (i + 1 < args.length) config.imageMode = args[++i];
                        }
                        case "--image-checkpoint" -> {
                            if (i + 1 < args.length) config.imageCheckpoint = args[++i];
                        }
                        case "--format" -> {
                            if (i + 1 < args.length) config.requestedFormat = args[++i];
                        }
                        case "--format-variant" -> {
                            if (i + 1 < args.length) config.requestedFormatVariant = args[++i];
                        }
                        case "--render-style" -> {
                            if (i + 1 < args.length) config.renderStyle = normalizeLooseId(args[++i], "auto");
                        }
                        case "--pacing-profile" -> {
                            if (i + 1 < args.length) config.pacingProfile = normalizeLooseId(args[++i], "balanced");
                        }
                        case "--history-file" -> {
                            if (i + 1 < args.length) config.historyFile = Path.of(args[++i]);
                        }
                        case "--history-limit" -> {
                            if (i + 1 < args.length) config.historyLimit = parseInt(args[++i], config.historyLimit);
                        }
                        case "--novelty-threshold" -> {
                            if (i + 1 < args.length) {
                                config.noveltyThreshold = parseInt(args[++i], config.noveltyThreshold);
                            }
                        }
                        case "--novelty-retries" -> {
                            if (i + 1 < args.length) config.noveltyRetries = parseInt(args[++i], config.noveltyRetries);
                        }
                        case "--identity-history-file" -> {
                            if (i + 1 < args.length) config.identityHistoryFile = Path.of(args[++i]);
                        }
                        case "--identity-history-limit" -> {
                            if (i + 1 < args.length) {
                                config.identityHistoryLimit = parseInt(args[++i], config.identityHistoryLimit);
                            }
                        }
                        case "--no-identity-history" -> config.identityHistoryEnabled = false;
                        case "--captions" -> {
                            if (i + 1 < args.length) config.captionMode = args[++i];
                        }
                        case "--caption-words" -> {
                            if (i + 1 < args.length) {
                                config.captionWordsPerCue = parseInt(args[++i], config.captionWordsPerCue);
                            }
                        }
                        case "--visual-max-scenes" -> {
                            if (i + 1 < args.length) {
                                config.visualMaxScenes = parseInt(args[++i], config.visualMaxScenes);
                            }
                        }
                        case "--metadata-dir" -> {
                            if (i + 1 < args.length) config.metadataDirectory = Path.of(args[++i]);
                        }
                        case "--disclosure" -> {
                            if (i + 1 < args.length) {
                                config.disclosureText = args[++i];
                                config.disclosureConfigured = true;
                            }
                        }
                        case "--content-origin" -> {
                            if (i + 1 < args.length) config.contentOrigin = args[++i];
                        }
                        case "--embedding-model", "--semantic-threshold", "--semantic-history-limit" -> {
                            if (i + 1 < args.length) i++;
                        }
                        case "--no-novelty" -> config.noveltyEnabled = false;
                        case "--no-integrity-sanitize" -> config.integritySanitize = false;
                        case "--no-provenance-metadata" -> config.provenanceEnabled = false;
                        case "--no-semantic-novelty" -> {
                            // Consumed by P0Entrypoint during automatic generation.
                        }
                        default -> {
                            if (CliOptions.isValueOption(arg) && i + 1 < args.length) {
                                i++;
                            }
                        }
                    }
                    continue;
                }

                if (positional == 0) {
                    config.commentsFile = Path.of(arg);
                } else if (positional == 1) {
                    config.outputDirectory = Path.of(arg);
                }
                positional++;
            }
            if (config.metadataDirectory == null) {
                config.metadataDirectory = config.outputDirectory.resolve("metadata");
            }
            config.captionMode = CaptionTimeline.Mode.resolve(config.captionMode).name().toLowerCase(Locale.ROOT);
            config.renderStyle = normalizeLooseId(config.renderStyle, "auto");
            config.pacingProfile = normalizeLooseId(config.pacingProfile, "balanced");
            config.captionWordsPerCue = Math.max(1, Math.min(12, config.captionWordsPerCue));
            config.visualMaxScenes = Math.max(1, Math.min(20, config.visualMaxScenes));
            config.identityHistoryLimit = Math.max(1, config.identityHistoryLimit);
            config.voiceSelection = VoicePlan.Selection.resolve(config.voiceSelection)
                    .name().toLowerCase(Locale.ROOT).replace('_', '-');
            VoicePlan.Delivery delivery = VoicePlan.Delivery.resolve(
                    config.ttsDelivery,
                    config.ttsSpeedConfigured ? config.ttsSpeed : null,
                    config.ttsLanguage,
                    config.ttsSentencePauseConfigured ? config.ttsSentencePauseMs : null);
            config.ttsDelivery = delivery.preset();
            config.ttsSpeed = delivery.speed();
            config.ttsLanguage = delivery.language();
            config.ttsSentencePauseMs = delivery.sentencePauseMs();
            config.contentOrigin = normalizeContentOrigin(config.contentOrigin);
            if (!config.disclosureConfigured) {
                config.disclosureText = "manual".equals(config.contentOrigin)
                        ? "Fictional content with synthetic identities; engagement is hidden."
                        : "AI-assisted fictional content with synthetic narration and identities; engagement is hidden.";
            }
            return config;
        }

        private static RunConfig loadDefaults() throws IOException {
            RunConfig config = new RunConfig();
            Path defaults = Path.of("defaults.txt");
            if (!Files.exists(defaults)) {
                return config;
            }
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(defaults)) {
                properties.load(input);
            }

            config.platform = normalizePlatform(properties.getProperty("platform", config.platform));
            config.width = parseInt(properties.getProperty("width"), config.width);
            config.height = parseInt(properties.getProperty("height"), config.height);
            config.outputPrefix = properties.getProperty("prefix", config.outputPrefix);
            config.postTitle = properties.getProperty("postTitle", config.postTitle);
            config.topic = properties.getProperty("topic", config.topic);
            config.llmModel = properties.getProperty("llmModel", config.llmModel);
            config.ttsEngine = properties.getProperty("ttsEngine", config.ttsEngine).toLowerCase(Locale.ROOT);
            config.voiceSeries = properties.getProperty("voiceSeries", config.voiceSeries);
            config.voiceSelection = properties.getProperty("voiceSelection", config.voiceSelection);
            config.seriesId = properties.getProperty("seriesId", config.seriesId);
            config.ttsDelivery = properties.getProperty("ttsDelivery", config.ttsDelivery);
            String configuredSpeed = properties.getProperty("ttsSpeed", "").trim();
            if (!configuredSpeed.isBlank()) {
                config.ttsSpeed = parseDouble(configuredSpeed, config.ttsSpeed);
                config.ttsSpeedConfigured = true;
            }
            config.ttsLanguage = properties.getProperty("ttsLanguage", config.ttsLanguage);
            String configuredPause = properties.getProperty("ttsSentencePauseMs", "").trim();
            if (!configuredPause.isBlank()) {
                config.ttsSentencePauseMs = parseInt(configuredPause, config.ttsSentencePauseMs);
                config.ttsSentencePauseConfigured = true;
            }
            config.audioDirectory = Path.of(
                    properties.getProperty("audioDirectory", config.audioDirectory.toString()));
            config.videoDirectory = Path.of(
                    properties.getProperty("videoDirectory", config.videoDirectory.toString()));
            config.videoCommand = properties.getProperty("videoCommand", config.videoCommand);
            config.finalVideoName = properties.getProperty("finalVideoName", config.finalVideoName);
            config.requestedFormat = properties.getProperty("format", config.requestedFormat);
            config.requestedFormatVariant = properties.getProperty(
                    "formatVariant", config.requestedFormatVariant);
            config.renderStyle = normalizeLooseId(
                    properties.getProperty("renderStyle", config.renderStyle), "auto");
            config.pacingProfile = normalizeLooseId(
                    properties.getProperty("pacingProfile", config.pacingProfile), "balanced");
            config.historyFile = Path.of(properties.getProperty("historyFile", config.historyFile.toString()));
            config.historyLimit = parseInt(properties.getProperty("historyLimit"), config.historyLimit);
            config.noveltyThreshold = parseInt(
                    properties.getProperty("noveltyThreshold"), config.noveltyThreshold);
            config.noveltyRetries = parseInt(properties.getProperty("noveltyRetries"), config.noveltyRetries);
            config.noveltyEnabled = Boolean.parseBoolean(
                    properties.getProperty("noveltyEnabled", String.valueOf(config.noveltyEnabled)));
            config.integritySanitize = Boolean.parseBoolean(
                    properties.getProperty("integritySanitize", String.valueOf(config.integritySanitize)));
            config.identityHistoryFile = Path.of(
                    properties.getProperty("identityHistoryFile", config.identityHistoryFile.toString()));
            config.identityHistoryLimit = parseInt(
                    properties.getProperty("identityHistoryLimit"), config.identityHistoryLimit);
            config.identityHistoryEnabled = Boolean.parseBoolean(properties.getProperty(
                    "identityHistoryEnabled", String.valueOf(config.identityHistoryEnabled)));
            config.captionMode = properties.getProperty("captionMode", config.captionMode);
            config.captionWordsPerCue = parseInt(
                    properties.getProperty("captionWordsPerCue"), config.captionWordsPerCue);
            config.visualMaxScenes = parseInt(
                    properties.getProperty("visualMaxScenes"), config.visualMaxScenes);
            config.provenanceEnabled = Boolean.parseBoolean(properties.getProperty(
                    "provenanceEnabled", String.valueOf(config.provenanceEnabled)));
            String configuredDisclosure = properties.getProperty("disclosureText", "").trim();
            if (!configuredDisclosure.isBlank()) {
                config.disclosureText = configuredDisclosure;
                config.disclosureConfigured = true;
            }
            String configuredMetadataDirectory = properties.getProperty("metadataDirectory", "").trim();
            if (!configuredMetadataDirectory.isBlank()) {
                config.metadataDirectory = Path.of(configuredMetadataDirectory);
            }
            config.imageMode = properties.getProperty("imageMode", config.imageMode);
            config.imageCheckpoint = properties.getProperty("imageCheckpoint", config.imageCheckpoint);
            return config;
        }

        int expectedCount() throws IOException {
            if (!Files.exists(commentsFile)) {
                throw new IOException("Input comments file was not found: " + commentsFile);
            }
            try (var lines = Files.lines(commentsFile)) {
                int available = (int) lines.map(String::trim).filter(line -> !line.isBlank()).count();
                return count >= 0 ? Math.min(available, count) : available;
            }
        }

        String readCurrentScript() throws IOException {
            if (!Files.exists(commentsFile)) {
                return "";
            }
            return Files.readString(commentsFile, StandardCharsets.UTF_8).trim();
        }

        List<String> readNarrationLines() throws IOException {
            if (!Files.exists(commentsFile)) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            for (String line : Files.readAllLines(commentsFile, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (!trimmed.isBlank()) {
                    result.add(trimmed);
                }
            }
            return result;
        }

        String readExactNarration(int index, List<String> fallbackLines) throws IOException {
            Path sidecar = audioTextPath(index);
            if (Files.exists(sidecar)) {
                String exact = Files.readString(sidecar, StandardCharsets.UTF_8).trim();
                if (!exact.isBlank()) {
                    return exact;
                }
            }

            String line = index >= 0 && index < fallbackLines.size() ? fallbackLines.get(index) : "";
            if (index == 0 && postTitle != null && !postTitle.isBlank()) {
                return line.isBlank() ? postTitle : postTitle + ". " + line;
            }
            return line;
        }

        String baseName(int index) {
            return index + outputPrefix;
        }

        Path imagePath(int index) {
            return outputDirectory.resolve(baseName(index) + ".png");
        }

        Path audioPath(int index) {
            return audioDirectory.resolve(baseName(index) + ".wav");
        }

        Path audioTextPath(int index) {
            return audioDirectory.resolve(baseName(index) + ".txt");
        }

        Path voiceMetadataPath(int index) {
            return audioDirectory.resolve(baseName(index) + ".voice.json");
        }

        Path videoPath(int index) {
            return videoDirectory.resolve(baseName(index) + ".mp4");
        }

        boolean ttsEnabled() {
            return ttsEngine != null && !ttsEngine.isBlank() && !"none".equalsIgnoreCase(ttsEngine);
        }

        private static String normalizePlatform(String value) {
            if (value == null || value.isBlank()) {
                return "reddit";
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            return "twitter".equals(normalized) ? "x" : normalized;
        }

        private static String normalizeContentOrigin(String value) {
            String normalized = value == null ? "manual" : value.trim().toLowerCase(Locale.ROOT);
            if (!"manual".equals(normalized) && !"ai".equals(normalized) && !"mixed".equals(normalized)) {
                throw new IllegalArgumentException(
                        "Unsupported content origin: " + value + ". Use manual, ai, or mixed.");
            }
            return normalized;
        }

        private static String normalizeLooseId(String value, String fallback) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                    .replace('-', '_')
                    .replace(' ', '_');
            return normalized.isBlank() ? fallback : normalized;
        }

        private static int parseInt(String value, int fallback) {
            if (value == null) {
                return fallback;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        private static double parseDouble(String value, double fallback) {
            if (value == null) {
                return fallback;
            }
            try {
                return Double.parseDouble(value.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }
}
