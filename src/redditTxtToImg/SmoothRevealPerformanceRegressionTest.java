package redditTxtToImg;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * Guards against the old per-word/full-frame GEQ implementation, which became
 * slower as narration word count grew and timed out on normal 1080x1920 clips.
 */
public final class SmoothRevealPerformanceRegressionTest {
    private SmoothRevealPerformanceRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path temp = Files.createTempDirectory("threadgens-fast-reveal-");
        try {
            int width = 720;
            int height = 1280;
            int fps = 30;
            int wordCount = 32;
            double spokenDuration = 3.2;
            double audioDuration = 3.4;

            Path full = temp.resolve("full.png");
            Path base = TimedVisualStateRenderer.basePath(full);
            List<TimedVisualStateRenderer.WordBox> boxes = createFrames(full, base, width, height, wordCount);
            String narration = buildNarration(wordCount);
            writeLayout(full, narration, width, height, boxes);

            Path audio = temp.resolve("voice.wav");
            createTone(audio, audioDuration);
            writeExactTiming(audio, narration, spokenDuration);

            DynamicVideoGenerator generator = new DynamicVideoGenerator("ffmpeg", 30, fps);
            List<TimedVisualStateRenderer.RenderedState> states = List.of(
                    new TimedVisualStateRenderer.RenderedState(full, 1.0, 0, 1));
            require(TimedVisualStateRenderer.hasSmoothRevealAssets(full),
                    "performance fixture must activate the production smooth reveal path");

            Path clip = temp.resolve("fast-reveal.mp4");
            long started = System.nanoTime();
            generator.renderClip(states, audio, clip, width, height, ContentFormat.THREAD_STORY, 0);
            double elapsed = (System.nanoTime() - started) / 1_000_000_000.0;

            require(Files.isRegularFile(clip) && Files.size(clip) > 1_000,
                    "optimized smooth reveal did not produce a valid MP4");
            require(elapsed < 30.0,
                    String.format(Locale.US,
                            "smooth reveal exceeded the regression budget: %.2fs for %.1fs of 720x1280 video",
                            elapsed, audioDuration));

            String early = frameMd5(clip, 0.20);
            String middle = frameMd5(clip, 1.70);
            String late = frameMd5(clip, 3.25);
            require(!early.equals(middle) && !middle.equals(late),
                    "smooth reveal frames must continue changing with narration timing");

            System.out.println(String.format(Locale.US,
                    "Smooth reveal performance regression passed: %.2fs wall time for %.1fs 720x1280 clip with %d words.",
                    elapsed, audioDuration, wordCount));
        } finally {
            deleteTree(temp);
        }
    }

    private static List<TimedVisualStateRenderer.WordBox> createFrames(
            Path fullPath,
            Path basePath,
            int width,
            int height,
            int wordCount
    ) throws Exception {
        BufferedImage full = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        BufferedImage base = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D gf = full.createGraphics();
        Graphics2D gb = base.createGraphics();
        Color background = new Color(31, 31, 33);
        gf.setColor(background);
        gb.setColor(background);
        gf.fillRect(0, 0, width, height);
        gb.fillRect(0, 0, width, height);
        gf.setColor(new Color(238, 238, 242));

        List<TimedVisualStateRenderer.WordBox> boxes = new ArrayList<>();
        int columns = 4;
        int boxWidth = 120;
        int boxHeight = 28;
        int gapX = 22;
        int gapY = 18;
        int originX = 72;
        int originY = 250;
        for (int i = 0; i < wordCount; i++) {
            int row = i / columns;
            int column = i % columns;
            int left = originX + column * (boxWidth + gapX);
            int top = originY + row * (boxHeight + gapY);
            int right = left + boxWidth - 1;
            int bottom = top + boxHeight - 1;
            gf.fillRect(left, top, boxWidth, boxHeight);
            boxes.add(new TimedVisualStateRenderer.WordBox(left, top, right, bottom));
        }
        gf.dispose();
        gb.dispose();
        ImageIO.write(full, "png", fullPath.toFile());
        ImageIO.write(base, "png", basePath.toFile());
        return List.copyOf(boxes);
    }

    private static String buildNarration(int wordCount) {
        List<String> words = new ArrayList<>();
        for (int i = 0; i < wordCount; i++) {
            words.add("word" + (i + 1));
        }
        return String.join(" ", words);
    }

    private static void writeLayout(
            Path full,
            String narration,
            int width,
            int height,
            List<TimedVisualStateRenderer.WordBox> boxes
    ) throws Exception {
        String encodedNarration = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(narration.getBytes(StandardCharsets.UTF_8));
        List<String> lines = new ArrayList<>();
        lines.add(TimedVisualStateRenderer.LAYOUT_HEADER);
        lines.add("meta\t" + width + "\t" + height + "\t" + encodedNarration);
        for (TimedVisualStateRenderer.WordBox box : boxes) {
            lines.add("word\t" + box.left() + "\t" + box.top() + "\t" + box.right() + "\t" + box.bottom());
        }
        Files.write(TimedVisualStateRenderer.layoutPath(full), lines, StandardCharsets.UTF_8);
    }

    private static void writeExactTiming(Path audio, String narration, double spokenDuration) throws Exception {
        String[] words = narration.split("\\s+");
        List<String> lines = new ArrayList<>();
        lines.add(NarrationTiming.HEADER);
        for (int i = 0; i < words.length; i++) {
            double start = spokenDuration * i / words.length;
            double end = spokenDuration * (i + 1.0) / words.length;
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(words[i].getBytes(StandardCharsets.UTF_8));
            lines.add(String.format(Locale.US, "word\t%.6f\t%.6f\t%s", start, end, encoded));
        }
        Files.write(NarrationTiming.sidecarFor(audio), lines, StandardCharsets.UTF_8);
    }

    private static void createTone(Path path, double seconds) throws Exception {
        int sampleRate = 24_000;
        int frames = Math.max(1, (int) Math.round(sampleRate * seconds));
        byte[] pcm = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            double angle = 2.0 * Math.PI * 220.0 * i / sampleRate;
            short sample = (short) Math.round(Math.sin(angle) * 2_500.0);
            pcm[i * 2] = (byte) (sample & 0xff);
            pcm[i * 2 + 1] = (byte) ((sample >>> 8) & 0xff);
        }
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        try (AudioInputStream input = new AudioInputStream(
                new ByteArrayInputStream(pcm), format, frames)) {
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, path.toFile());
        }
    }

    private static String frameMd5(Path media, double seconds) throws Exception {
        Process process = new ProcessBuilder(
                "ffmpeg", "-v", "error",
                "-ss", String.format(Locale.US, "%.3f", seconds),
                "-i", media.toString(),
                "-frames:v", "1",
                "-f", "md5", "-")
                .redirectErrorStream(true)
                .start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line.trim());
            }
        }
        int exit = process.waitFor();
        require(exit == 0, "ffmpeg frame probe failed: " + output);
        return output.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
