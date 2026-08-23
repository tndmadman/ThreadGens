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
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

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

            verifyPerlinSeedProgression(temp);
            verifyBackgroundPaletteChanges(source, temp);
            verifySmoothRevealVideo(temp);

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
                    require(states.size() >= 2, "Expected multiple compatibility states for generic frame " + format.id());
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

            verifyFinalTemporalTexture(generator, source, audio, temp);
            System.out.println("P0 video smoke tests passed for smooth reveal, all formats, palette rotation, per-frame Perlin seeds, and final temporal texture.");
        } finally {
            System.clearProperty("threadgens.palette");
            deleteRecursively(temp);
        }
    }

    private static void verifyPerlinSeedProgression(Path temp) throws Exception {
        int width = 48;
        int height = 40;
        int frames = 3;
        long baseSeed = 912_345L;
        Path firstSequence = temp.resolve("perlin_sequence_a.gray");
        Path secondSequence = temp.resolve("perlin_sequence_b.gray");

        PerlinNoiseTexture.RawSequence sequence = PerlinNoiseTexture.generateRawSequence(
                firstSequence, width, height, baseSeed, frames);
        PerlinNoiseTexture.generateRawSequence(secondSequence, width, height, baseSeed, frames);

        require(sequence.width() == width && sequence.height() == height && sequence.frameCount() == frames,
                "Perlin raw sequence metadata did not preserve requested dimensions/frame count.");
        require(PerlinNoiseTexture.frameSeed(baseSeed, 0) == baseSeed,
                "Perlin frame zero must use the base seed.");
        require(PerlinNoiseTexture.frameSeed(baseSeed, 1) == baseSeed + 1,
                "Perlin seed must increment exactly once per frame.");
        require(PerlinNoiseTexture.frameSeed(baseSeed, 2) == baseSeed + 2,
                "Perlin seed progression must continue monotonically.");

        byte[] bytes = Files.readAllBytes(firstSequence);
        byte[] repeat = Files.readAllBytes(secondSequence);
        int frameSize = width * height;
        require(bytes.length == frameSize * frames,
                "Perlin raw sequence byte size does not match gray8 frame dimensions.");
        require(Arrays.equals(bytes, repeat),
                "A fixed Perlin base seed must produce a deterministic animation sequence.");

        byte[] frame0 = Arrays.copyOfRange(bytes, 0, frameSize);
        byte[] frame1 = Arrays.copyOfRange(bytes, frameSize, frameSize * 2);
        byte[] frame2 = Arrays.copyOfRange(bytes, frameSize * 2, frameSize * 3);
        require(!Arrays.equals(frame0, frame1) && !Arrays.equals(frame1, frame2),
                "Consecutive Perlin frames must change when their seeds increment.");
    }

    private static void verifySmoothRevealVideo(Path temp) throws Exception {
        String narration = "first line moves smoothly second line follows exactly";
        Path source = temp.resolve("smooth_reddit.png");
        Path audio = temp.resolve("smooth_reddit.wav");
        createRedditLikeSource(source, 360, 640);
        createTone(audio, 2.4);
        writeExactTiming(audio, narration, 2.0);

        DynamicVideoGenerator generator = new DynamicVideoGenerator("ffmpeg", 120, 30);
        List<TimedVisualStateRenderer.RenderedState> states = TimedVisualStateRenderer.renderStates(
                source,
                narration,
                ContentFormat.THREAD_STORY,
                1,
                2,
                2.4,
                temp.resolve("smooth_states"),
                "smooth");
        require(states.size() == 1 && TimedVisualStateRenderer.hasSmoothRevealAssets(states.get(0).imagePath()),
                "Reddit fixture must route through the smooth reveal path.");

        Path clip = temp.resolve("smooth_reveal.mp4");
        generator.renderClip(states, audio, clip, 360, 640, ContentFormat.THREAD_STORY, 1);
        require(Files.size(clip) > 1_000, "Smooth reveal clip is unexpectedly small.");
        require(hasAudioAndVideoStreams(clip), "Smooth reveal clip must keep audio and video streams.");

        String early = frameMd5(clip, 0.10);
        String middle = frameMd5(clip, 1.10);
        String late = frameMd5(clip, 2.10);
        require(!early.equals(middle) && !middle.equals(late) && !early.equals(late),
                "Decoded smooth-reveal frames must change as narration advances.");
        TimedVisualStateRenderer.cleanup(states);
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

    private static void verifyBackgroundPaletteChanges(Path source, Path temp) throws Exception {
        Path ember = temp.resolve("palette_ember.png");
        Path ocean = temp.resolve("palette_ocean.png");

        System.setProperty("threadgens.palette", "ember");
        DynamicVisualRenderer.render(source, "", ContentFormat.THREAD_STORY, 0, 1, ember);
        System.setProperty("threadgens.palette", "ocean");
        DynamicVisualRenderer.render(source, "", ContentFormat.THREAD_STORY, 0, 1, ocean);
        System.clearProperty("threadgens.palette");

        BufferedImage emberImage = ImageIO.read(ember.toFile());
        BufferedImage oceanImage = ImageIO.read(ocean.toFile());
        require(emberImage != null && oceanImage != null, "Palette regression images were not readable.");

        int emberPixel = emberImage.getRGB(8, 8) & 0x00ffffff;
        int oceanPixel = oceanImage.getRGB(8, 8) & 0x00ffffff;
        require(emberPixel != oceanPixel,
                "Different per-video palettes must produce different dark-background pixels.");
    }

    private static void verifyFinalTemporalTexture(
            DynamicVideoGenerator generator,
            Path source,
            Path audio,
            Path temp
    ) throws Exception {
        Path staticClip = temp.resolve("texture_static_source.mp4");
        Path finalVideo = temp.resolve("texture_final.mp4");
        generator.renderClip(source, audio, staticClip, 360, 640, ContentFormat.THREAD_STORY, 0);
        generator.combineClips(List.of(staticClip), finalVideo, ContentFormat.THREAD_STORY);

        String firstFrame = frameMd5(finalVideo, 0.25);
        String laterFrame = frameMd5(finalVideo, 0.85);
        require(!firstFrame.isBlank() && !laterFrame.isBlank(),
                "Could not hash decoded frames from final textured video.");
        require(!firstFrame.equals(laterFrame),
                "Final completed video must contain temporal texture; two static-source frames decoded identically.");
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
        require(exit == 0, "ffmpeg frame MD5 probe failed for " + media + ": " + output);
        return output.toString();
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
        g.drawString("static state smoke frame", 40, 160);
        g.dispose();
        ImageIO.write(image, "png", path.toFile());
    }

    private static void createRedditLikeSource(Path path, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(16, 17, 19));
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(31, 31, 33));
        g.fillRoundRect(45, 86, 294, 445, 18, 18);
        g.setColor(new Color(230, 70, 10));
        g.fillRoundRect(268, 102, 56, 24, 8, 8);
        g.setColor(new Color(235, 235, 238));
        g.setFont(new Font("Arial", Font.PLAIN, 20));
        g.drawString("first line moves smoothly", 58, 190);
        g.drawString("second line follows exactly", 58, 218);
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
