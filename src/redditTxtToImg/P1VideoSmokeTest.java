package redditTxtToImg;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/** Optional local integration test for FFmpeg/libass caption rendering. */
public final class P1VideoSmokeTest {
    private P1VideoSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        String ffmpeg = args.length > 0 ? args[0] : "ffmpeg";
        String ffprobe = args.length > 1 ? args[1] : "ffprobe";
        Path temp = Files.createTempDirectory("threadgens-p1-video");
        try {
            Path source = temp.resolve("source.png");
            writeSourceImage(source);
            Path audio = temp.resolve("narration.wav");
            writeTone(audio, 4.0);

            DynamicVideoGenerator generator = new DynamicVideoGenerator(ffmpeg, 120, 30);
            double duration = generator.probeDurationSeconds(audio);
            String narration = "This first sentence begins the test. The second sentence changes the scene and highlights each word.";
            CaptionTimeline timeline = CaptionTimeline.create(narration, duration, "word", 5);
            Path captions = timeline.writeAss(temp.resolve("caption, test's.ass"), 540, 960);

            List<CaptionTimeline.Scene> scenes = timeline.scenes(4);
            List<TimedVisualStateRenderer.RenderedState> frames = TimedVisualStateRenderer.renderStates(
                    source,
                    narration,
                    ContentFormat.CONFESSION,
                    0,
                    1,
                    scenes,
                    duration,
                    temp,
                    "video-smoke");

            Path output = temp.resolve("captioned.mp4");
            String disclosure = "AI-assisted ThreadGens integration test";
            generator.renderClip(
                    frames,
                    audio,
                    captions,
                    output,
                    540,
                    960,
                    ContentFormat.CONFESSION,
                    0,
                    Map.of("comment", disclosure, "encoded_by", "ThreadGens P1 test"));

            require(Files.exists(output) && Files.size(output) > 10_000,
                    "FFmpeg should create a non-empty captioned MP4.");
            double outputDuration = generator.probeDurationSeconds(output);
            require(outputDuration >= duration, "Final MP4 should cover the complete narration.");
            String comment = ProcessRunner.runAndCapture(
                    List.of(ffprobe, "-v", "error", "-show_entries", "format_tags=comment",
                            "-of", "default=noprint_wrappers=1:nokey=1", output.toString()),
                    "P1 metadata probe",
                    30).trim();
            require(comment.contains(disclosure), "MP4 should contain the AI disclosure metadata tag.");
            TimedVisualStateRenderer.cleanup(frames);
            System.out.println("P1 FFmpeg video smoke test passed.");
        } finally {
            deleteRecursively(temp);
        }
    }

    private static void writeSourceImage(Path output) throws Exception {
        BufferedImage image = new BufferedImage(540, 960, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(22, 25, 31));
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setColor(new Color(71, 122, 166));
        g.fillRoundRect(55, 130, 430, 620, 36, 36);
        g.setColor(Color.WHITE);
        g.drawString("ThreadGens P1", 210, 450);
        g.dispose();
        ImageIO.write(image, "png", output.toFile());
    }

    private static void writeTone(Path output, double seconds) throws Exception {
        float sampleRate = 24_000f;
        int frames = (int) Math.round(sampleRate * seconds);
        byte[] pcm = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            short sample = (short) (Math.sin(2.0 * Math.PI * 220.0 * i / sampleRate) * 3200);
            pcm[i * 2] = (byte) (sample & 0xff);
            pcm[i * 2 + 1] = (byte) ((sample >>> 8) & 0xff);
        }
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        try (AudioInputStream input = new AudioInputStream(
                new ByteArrayInputStream(pcm), format, frames)) {
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, output.toFile());
        }
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
