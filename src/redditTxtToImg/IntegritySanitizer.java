package redditTxtToImg;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

/**
 * Removes generated engagement claims and synthetic verification markers from
 * rendered social screenshots before they are persisted into the P0 output.
 */
final class IntegritySanitizer {
    private static final Color MUTED = new Color(132, 136, 140);
    private static final Color X_DIVIDER = new Color(47, 51, 54);

    private IntegritySanitizer() {
    }

    static void sanitize(Path imagePath, String platform) throws IOException {
        if (imagePath == null || !Files.exists(imagePath)) {
            throw new IOException("Cannot sanitize missing image: " + imagePath);
        }
        BufferedImage image = ImageIO.read(imagePath.toFile());
        if (image == null) {
            throw new IOException("Unsupported or unreadable image: " + imagePath);
        }

        String normalized = platform == null ? "reddit" : platform.trim().toLowerCase();
        if ("x".equals(normalized) || "twitter".equals(normalized)) {
            sanitizeX(image);
        } else {
            sanitizeReddit(image);
        }
        ImageIO.write(image, "png", imagePath.toFile());
    }

    private static void sanitizeReddit(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int margin = scaled(width, 64, 1080);
        int boxBottom = height - scaled(height, 330, 1920);
        int left = margin + scaled(width, 28, 1080);
        int right = width - margin - scaled(width, 28, 1080);
        int top = Math.max(0, boxBottom - scaled(height, 118, 1920));
        int bottom = Math.min(height, boxBottom - scaled(height, 18, 1920));

        Graphics2D g = image.createGraphics();
        configure(g);

        Color card = sample(image,
                Math.max(0, Math.min(width - 1, width / 2)),
                Math.max(0, Math.min(height - 1, top - scaled(height, 18, 1920))),
                new Color(24, 24, 25));
        g.setColor(card);
        g.fillRect(left, top, Math.max(1, right - left), Math.max(1, bottom - top));

        g.setColor(new Color(70, 72, 74));
        g.setStroke(new BasicStroke(Math.max(1f, width / 540f)));
        g.drawLine(left, top + 2, right, top + 2);

        int fontSize = Math.max(14, scaled(height, 22, 1920));
        g.setFont(new Font("Arial", Font.PLAIN, fontSize));
        g.setColor(MUTED);
        String message = "Fictional thread  •  engagement hidden";
        FontMetrics metrics = g.getFontMetrics();
        int x = left + scaled(width, 16, 1080);
        int y = top + Math.max(fontSize + 8, (bottom - top + metrics.getAscent()) / 2 - 2);
        g.drawString(message, x, Math.min(bottom - 6, y));
        g.dispose();
    }

    private static void sanitizeX(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int margin = scaled(width, 64, 1080);
        int cardBottom = height - scaled(height, 260, 1920);

        // Remove the timestamp/view row and all synthetic action counts.
        int left = margin + scaled(width, 40, 1080);
        int right = width - margin - scaled(width, 40, 1080);
        int top = Math.max(0, cardBottom - scaled(height, 238, 1920));
        int bottom = Math.min(height, cardBottom - scaled(height, 38, 1920));

        Graphics2D g = image.createGraphics();
        configure(g);
        g.setColor(Color.BLACK);
        g.fillRect(left, top, Math.max(1, right - left), Math.max(1, bottom - top));

        g.setColor(X_DIVIDER);
        g.setStroke(new BasicStroke(Math.max(1f, width / 540f)));
        g.drawLine(left, top + 4, right, top + 4);
        g.drawLine(left, bottom - 4, right, bottom - 4);

        int fontSize = Math.max(14, scaled(height, 24, 1920));
        g.setFont(new Font("Arial", Font.PLAIN, fontSize));
        g.setColor(new Color(113, 118, 123));
        String message = "Fictional thread  •  engagement hidden";
        FontMetrics metrics = g.getFontMetrics();
        int x = left + scaled(width, 12, 1080);
        int y = top + Math.max(fontSize + 12, (bottom - top + metrics.getAscent()) / 2);
        g.drawString(message, x, Math.min(bottom - 10, y));
        g.dispose();

        // Remove the renderer's synthetic blue verification check without touching
        // the avatar, which sits to the left of this search region.
        removeSyntheticVerifiedBlue(image);
    }

    private static void removeSyntheticVerifiedBlue(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int minX = Math.max(0, scaled(width, 205, 1080));
        int maxX = Math.min(width - 1, scaled(width, 930, 1080));
        int minY = Math.max(0, scaled(height, 215, 1920));
        int maxY = Math.min(height - 1, scaled(height, 390, 1920));

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                Color color = new Color(image.getRGB(x, y));
                if (isVerifiedBlue(color)) {
                    image.setRGB(x, y, Color.BLACK.getRGB());
                }
            }
        }
    }

    private static boolean isVerifiedBlue(Color color) {
        int dr = color.getRed() - 29;
        int dg = color.getGreen() - 155;
        int db = color.getBlue() - 240;
        return (dr * dr) + (dg * dg) + (db * db) <= 42 * 42;
    }

    private static Color sample(BufferedImage image, int x, int y, Color fallback) {
        try {
            return new Color(image.getRGB(x, y));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static int scaled(int actual, int designPixels, int designSize) {
        return Math.max(1, (int) Math.round((double) actual * designPixels / designSize));
    }

    private static void configure(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }
}
