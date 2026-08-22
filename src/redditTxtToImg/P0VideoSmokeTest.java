package redditTxtToImg;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/** End-to-end FFmpeg smoke coverage for every P0 presentation/transition format. */
public final class P0VideoSmokeTest {
    private P0VideoSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path temp = Files.createTempDirectory("threadgens-p0-video");
        try {
            Path source = temp.resolve("source.png");
            Path audio = temp.resolve("tone.wav");
            createSourceImage(source, 360, 640);
            createTone(audio, 1.25);

            DynamicVideoGenerator generator = new DynamicVideoGenerator("ffmpeg", 120, 24);
            for (ContentFormat format : ContentFormat.values()) {
                List<Path> clips = new ArrayList<>();
                for (int item = 0; item < 2; item++) {
                    String narration = item == 0
                            ? "The first detail changes what the viewer thinks happened. Then a second clue becomes important."
                            : "The response challenges that assumption. A final concrete detail moves the story forward.";
                    Path stateDir = temp.resolve(format.id() + "_states_" + item);
                    List<TimedVisualStateRenderer.RenderedState> states = TimedVisualStateRenderer.renderStates(
                            source,
                            narration,
                            format,
                            item,
                            2,
                            generator.probeDurationSeconds(audio),
                            stateDir,
                            "item" + item
                    );
                    require(states.size() >= 2, "Expected multiple timed visual states for " + format.id());
                    Path clip = temp.resolve(format.id() + "_clip_" + item + ".mp4");
                    generator.renderClip(states, audio, clip, 360, 640, format, item);
                    TimedVisualStateRenderer.cleanup(states);
                    require(Files.size(clip) > 1_000, "Clip is unexpectedly small for " + format.id());
                    clips.add(clip);
                }

                Path finalVideo = temp.resolve(format.id() + "_final.mp4");
                generator.combineClips(clips, finalVideo, format);
                require(Files.exists(finalVideo) && Files.size(finalVideo) > 2_000,
                        "Final video missing for " + format.id());
                require(generator.probeDurationSeconds(finalVideo) > 1.0,
                        "Final video duration invalid for " + format.id());
                require(hasAudioAndVideoStreams(finalVideo),
                        "Final video must contain both audio and video streams for " + format.id());
            }
            System.out.println("P0 video smoke tests passed for all formats.");
        } finally {
            deleteRecursively(temp);
        }
    }

    private static void createSourceImage(Path path, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(20, 22, 28));
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(235, 237, 242));
        g.setFont(new Font("Arial", Font.BOLD, 28));
        g.drawString("ThreadGens P0", 40, 110);
        g.setFont(new Font("Arial", Font.PLAIN, 20));
        g.drawString("dynamic state smoke frame", 40, 160);
        g.dispose();
        ImageIO.write(image, "png", path.toFile());
    }

    private static void createTone(Path path, double seconds) throws Exception {
        int sampleRate = 24_000;
        int frames = Math.max(1, (int) Math.round(sampleRate * seconds));
        byte[] pcm = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            double angle = 2.0 * Math.PI * 220.0 * i / sampleRate;
            short sample = (short) Math.round(Math.sin(angle) * 3_000.0);
            pcm[i * 2] = (byte) (sample & 0xff);
            pcm[i * 2 + 1] = (byte) ((sample >>> 8) & 0xff);
        }
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        try (AudioInputStream input = new AudioInputStream(
                new ByteArrayInputStream(pcm), format, frames)) {
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, path.toFile());
        }
    }

    private static boolean hasAudioAndVideoStreams(Path media) throws Exception {
        Process process = new ProcessBuilder(
                "ffprobe", "-v", "error", "-show_entries", "stream=codec_type",
                "-of", "csv=p=0", media.toString())
                .redirectErrorStream(true)
                .start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        int exit = process.waitFor();
        String text = output.toString();
        return exit == 0 && text.contains("video") && text.contains("audio");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) return;
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
