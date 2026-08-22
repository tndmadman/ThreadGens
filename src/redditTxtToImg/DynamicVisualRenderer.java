package redditTxtToImg;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import javax.imageio.ImageIO;

/**
 * Shared Reddit/X presentation layer.
 *
 * Keep the rendered social image spatially fixed and let TimedVisualStateRenderer
 * reveal the actual raster text. This layer deliberately does not add format
 * headers, progress counters, ranks, message numbers, duplicate narration cards,
 * or other synthetic text. It only gives each video a stable background palette.
 */
final class DynamicVisualRenderer {
    private DynamicVisualRenderer() {
    }

    static Path render(
            Path sourceImage,
            String narration,
            ContentFormat format,
            int index,
            int total,
            Path outputPath
    ) throws IOException {
        if (sourceImage == null || !Files.exists(sourceImage)) {
            throw new IOException("Missing source image for dynamic composition: " + sourceImage);
        }
        BufferedImage source = ImageIO.read(sourceImage.toFile());
        if (source == null) {
            throw new IOException("Could not decode source image: " + sourceImage);
        }
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }

        String configuredPalette = System.getProperty("threadgens.palette");
        if (configuredPalette == null || configuredPalette.isBlank()) {
            configuredPalette = System.getenv("THREADGENS_PALETTE");
        }
        Palette palette = Palette.resolve(configuredPalette, format);
        BufferedImage target = tintNeutralDarkUi(source, palette);
        ImageIO.write(target, "png", outputPath.toFile());
        return outputPath;
    }

    /**
     * Recolor only neutral dark UI pixels. Bright text, avatars, logos and
     * colored content stay essentially untouched, while the large black/gray
     * social background picks up a subtle per-video hue.
     */
    private static BufferedImage tintNeutralDarkUi(BufferedImage source, Palette palette) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        configure(g);
        g.drawImage(source, 0, 0, null);
        g.dispose();

        Color tint = palette.tint();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = target.getRGB(x, y);
                int r = (rgb >>> 16) & 0xff;
                int green = (rgb >>> 8) & 0xff;
                int b = rgb & 0xff;
                int max = Math.max(r, Math.max(green, b));
                int min = Math.min(r, Math.min(green, b));
                int luminance = (r * 54 + green * 183 + b * 19) >> 8;

                if (luminance >= 92 || (max - min) > 22) {
                    continue;
                }

                double darkness = 1.0 - (luminance / 92.0);
                double amount = 0.16 + (0.34 * darkness);
                int nr = blend(r, tint.getRed(), amount);
                int ng = blend(green, tint.getGreen(), amount);
                int nb = blend(b, tint.getBlue(), amount);
                target.setRGB(x, y, (nr << 16) | (ng << 8) | nb);
            }
        }
        return target;
    }

    private static int blend(int base, int tint, double amount) {
        return Math.max(0, Math.min(255,
                (int) Math.round((base * (1.0 - amount)) + (tint * amount))));
    }

    private static void configure(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private record Palette(Color tint) {
        static Palette resolve(String requested, ContentFormat format) {
            String name = requested == null ? "" : requested.trim().toLowerCase(Locale.ROOT);
            return switch (name) {
                case "ember" -> new Palette(new Color(88, 34, 24));
                case "ocean" -> new Palette(new Color(24, 53, 91));
                case "forest" -> new Palette(new Color(27, 70, 46));
                case "violet" -> new Palette(new Color(62, 38, 91));
                case "teal" -> new Palette(new Color(20, 76, 78));
                case "rose" -> new Palette(new Color(88, 38, 60));
                case "amber" -> new Palette(new Color(94, 65, 24));
                case "slate" -> new Palette(new Color(47, 58, 76));
                default -> fallback(format);
            };
        }

        private static Palette fallback(ContentFormat format) {
            return switch (format) {
                case THREAD_STORY -> new Palette(new Color(47, 58, 76));
                case CONFESSION -> new Palette(new Color(62, 38, 70));
                case DEBATE -> new Palette(new Color(43, 58, 82));
                case BEST_ANSWERS -> new Palette(new Color(27, 70, 61));
                case ESCALATING_CONVERSATION -> new Palette(new Color(37, 57, 86));
            };
        }
    }
}
