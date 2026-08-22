package redditTxtToImg;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

/**
 * Final integrity safety net.
 *
 * Production renderers now omit synthetic engagement, verification and precise
 * fake timestamps at source. This class therefore no longer paints over normal
 * footer content. It only validates the image and removes the legacy X verified
 * blue if an older/custom renderer somehow introduces it.
 */
final class IntegritySanitizer {
    private IntegritySanitizer() {
    }

    static void sanitize(Path imagePath, String platform) throws IOException {
        if (imagePath == null || !Files.exists(imagePath)) {
            throw new IOException("Cannot validate missing image: " + imagePath);
        }
        BufferedImage image = ImageIO.read(imagePath.toFile());
        if (image == null) {
            throw new IOException("Unsupported or unreadable image: " + imagePath);
        }

        String normalized = platform == null ? "reddit" : platform.trim().toLowerCase();
        if ("x".equals(normalized) || "twitter".equals(normalized)) {
            boolean changed = removeLegacyVerifiedBlue(image);
            if (changed) {
                ImageIO.write(image, "png", imagePath.toFile());
            }
        }
    }

    private static boolean removeLegacyVerifiedBlue(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int minX = Math.max(0, scaled(width, 205, 1080));
        int maxX = Math.min(width - 1, scaled(width, 930, 1080));
        int minY = Math.max(0, scaled(height, 215, 1920));
        int maxY = Math.min(height - 1, scaled(height, 390, 1920));
        boolean changed = false;

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                Color color = new Color(image.getRGB(x, y));
                if (isLegacyVerifiedBlue(color)) {
                    image.setRGB(x, y, Color.BLACK.getRGB());
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static boolean isLegacyVerifiedBlue(Color color) {
        int dr = color.getRed() - 29;
        int dg = color.getGreen() - 155;
        int db = color.getBlue() - 240;
        return (dr * dr) + (dg * dg) + (db * db) <= 42 * 42;
    }

    private static int scaled(int actual, int designPixels, int designSize) {
        return Math.max(1, (int) Math.round((double) actual * designPixels / designSize));
    }
}
