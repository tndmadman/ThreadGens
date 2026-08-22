package redditTxtToImg;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

/** Exercises P2 capture with real FFmpeg/ffprobe media and P1 sidecar integration. */
public final class P2ArtifactSmokeTest {
    private P2ArtifactSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("threadgens-p2-artifact-");
        try {
            Path image = dir.resolve("0audit.png");
            BufferedImage buffered = new BufferedImage(320, 568, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = buffered.createGraphics();
            try {
                graphics.setColor(new Color(30, 30, 30));
                graphics.fillRect(0, 0, 320, 568);
                graphics.setColor(Color.WHITE);
                graphics.fillOval(28, 88, 48, 48);
                graphics.drawString("Smoke Author", 88, 112);
                graphics.drawString("P2 artifact smoke", 40, 220);
                graphics.drawString("different visible states", 40, 300);
            } finally {
                graphics.dispose();
            }
            ImageIO.write(buffered, "png", image.toFile());

            Path audio = dir.resolve("0audit.wav");
            run(List.of("ffmpeg", "-y", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
                    "-t", "0.8", audio.toString()));
            Path video = dir.resolve("final.mp4");
            run(List.of("ffmpeg", "-y", "-loop", "1", "-i", image.toString(), "-i", audio.toString(),
                    "-t", "1.0", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", video.toString()));

            Path voiceSidecar = dir.resolve("0audit.voice.json");
            Files.writeString(voiceSidecar,
                    "{\"engine\":\"kokoro\",\"voice\":\"af_bella\",\"delivery\":\"calm\",\"speed\":0.92}");
            PublishFingerprint first = PublishFingerprint.capture(new PublishFingerprint.CaptureInput(
                    "reddit", "thread_story", "A unique P2 artifact smoke test story.",
                    List.of(video), List.of(image), List.of(audio), "configured_but_not_rendered", "kokoro", "", "ffmpeg"));
            require(!first.artifactHash.isBlank(), "artifact hash must exist");
            require(first.visualHashes.size() >= 4,
                    "visual fingerprint must include source image plus sampled finished-video frames");
            require(first.identityHashes.size() >= 2,
                    "identity fingerprint must include rendered avatar and author/header regions");
            require(!first.segmentDurations.isEmpty() && first.segmentDurations.get(0) > 0,
                    "ffprobe audio duration must exist");
            require(first.totalDuration > 0, "ffprobe final-video duration must exist");
            require("kokoro:af_bella".equals(first.voice),
                    "P2 must fingerprint the actual P1-rendered voice sidecar, not the launch-time voice argument");

            Files.deleteIfExists(voiceSidecar);
            PublishFingerprint defaultVoice = PublishFingerprint.capture(new PublishFingerprint.CaptureInput(
                    "reddit", "thread_story", "Default voice identity probe.",
                    List.of(video), List.of(image), List.of(audio), "unknown", "piper", "", "ffmpeg"));
            require(!"unknown".equalsIgnoreCase(defaultVoice.voice),
                    "P2 must resolve the renderer's configured default voice when no P1 sidecar exists");

            Files.writeString(voiceSidecar,
                    "{\"engine\":\"kokoro\",\"voice\":\"af_bella\",\"delivery\":\"calm\",\"speed\":0.92}");
            Path historyPath = dir.resolve("publish_history.jsonl");
            PublishAuditHistory history = new PublishAuditHistory(historyPath, 20);
            history.record(first, "PASS", 10);
            PublishFingerprint duplicate = PublishFingerprint.capture(new PublishFingerprint.CaptureInput(
                    "reddit", "thread_story", "Different text but same finished artifact.",
                    List.of(video), List.of(image), List.of(audio), "other_voice", "kokoro", "", "ffmpeg"));
            PrePublishAuditor.Result result = new PrePublishAuditor(58, 78).assess(duplicate, history.load());
            require(result.status() == PrePublishAuditor.Status.BLOCK, "same finished artifact must block");
            require(result.scores().identity() > 0.99, "same rendered identity must compare as identical");
            require(result.scores().audio() > 0.99, "same actual P1 voice signature must compare as identical");
            System.out.println("P2 artifact smoke test passed.");
        } finally {
            deleteTree(dir);
        }
    }

    private static void run(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) throw new IOException("Command failed (" + exit + "): " + String.join(" ", command) + "\n" + output);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) Files.deleteIfExists(path);
        }
    }
}
