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
            CheckedRunner.runOrThrow(stripP0Options(safeArgs));
            return;
        }

        RunConfig config = RunConfig.fromArgs(safeArgs);
        if (config.createVideo && !config.ttsEnabled()) {
            throw new IOException(
                    "Video was requested, but TTS is disabled. Use --tts kokoro or --tts piper before --video.");
        }

        NoveltyGuard noveltyGuard = new NoveltyGuard(
                config.historyFile, config.noveltyThreshold, config.historyLimit);
        ContentFormat format = ContentFormat.resolve(config.requestedFormat, noveltyGuard);
        System.out.println("P0 format: " + format.id() + " (" + format.label() + ")");
        System.out.println("P0 novelty history: " + noveltyGuard.historyFile());

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
        CheckedRunner.runOrThrow(delegatedArgs);

        int artifactCount = config.expectedCount();
        if (config.integritySanitize) {
            System.out.println("P0 integrity: removing synthetic engagement and verification markers...");
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
            noveltyGuard.record(currentScript, config.topic, format);
            System.out.println("P0 novelty: accepted script recorded in history.");
        }

        if (noveltyResult != null && !noveltyResult.accepted()) {
            System.out.println("P0 completed with a manual-content novelty warning.");
        } else {
            System.out.println("P0 pipeline complete.");
        }
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

        System.out.println("P0 video: building dynamic " + format.id() + " compositions...");
        for (int i = 0; i < artifactCount; i++) {
            Path image = config.imagePath(i);
            Path audio = config.audioPath(i);
            Path clip = config.videoPath(i);
            String narration = config.readExactNarration(i, narrationFallback);

            Path frame = frameDirectory.resolve(config.baseName(i) + "_" + format.id() + ".png");
            DynamicVisualRenderer.render(image, narration, format, i, artifactCount, frame);
            Files.deleteIfExists(clip);
            dynamicVideo.renderClip(
                    frame, audio, clip, config.width, config.height, format, i);
            clips.add(clip);
            System.out.println("Generated dynamic clip: " + clip);
        }

        if (config.concatVideo && !clips.isEmpty()) {
            Path finalVideo = config.videoDirectory.resolve(config.finalVideoName);
            Files.deleteIfExists(finalVideo);
            dynamicVideo.combineClips(clips, finalVideo);
            System.out.println("Generated dynamic final video: " + finalVideo);
        }

        for (int i = 0; i < artifactCount; i++) {
            Files.deleteIfExists(frameDirectory.resolve(config.baseName(i) + "_" + format.id() + ".png"));
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
            if ("--no-novelty".equals(arg) || "--no-integrity-sanitize".equals(arg)) {
                continue;
            }
            result.add(arg);
        }
        return result.toArray(new String[0]);
    }

    private static boolean isP0ValueOption(String arg) {
        return "--format".equals(arg)
                || "--history-file".equals(arg)
                || "--history-limit".equals(arg)
                || "--novelty-threshold".equals(arg)
                || "--novelty-retries".equals(arg);
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

        String outputPrefix = "aithread";
        String finalVideoName = "final.mp4";
        String ttsEngine = "none";
        String videoCommand = "ffmpeg";
        String postTitle = "Finish this story in the comments";
        String topic = "weird everyday stories";
        String requestedFormat = "auto";

        int count = -1;
        int width = 1080;
        int height = 1920;
        int videoFps = 30;
        int videoTimeoutSeconds = 180;
        int noveltyThreshold = NoveltyGuard.DEFAULT_THRESHOLD;
        int noveltyRetries = 4;
        int historyLimit = NoveltyGuard.DEFAULT_HISTORY_LIMIT;

        boolean autoGenerateText = false;
        boolean createVideo = false;
        boolean concatVideo = false;
        boolean noveltyEnabled = true;
        boolean integritySanitize = true;

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
                        case "--format" -> {
                            if (i + 1 < args.length) config.requestedFormat = args[++i];
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
                        case "--no-novelty" -> config.noveltyEnabled = false;
                        case "--no-integrity-sanitize" -> config.integritySanitize = false;
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
            config.ttsEngine = properties.getProperty("ttsEngine", config.ttsEngine).toLowerCase(Locale.ROOT);
            config.audioDirectory = Path.of(
                    properties.getProperty("audioDirectory", config.audioDirectory.toString()));
            config.videoDirectory = Path.of(
                    properties.getProperty("videoDirectory", config.videoDirectory.toString()));
            config.videoCommand = properties.getProperty("videoCommand", config.videoCommand);
            config.finalVideoName = properties.getProperty("finalVideoName", config.finalVideoName);
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
    }
}
