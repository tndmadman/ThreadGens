package redditTxtToImg;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
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
        ExpectedOutputs expected = ExpectedOutputs.fromArgs(args);
        Instant started = Instant.now().minusSeconds(2);

        RedditScreenshotGenerator.main(args);

        if (expected.skipVerification) {
            return;
        }
        expected.verifyFreshArtifacts(started);
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

        void verifyFreshArtifacts(Instant started) throws IOException {
            int expectedCount = expectedCount();
            for (int i = 0; i < expectedCount; i++) {
                String baseName = i + outputPrefix;
                requireFresh(outputDirectory.resolve(baseName + ".png"), started, "image");
                if (ttsEnabled()) {
                    requireFresh(audioDirectory.resolve(baseName + ".wav"), started, "audio");
                    if (createVideo) {
                        requireFresh(videoDirectory.resolve(baseName + ".mp4"), started, "video clip");
                    }
                }
            }

            if (ttsEnabled() && createVideo && concatVideo && expectedCount > 0) {
                requireFresh(videoDirectory.resolve(finalVideoName), started, "final video");
            }
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

        private static void requireFresh(Path path, Instant started, String label) throws IOException {
            if (!Files.exists(path)) {
                throw new IOException("Expected " + label + " was not created: " + path);
            }
            FileTime modified = Files.getLastModifiedTime(path);
            if (modified.toInstant().isBefore(started)) {
                throw new IOException("Expected " + label + " was not refreshed during this run: " + path);
            }
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
