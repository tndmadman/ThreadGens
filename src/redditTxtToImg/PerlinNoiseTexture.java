package redditTxtToImg;

import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import javax.imageio.ImageIO;

/** Generates seeded multi-octave Perlin textures for the final video pass. */
final class PerlinNoiseTexture {
    private static final int MIN_DIMENSION = 32;

    private PerlinNoiseTexture() {
    }

    /** Compatibility helper for callers/tests that need one static PNG texture. */
    static Path generate(Path output, int width, int height, long seed) throws IOException {
        int safeWidth = Math.max(MIN_DIMENSION, width);
        int safeHeight = Math.max(MIN_DIMENSION, height);
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }

        byte[] pixels = renderGrayFrame(safeWidth, safeHeight, seed);
        BufferedImage image = new BufferedImage(safeWidth, safeHeight, BufferedImage.TYPE_BYTE_GRAY);
        image.getRaster().setDataElements(0, 0, safeWidth, safeHeight, pixels);

        if (!ImageIO.write(image, "png", output.toFile())) {
            throw new IOException("Could not encode Perlin texture: " + output);
        }
        return output;
    }

    /**
     * Writes a compact raw gray8 animation. Frame N is generated from
     * {@code baseSeed + N}, so the Perlin field itself changes on every video
     * frame while remaining deterministic for a given base seed.
     */
    static RawSequence generateRawSequence(
            Path output,
            int width,
            int height,
            long baseSeed,
            int frameCount
    ) throws IOException {
        int safeWidth = Math.max(MIN_DIMENSION, width);
        int safeHeight = Math.max(MIN_DIMENSION, height);
        int safeFrameCount = Math.max(1, frameCount);
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }

        byte[] frame = new byte[Math.multiplyExact(safeWidth, safeHeight)];
        try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(output), 1024 * 1024)) {
            for (int frameIndex = 0; frameIndex < safeFrameCount; frameIndex++) {
                fillGrayFrame(frame, safeWidth, safeHeight, frameSeed(baseSeed, frameIndex));
                stream.write(frame);
            }
        }
        return new RawSequence(output, safeWidth, safeHeight, safeFrameCount, baseSeed);
    }

    static long frameSeed(long baseSeed, int frameIndex) {
        if (frameIndex < 0) {
            throw new IllegalArgumentException("Perlin frame index must be non-negative: " + frameIndex);
        }
        return baseSeed + frameIndex;
    }

    static byte[] renderGrayFrame(int width, int height, long seed) {
        int safeWidth = Math.max(MIN_DIMENSION, width);
        int safeHeight = Math.max(MIN_DIMENSION, height);
        byte[] frame = new byte[Math.multiplyExact(safeWidth, safeHeight)];
        fillGrayFrame(frame, safeWidth, safeHeight, seed);
        return frame;
    }

    private static void fillGrayFrame(byte[] frame, int width, int height, long seed) {
        if (frame.length != Math.multiplyExact(width, height)) {
            throw new IllegalArgumentException("Perlin frame buffer does not match requested dimensions.");
        }

        int[] permutation = buildPermutation(seed);
        double baseScale = 4.5;
        int offset = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double nx = (x / (double) width) * baseScale;
                double ny = (y / (double) height) * baseScale * (height / (double) width);
                double amplitude = 1.0;
                double frequency = 1.0;
                double sum = 0.0;
                double normalization = 0.0;

                for (int octave = 0; octave < 4; octave++) {
                    sum += amplitude * noise(nx * frequency, ny * frequency, permutation);
                    normalization += amplitude;
                    amplitude *= 0.52;
                    frequency *= 2.03;
                }

                double value = normalization <= 0.0 ? 0.0 : sum / normalization;
                int gray = clamp((int) Math.round(128.0 + value * 46.0));
                frame[offset++] = (byte) gray;
            }
        }
    }

    private static int[] buildPermutation(long seed) {
        int[] base = new int[256];
        for (int i = 0; i < base.length; i++) {
            base[i] = i;
        }
        Random random = new Random(seed);
        for (int i = base.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swap = base[i];
            base[i] = base[j];
            base[j] = swap;
        }

        int[] permutation = new int[512];
        for (int i = 0; i < permutation.length; i++) {
            permutation[i] = base[i & 255];
        }
        return permutation;
    }

    private static double noise(double x, double y, int[] p) {
        int xi = fastFloor(x) & 255;
        int yi = fastFloor(y) & 255;
        double xf = x - Math.floor(x);
        double yf = y - Math.floor(y);

        double u = fade(xf);
        double v = fade(yf);

        int aa = p[p[xi] + yi];
        int ab = p[p[xi] + yi + 1];
        int ba = p[p[xi + 1] + yi];
        int bb = p[p[xi + 1] + yi + 1];

        double x1 = lerp(grad(aa, xf, yf), grad(ba, xf - 1.0, yf), u);
        double x2 = lerp(grad(ab, xf, yf - 1.0), grad(bb, xf - 1.0, yf - 1.0), u);
        return lerp(x1, x2, v);
    }

    private static int fastFloor(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double a, double b, double t) {
        return a + t * (b - a);
    }

    private static double grad(int hash, double x, double y) {
        return switch (hash & 7) {
            case 0 -> x + y;
            case 1 -> -x + y;
            case 2 -> x - y;
            case 3 -> -x - y;
            case 4 -> x;
            case 5 -> -x;
            case 6 -> y;
            default -> -y;
        };
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    record RawSequence(Path path, int width, int height, int frameCount, long baseSeed) {
    }
}
