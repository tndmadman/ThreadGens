package redditTxtToImg;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * User-facing checked entry point.
 *
 * Normal calls route through P0Entrypoint so CLI, GUI and older scripts inherit
 * the production originality/integrity pipeline. The package-private raw method
 * remains available to P0Runner and explicit compatibility tests without
 * creating recursion.
 */
public class CheckedRunner {
    private static final Set<String> VOICE_DEPENDENCY_OPTIONS = Set.of("--tts", "--voice-dir");

    public static void main(String[] args) {
        try {
            runOrThrow(args == null ? new String[0] : args);
        } catch (Exception e) {
            System.err.println("Checked run failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static void runOrThrow(String[] args) throws IOException, InterruptedException {
        P0Entrypoint.runOrThrow(args == null ? new String[0] : args);
    }

    static void runRawOrThrow(String[] args) throws IOException, InterruptedException {
        String[] normalizedArgs = normalizeArgsForRenderer(args);
        ExpectedOutputs expected = ExpectedOutputs.fromArgs(normalizedArgs);
        String[] rendererArgs = stripPlatformArgs(normalizedArgs);

        if (expected.skipVerification) {
            runSelectedPlatform(expected.platform, rendererArgs);
            return;
        }

        expected.validateRequestedModes();
        expected.deleteExpectedArtifacts();
        runSelectedPlatform(expected.platform, rendererArgs);
        expected.generateAndOverlayOpImageIfRequested();
        expected.verifyArtifactsExist();
    }

    private static void runSelectedPlatform(String platform, String[] args) {
        if ("x".equals(platform) || "twitter".equals(platform)) {
            XPlatformRunner.main(args);
            return;
        }
        if ("reddit".equals(platform)) {
            RedditScreenshotGenerator.main(args);
            return;
        }
        throw new IllegalArgumentException("Unsupported platform: " + platform + ". Supported values: reddit, x.");
    }

    private static String[] stripPlatformArgs(String[] args) {
        if (args == null || args.length == 0) {
            return new String[0];
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--platform".equals(arg)) {
                if (i + 1 < args.length) {
                    i++;
                }
                continue;
            }
            result.add(arg);
        }
        return result.toArray(new String[0]);
    }

    private static String[] normalizeArgsForRenderer(String[] args) {
        if (args == null || args.length == 0 || !Arrays.asList(args).contains("--voice")) {
            return args == null ? new String[0] : args.clone();
        }

        List<String> dependencies = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (VOICE_DEPENDENCY_OPTIONS.contains(arg) && i + 1 < args.length) {
                dependencies.add(arg);
                dependencies.add(args[++i]);
                continue;
            }
            rest.add(arg);
        }

        int voiceIndex = rest.indexOf("--voice");
        if (voiceIndex < 0 || dependencies.isEmpty()) {
            return args.clone();
        }

        rest.addAll(voiceIndex, dependencies);
        return rest.toArray(new String[0]);
    }

    private static class ExpectedOutputs {
        String platform = "reddit";
        Path commentsFile = Path.of("data", "comments.txt");
        Path outputDirectory = Path.of("output");
        Path audioDirectory = Path.of("output", "audio");
        Path videoDirectory = Path.of("output", "video");
        String outputPrefix = "aithread";
        String ttsEngine = "none";
        String finalVideoName = "final.mp4";
        String postTitle = "Finish this story in the comments";
        String topic = "weird everyday stories";
        OpImageSettings opImageSettings = new OpImageSettings();
        int count = -1;
        boolean autoGenerateText = false;
        boolean createVideo = false;
        boolean concatVideo = false;
        boolean skipVerification = false;

        static ExpectedOutputs fromArgs(String[] args) throws IOException {
            ExpectedOutputs expected = loadDefaults();
            int positional = 0;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg == null || arg.isBlank()) {
                    continue;
                }

                if (arg.startsWith("--")) {
                    if ("--list-voices".equals(arg) || "--gui".equals(arg)) {
                        expected.skipVerification = true;
                    } else if ("--platform".equals(arg) && i + 1 < args.length) {
                        expected.platform = normalizePlatform(args[++i]);
                    } else if ("--auto".equals(arg)) {
                        expected.autoGenerateText = true;
                    } else if ("--video".equals(arg)) {
                        expected.createVideo = true;
                    } else if ("--concat-video".equals(arg)) {
                        expected.createVideo = true;
                        expected.concatVideo = true;
                    } else if ("--count".equals(arg) && i + 1 < args.length) {
                        expected.count = parseInt(args[++i], expected.count);
                    } else if ("--prefix".equals(arg) && i + 1 < args.length) {
                        expected.outputPrefix = args[++i];
                    } else if ("--post-title".equals(arg) && i + 1 < args.length) {
                        expected.postTitle = args[++i];
                    } else if ("--topic".equals(arg) && i + 1 < args.length) {
                        expected.topic = args[++i];
                    } else if ("--tts".equals(arg) && i + 1 < args.length) {
                        expected.ttsEngine = args[++i].toLowerCase(Locale.ROOT);
                    } else if ("--audio-dir".equals(arg) && i + 1 < args.length) {
                        expected.audioDirectory = Path.of(args[++i]);
                    } else if ("--video-dir".equals(arg) && i + 1 < args.length) {
                        expected.videoDirectory = Path.of(args[++i]);
                    } else if ("--final-video".equals(arg) && i + 1 < args.length) {
                        expected.finalVideoName = args[++i];
                    } else if (CliOptions.isValueOption(arg) && i + 1 < args.length) {
                        expected.opImageSettings.applyArg(arg, args[++i]);
                    }
                    continue;
                }

                if (positional == 0) {
                    expected.commentsFile = Path.of(arg);
                } else if (positional == 1) {
                    expected.outputDirectory = Path.of(arg);
                }
                positional++;
            }
            return expected;
        }

        private static ExpectedOutputs loadDefaults() throws IOException {
            ExpectedOutputs expected = new ExpectedOutputs();
            Path defaults = Path.of("defaults.txt");
            if (!Files.exists(defaults)) {
                return expected;
            }

            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(defaults)) {
                properties.load(input);
            }
            expected.platform = normalizePlatform(properties.getProperty("platform", expected.platform));
            expected.outputPrefix = properties.getProperty("prefix", expected.outputPrefix);
            expected.ttsEngine = properties.getProperty("ttsEngine", expected.ttsEngine).toLowerCase(Locale.ROOT);
            expected.audioDirectory = Path.of(properties.getProperty("audioDirectory", expected.audioDirectory.toString()));
            expected.videoDirectory = Path.of(properties.getProperty("videoDirectory", expected.videoDirectory.toString()));
            expected.finalVideoName = properties.getProperty("finalVideoName", expected.finalVideoName);
            expected.postTitle = properties.getProperty("postTitle", expected.postTitle);
            expected.topic = properties.getProperty("topic", expected.topic);
            expected.opImageSettings = OpImageSettings.from(properties);
            return expected;
        }

        void validateRequestedModes() throws IOException {
            if (createVideo && !ttsEnabled()) {
                throw new IOException("Video was requested, but TTS is disabled. Use --tts kokoro or --tts piper before --video.");
            }
        }

        void deleteExpectedArtifacts() throws IOException {
            for (Path path : expectedArtifactPaths(true)) {
                Files.deleteIfExists(path);
            }
        }

        void generateAndOverlayOpImageIfRequested() throws IOException, InterruptedException {
            if (!opImageSettings.isEnabled() || expectedCount(false) <= 0) {
                return;
            }
            Path opScreenshotPath = outputDirectory.resolve("0" + outputPrefix + ".png");
            String visibleTitle = "reddit".equals(platform) ? postTitle : "";
            OpImagePipeline.generateAndOverlay(
                    platform,
                    visibleTitle,
                    originalPostBodyForImagePrompt(),
                    opScreenshotPath,
                    outputPrefix,
                    opImageSettings
            );
        }

        void verifyArtifactsExist() throws IOException {
            for (Path path : expectedArtifactPaths(false)) {
                if (!Files.exists(path)) {
                    throw new IOException("Expected output was not created: " + path);
                }
            }
        }

        private List<Path> expectedArtifactPaths(boolean forDeletion) throws IOException {
            int expectedCount = expectedCount(forDeletion);
            List<Path> paths = new ArrayList<>();
            for (int i = 0; i < expectedCount; i++) {
                String baseName = i + outputPrefix;
                paths.add(outputDirectory.resolve(baseName + ".png"));
                if (forDeletion || ttsEnabled()) {
                    paths.add(audioDirectory.resolve(baseName + ".txt"));
                    paths.add(audioDirectory.resolve(baseName + ".voice.json"));
                }
                if (ttsEnabled()) {
                    paths.add(audioDirectory.resolve(baseName + ".wav"));
                    if (createVideo) {
                        paths.add(videoDirectory.resolve(baseName + ".mp4"));
                    }
                }
            }

            if (ttsEnabled() && createVideo && concatVideo && expectedCount > 0) {
                paths.add(videoDirectory.resolve(finalVideoName));
            }
            return paths;
        }

        private int expectedCount(boolean forDeletion) throws IOException {
            if (autoGenerateText) {
                return count >= 0 ? count : 10;
            }
            if (forDeletion && count >= 0) {
                return count;
            }

            int availableLines = countInputLines();
            if (count >= 0) {
                return Math.min(availableLines, count);
            }
            return availableLines;
        }

        private String originalPostBodyForImagePrompt() throws IOException {
            if (autoGenerateText) {
                return topic;
            }
            if (!Files.exists(commentsFile)) {
                throw new IOException("Input comments file was not found: " + commentsFile);
            }
            try (var lines = Files.lines(commentsFile)) {
                return lines.map(String::trim)
                        .filter(line -> !line.isBlank())
                        .findFirst()
                        .orElse(topic);
            }
        }

        private int countInputLines() throws IOException {
            if (!Files.exists(commentsFile)) {
                throw new IOException("Input comments file was not found: " + commentsFile);
            }
            try (var lines = Files.lines(commentsFile)) {
                return (int) lines.map(String::trim).filter(line -> !line.isBlank()).count();
            }
        }

        private boolean ttsEnabled() {
            return ttsEngine != null && !ttsEngine.isBlank() && !"none".equalsIgnoreCase(ttsEngine);
        }

        private static String normalizePlatform(String value) {
            if (value == null || value.isBlank()) {
                return "reddit";
            }
            String cleaned = value.trim().toLowerCase(Locale.ROOT);
            if ("twitter".equals(cleaned)) {
                return "x";
            }
            return cleaned;
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
