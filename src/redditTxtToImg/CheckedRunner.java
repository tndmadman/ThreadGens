package redditTxtToImg;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Runs the existing generator and verifies that the expected fresh artifacts were created.
 *
 * RedditScreenshotGenerator.main intentionally prints errors for interactive use, but that makes
 * shell scripts and the GUI think failed runs succeeded. This wrapper gives callers a hard failure
 * signal without changing the renderer internals.
 */
public class CheckedRunner {
    private static final Set<String> VALUE_OPTIONS = Set.of(
            "--count", "--prefix", "--style", "--names", "--profiles",
            "--post-title", "--topic", "--llm-model", "--llm-url", "--script-out",
            "--tts", "--voice", "--voice-dir", "--tts-command", "--audio-dir", "--tts-timeout",
            "--video-dir", "--video-command", "--fps", "--video-timeout", "--final-video"
    );

    private static final Set<String> VOICE_DEPENDENCY_OPTIONS = Set.of("--tts", "--voice-dir");

    public static void main(String[] args) {
        try {
            runOrThrow(args);
        } catch (Exception e) {
            System.err.println("Checked run failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static void runOrThrow(String[] args) throws IOException, InterruptedException {
        String[] normalizedArgs = normalizeArgsForRenderer(args);
        ExpectedOutputs expected = ExpectedOutputs.fromArgs(normalizedArgs);

        if (expected.skipVerification) {
            RedditScreenshotGenerator.main(normalizedArgs);
            return;
        }

        expected.validateRequestedModes();
        expected.deleteExpectedArtifacts();

        RedditScreenshotGenerator.main(normalizedArgs);

        expected.verifyArtifactsExist();
    }

    /**
     * The renderer resolves --voice immediately using the current --tts and --voice-dir values.
     * Moving those dependencies before --voice makes CLI argument order forgiving while leaving
     * RedditScreenshotGenerator untouched.
     */
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
        Path commentsFile = Path.of("data", "comments.txt");
        Path outputDirectory = Path.of("output");
        Path audioDirectory = Path.of("output", "audio");
        Path videoDirectory = Path.of("output", "video");
        String outputPrefix = "aithread";
        String ttsEngine = "none";
        String finalVideoName = "final.mp4";
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
                    } else if ("--tts".equals(arg) && i + 1 < args.length) {
                        expected.ttsEngine = args[++i].toLowerCase();
                    } else if ("--audio-dir".equals(arg) && i + 1 < args.length) {
                        expected.audioDirectory = Path.of(args[++i]);
                    } else if ("--video-dir".equals(arg) && i + 1 < args.length) {
                        expected.videoDirectory = Path.of(args[++i]);
                    } else if ("--final-video".equals(arg) && i + 1 < args.length) {
                        expected.finalVideoName = args[++i];
                    } else if (VALUE_OPTIONS.contains(arg) && i + 1 < args.length) {
                        i++;
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
            expected.outputPrefix = properties.getProperty("prefix", expected.outputPrefix);
            expected.ttsEngine = properties.getProperty("ttsEngine", expected.ttsEngine).toLowerCase();
            expected.audioDirectory = Path.of(properties.getProperty("audioDirectory", expected.audioDirectory.toString()));
            expected.videoDirectory = Path.of(properties.getProperty("videoDirectory", expected.videoDirectory.toString()));
            expected.finalVideoName = properties.getProperty("finalVideoName", expected.finalVideoName);
            return expected;
        }

        void validateRequestedModes() throws IOException {
            if (createVideo && !ttsEnabled()) {
                throw new IOException("Video was requested, but TTS is disabled. Use --tts kokoro or --tts piper before --video.");
            }
        }

        void deleteExpectedArtifacts() throws IOException {
            for (Path path : expectedArtifactPaths()) {
                Files.deleteIfExists(path);
            }
        }

        void verifyArtifactsExist() throws IOException {
            for (Path path : expectedArtifactPaths()) {
                if (!Files.exists(path)) {
                    throw new IOException("Expected output was not created: " + path);
                }
            }
        }

        private List<Path> expectedArtifactPaths() throws IOException {
            int expectedCount = expectedCount();
            List<Path> paths = new ArrayList<>();
            for (int i = 0; i < expectedCount; i++) {
                String baseName = i + outputPrefix;
                paths.add(outputDirectory.resolve(baseName + ".png"));
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

        private int expectedCount() throws IOException {
            if (autoGenerateText) {
                return count >= 0 ? count : 10;
            }

            int availableLines = countInputLines();
            if (count >= 0) {
                return Math.min(availableLines, count);
            }
            return availableLines;
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
