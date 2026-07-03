package redditTxtToImg;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

final class OpImageCompositor {
    private OpImageCompositor() {
    }

    static void overlay(String platform, Path screenshotPath, Path opImagePath) throws IOException {
        if (screenshotPath == null || !Files.exists(screenshotPath)) {
            throw new IOException("OP screenshot was not found for image overlay: " + screenshotPath);
        }
        if (opImagePath == null || !Files.exists(opImagePath)) {
            throw new IOException("OP image was not found for overlay: " + opImagePath);
        }

        BufferedImage screenshot = ImageIO.read(screenshotPath.toFile());
        BufferedImage opImage = ImageIO.read(opImagePath.toFile());
        if (screenshot == null) {
            throw new IOException("Could not read screenshot for image overlay: " + screenshotPath);
        }
        if (opImage == null) {
            throw new IOException("Could not read OP image for overlay: " + opImagePath);
        }

        Rect rect = placement(platform, screenshot.getWidth(), screenshot.getHeight());
        Graphics2D g = screenshot.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setColor(new Color(0, 0, 0, 180));
        g.fillRoundRect(rect.x - 4, rect.y - 4, rect.width + 8, rect.height + 8, 30, 30);

        Shape oldClip = g.getClip();
        g.setClip(new RoundRectangle2D.Double(rect.x, rect.y, rect.width, rect.height, 26, 26));
        drawCover(g, opImage, rect);
        g.setClip(oldClip);

        g.setColor(new Color(255, 255, 255, 70));
        g.setStroke(new BasicStroke(2));
        g.drawRoundRect(rect.x, rect.y, rect.width, rect.height, 26, 26);
        g.dispose();

        ImageIO.write(screenshot, "png", screenshotPath.toFile());
    }

    private static Rect placement(String platform, int width, int height) {
        String cleaned = platform == null ? "reddit" : platform.toLowerCase();
        if ("x".equals(cleaned) || "twitter".equals(cleaned)) {
            return new Rect(
                    Math.round(width * 0.102f),
                    Math.round(height * 0.510f),
                    Math.round(width * 0.796f),
                    Math.round(height * 0.250f)
            );
        }
        return new Rect(
                Math.round(width * 0.098f),
                Math.round(height * 0.580f),
                Math.round(width * 0.804f),
                Math.round(height * 0.230f)
        );
    }

    private static void drawCover(Graphics2D g, BufferedImage source, Rect target) {
        double sourceRatio = source.getWidth() / (double) source.getHeight();
        double targetRatio = target.width / (double) target.height;

        int srcX = 0;
        int srcY = 0;
        int srcW = source.getWidth();
        int srcH = source.getHeight();

        if (sourceRatio > targetRatio) {
            srcW = (int) Math.round(source.getHeight() * targetRatio);
            srcX = (source.getWidth() - srcW) / 2;
        } else if (sourceRatio < targetRatio) {
            srcH = (int) Math.round(source.getWidth() / targetRatio);
            srcY = (source.getHeight() - srcH) / 2;
        }

        g.drawImage(source,
                target.x, target.y, target.x + target.width, target.y + target.height,
                srcX, srcY, srcX + srcW, srcY + srcH,
                null);
    }

    private static class Rect {
        final int x;
        final int y;
        final int width;
        final int height;

        Rect(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }
}
