package redditTxtToImg;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import javax.imageio.ImageIO;

/** Generates a small seeded multi-octave Perlin texture for final video grain. */
final class PerlinNoiseTexture {
    private PerlinNoiseTexture() {
    }

    static Path generate(Path output, int width, int height, long seed) throws IOException {
        int safeWidth = Math.max(32, width);
        int safeHeight = Math.max(32, height);
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }

        int[] permutation = buildPermutation(seed);
        BufferedImage image = new BufferedImage(safeWidth, safeHeight, BufferedImage.TYPE_BYTE_GRAY);
        double baseScale = 4.5;

        for (int y = 0; y < safeHeight; y++) {
            for (int x = 0; x < safeWidth; x++) {
                double nx = (x / (double) safeWidth) * baseScale;
                double ny = (y / (double) safeHeight) * baseScale * (safeHeight / (double) safeWidth);
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
                int rgb = (gray << 16) | (gray << 8) | gray;
                image.setRGB(x, y, rgb);
            }
        }

        if (!ImageIO.write(image, "png", output.toFile())) {
            throw new IOException("Could not encode Perlin texture: " + output);
        }
        return output;
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
}
