package redditTxtToImg;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Wrapper used by the Windows runners when OP images may be enabled.
 *
 * The normal generators create videos inside their own batch flow. For OP images, the image overlay
 * must happen before the video clip is made, so this wrapper delays video generation until after
 * CheckedRunner has rendered/overlaid the PNGs and generated audio.
 */
public class OpImageVideoSafeRunner {
    public static void main(String[] args) {
        try {
            run(args == null ? new String[0] : args);
        } catch (Exception e) {
            System.err.println("OP-image safe run failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    static void run(String[] args) throws IOException, InterruptedException {
        RunSettings settings = RunSettings.fromArgs(args);
        if (!settings.shouldDelayVideo()) {
            CheckedRunner.runOrThrow(args);
            return;
        }

        System.out.println("OP image + video requested: rendering images/audio first so the OP overlay is included in the video.");
        CheckedRunner.runOrThrow(stripVideoModeArgs(args));
        settings.renderVideosFromExistingArtifacts();
    }

    private static String[] stripVideoModeArgs(String[] args) {
        List<String> result = new ArrayList<>();
        for (String arg : args) {
            if ("--video".equals(arg) || "--concat-video".equals(arg)) {
                continue;
            }
            result.add(arg);
        }
        return result.toArray(new String[0]);
    }

    private static class RunSettings {
        Path commentsFile = Path.of("data", "comments.txt");
        Path outputDirectory = Path.of("output");
        Path audioDirectory = Path.of("output", "audio");
        Path videoDirectory = Path.of("output", "video");
        String outputPrefix = "aithread";
        String finalVideoName = "final.mp4";
        String videoCommand = "ffmpeg";
        String ttsEngine = "none";
        int count = -1;
        int width = 1080;
        int height = 1920;
        int videoFps = 30;
        int videoTimeoutSeconds = 180;
        boolean autoGenerateText = false;
        boolean createVideo = false;
        boolean concatVideo = false;
        boolean opImageEnabled = false;

        static RunSettings fromArgs(String[] args) throws IOException {
            RunSettings settings = loadDefaults();
            int positional = 0;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg == null || arg.isBlank()) {
                    continue;
                }
                if (arg.startsWith("--")) {
                    if ("--auto".equals(arg)) settings.autoGenerateText = true;
                    else if ("--video".equals(arg)) settings.createVideo = true;
                    else if ("--concat-video".equals(arg)) { settings.createVideo = true; settings.concatVideo = true; }
                    else if ("--count".equals(arg) && i + 1 < args.length) settings.count = parseInt(args[++i], settings.count);
                    else if ("--prefix".equals(arg) && i + 1 < args.length) settings.outputPrefix = args[++i];
                    else if ("--tts".equals(arg) && i + 1 < args.length) settings.ttsEngine = args[++i].toLowerCase();
                    else if ("--audio-dir".equals(arg) && i + 1 < args.length) settings.audioDirectory = Path.of(args[++i]);
                    else if ("--video-dir".equals(arg) && i + 1 < args.length) settings.videoDirectory = Path.of(args[++i]);
                    else if ("--video-command".equals(arg) && i + 1 < args.length) settings.videoCommand = args[++i];
                    else if ("--fps".equals(arg) && i + 1 < args.length) settings.videoFps = parseInt(args[++i], settings.videoFps);
                    else if ("--video-timeout".equals(arg) && i + 1 < args.length) settings.videoTimeoutSeconds = parseInt(args[++i], settings.videoTimeoutSeconds);
                    else if ("--final-video".equals(arg) && i + 1 < args.length) settings.finalVideoName = args[++i];
                    else if ("--image-mode".equals(arg) && i + 1 < args.length) {
                        String mode = args[++i].trim();
                        settings.opImageEnabled = !mode.isBlank() && !"none".equalsIgnoreCase(mode);
                    } else if (CliOptions.isValueOption(arg) && i + 1 < args.length) {
                        i++;
                    }
                    continue;
                }

                if (positional == 0) {
                    settings.commentsFile = Path.of(arg);
                } else if (positional == 1) {
                    settings.outputDirectory = Path.of(arg);
                }
                positional++;
            }
            return settings;
        }

        private static RunSettings loadDefaults() throws IOException {
            RunSettings settings = new RunSettings();
            Path defaults = Path.of("defaults.txt");
            if (!Files.exists(defaults)) {
                return settings;
            }
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(defaults)) {
                properties.load(input);
            }
            settings.width = parseInt(properties.getProperty("width"), settings.width);
            settings.height = parseInt(properties.getProperty("height"), settings.height);
            settings.outputPrefix = properties.getProperty("prefix", settings.outputPrefix);
            settings.ttsEngine = properties.getProperty("ttsEngine", settings.ttsEngine).toLowerCase();
            settings.audioDirectory = Path.of(properties.getProperty("audioDirectory", settings.audioDirectory.toString()));
            settings.videoDirectory = Path.of(properties.getProperty("videoDirectory", settings.videoDirectory.toString()));
            settings.videoCommand = properties.getProperty("videoCommand", settings.videoCommand);
            settings.finalVideoName = properties.getProperty("finalVideoName", settings.finalVideoName);
            String imageMode = properties.getProperty("imageMode", "none").trim();
            settings.opImageEnabled = !imageMode.isBlank() && !"none".equalsIgnoreCase(imageMode);
            return settings;
        }

        boolean shouldDelayVideo() {
            return createVideo && opImageEnabled && ttsEnabled();
        }

        void renderVideosFromExistingArtifacts() throws IOException, InterruptedException {
            int expectedCount = expectedCount();
            VideoGenerator videoGenerator = new VideoGenerator(videoCommand, videoTimeoutSeconds);
            List<Path> videoClips = new ArrayList<>();
            Files.createDirectories(videoDirectory);

            System.out.println("Phase 3/4: rendering video clips from OP-image-corrected screenshots...");
            for (int i = 0; i < expectedCount; i++) {
                String baseName = i + outputPrefix;
                Path imagePath = outputDirectory.resolve(baseName + ".png");
                Path audioPath = audioDirectory.resolve(baseName + ".wav");
                Path videoPath = videoDirectory.resolve(baseName + ".mp4");
                Files.deleteIfExists(videoPath);
                videoGenerator.makeClip(imagePath, audioPath, videoPath, width, height, videoFps);
                videoClips.add(videoPath);
                System.out.println("Generated video: " + videoPath);
            }

            if (concatVideo && !videoClips.isEmpty()) {
                System.out.println("Phase 4/4: stitching final video from OP-image-corrected clips...");
                Path finalVideo = videoDirectory.resolve(finalVideoName);
                Files.deleteIfExists(finalVideo);
                videoGenerator.combineClips(videoClips, finalVideo);
                System.out.println("Generated final video: " + finalVideo);
            } else {
                System.out.println("Phase 4/4: no final stitch needed.");
            }
        }

        private int expectedCount() throws IOException {
            if (autoGenerateText) {
                return count >= 0 ? count : 10;
            }
            int availableLines = countInputLines();
            return count >= 0 ? Math.min(availableLines, count) : availableLines;
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
            if (value == null) return fallback;
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }
}
