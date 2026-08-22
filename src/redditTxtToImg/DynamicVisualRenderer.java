package redditTxtToImg;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Builds meaningfully different presentation frames for the P0 content formats.
 * Motion is added later by DynamicVideoGenerator; this class changes the
 * composition itself so formats are not just different transition settings.
 */
final class DynamicVisualRenderer {
    private static final Color BACKGROUND = new Color(12, 12, 14);
    private static final Color PANEL = new Color(24, 24, 28);
    private static final Color TEXT = new Color(244, 244, 246);
    private static final Color MUTED = new Color(158, 160, 166);

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

        BufferedImage target = new BufferedImage(
                source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        configure(g);

        switch (format) {
            case THREAD_STORY -> renderThread(g, source, format, index, total);
            case CONFESSION -> renderConfession(g, source, narration, format, index, total);
            case DEBATE -> renderDebate(g, source, narration, format, index, total);
            case BEST_ANSWERS -> renderBestAnswers(g, source, narration, format, index, total);
            case ESCALATING_CONVERSATION -> renderConversation(g, source, narration, format, index, total);
        }

        g.dispose();
        ImageIO.write(target, "png", outputPath.toFile());
        return outputPath;
    }

    private static void renderThread(
            Graphics2D g, BufferedImage source, ContentFormat format, int index, int total) {
        g.drawImage(source, 0, 0, source.getWidth(), source.getHeight(), null);
        drawTopFormatBar(g, source.getWidth(), format.label(), index, total, new Color(72, 82, 96, 210));
    }

    private static void renderConfession(
            Graphics2D g,
            BufferedImage source,
            String narration,
            ContentFormat format,
            int index,
            int total
    ) {
        int w = source.getWidth();
        int h = source.getHeight();
        g.setPaint(new GradientPaint(0, 0, new Color(25, 18, 25), w, h, new Color(9, 9, 12)));
        g.fillRect(0, 0, w, h);

        int pad = Math.max(32, w / 18);
        int top = Math.max(130, h / 12);
        int bottomSpace = Math.max(420, h / 4);
        int targetHeight = h - top - bottomSpace;
        drawImageCover(g, source, pad, top, w - (pad * 2), targetHeight, 0.10, 0.08);

        g.setColor(new Color(0, 0, 0, 120));
        g.fillRoundRect(pad, top, w - (pad * 2), targetHeight, 32, 32);
        drawImageCover(g, source, pad + 10, top + 10, w - (pad * 2) - 20, targetHeight - 20, 0.08, 0.08);

        drawTopFormatBar(g, w, format.label(), index, total, new Color(106, 64, 91, 230));
        drawLargeQuoteCard(g, narration, pad, h - bottomSpace + 44, w - (pad * 2), bottomSpace - 88,
                "FIRST-PERSON MOMENT");
    }

    private static void renderDebate(
            Graphics2D g,
            BufferedImage source,
            String narration,
            ContentFormat format,
            int index,
            int total
    ) {
        int w = source.getWidth();
        int h = source.getHeight();
        boolean leftSide = index % 2 == 0;

        g.setColor(BACKGROUND);
        g.fillRect(0, 0, w, h);

        int gutter = Math.max(28, w / 28);
        int panelWidth = (int) Math.round(w * 0.74);
        int panelHeight = (int) Math.round(h * 0.66);
        int panelX = leftSide ? gutter : w - panelWidth - gutter;
        int panelY = Math.max(190, h / 9);

        Color sideColor = leftSide ? new Color(52, 82, 116) : new Color(116, 67, 66);
        g.setColor(new Color(sideColor.getRed(), sideColor.getGreen(), sideColor.getBlue(), 90));
        g.fillRect(leftSide ? 0 : w / 2, 0, w / 2, h);

        g.setColor(PANEL);
        g.fillRoundRect(panelX - 12, panelY - 12, panelWidth + 24, panelHeight + 24, 34, 34);
        drawImageCover(g, source, panelX, panelY, panelWidth, panelHeight, leftSide ? 0.02 : 0.18, 0.04);

        int labelY = panelY + panelHeight + 58;
        drawPill(g, leftSide ? "SIDE A" : "SIDE B", panelX, labelY, sideColor);
        drawWrappedText(
                g,
                shortExcerpt(narration, 240),
                panelX,
                labelY + 76,
                panelWidth,
                Math.max(34, h / 50),
                Font.BOLD,
                TEXT,
                5
        );
        drawTopFormatBar(g, w, format.label(), index, total, sideColor);
    }

    private static void renderBestAnswers(
            Graphics2D g,
            BufferedImage source,
            String narration,
            ContentFormat format,
            int index,
            int total
    ) {
        int w = source.getWidth();
        int h = source.getHeight();

        g.setPaint(new GradientPaint(0, 0, new Color(12, 19, 22), w, h, new Color(7, 9, 11)));
        g.fillRect(0, 0, w, h);

        int pad = Math.max(44, w / 16);
        int panelY = Math.max(220, h / 8);
        int panelHeight = (int) Math.round(h * 0.62);

        g.setColor(new Color(31, 35, 39));
        g.fillRoundRect(pad, panelY, w - (pad * 2), panelHeight, 42, 42);
        g.setColor(new Color(89, 183, 164));
        g.setStroke(new BasicStroke(Math.max(3f, w / 270f)));
        g.drawRoundRect(pad, panelY, w - (pad * 2), panelHeight, 42, 42);

        int rankSize = Math.max(100, w / 7);
        int rankX = pad + 34;
        int rankY = panelY + 34;
        g.setColor(new Color(89, 183, 164));
        g.fillOval(rankX, rankY, rankSize, rankSize);
        g.setColor(Color.BLACK);
        g.setFont(new Font("Arial", Font.BOLD, Math.max(42, rankSize / 2)));
        centerText(g, "#" + (index + 1), rankX, rankY, rankSize, rankSize);

        int imageTop = rankY + rankSize + 46;
        int imageHeight = panelHeight - rankSize - 116;
        drawImageCover(g, source, pad + 28, imageTop, w - (pad * 2) - 56, imageHeight, 0.10, 0.08);

        int captionY = panelY + panelHeight + 52;
        drawWrappedText(
                g,
                shortExcerpt(narration, 210),
                pad,
                captionY,
                w - (pad * 2),
                Math.max(32, h / 54),
                Font.BOLD,
                TEXT,
                4
        );
        drawTopFormatBar(g, w, format.label(), index, total, new Color(49, 112, 100, 230));
    }

    private static void renderConversation(
            Graphics2D g,
            BufferedImage source,
            String narration,
            ContentFormat format,
            int index,
            int total
    ) {
        int w = source.getWidth();
        int h = source.getHeight();
        g.setColor(new Color(11, 12, 16));
        g.fillRect(0, 0, w, h);

        int pad = Math.max(42, w / 17);
        int imageY = Math.max(170, h / 10);
        int imageHeight = (int) Math.round(h * 0.50);
        g.setColor(new Color(28, 30, 36));
        g.fillRoundRect(pad - 8, imageY - 8, w - (pad * 2) + 16, imageHeight + 16, 36, 36);
        drawImageCover(g, source, pad, imageY, w - (pad * 2), imageHeight, 0.08, 0.02);

        int bubbleTop = imageY + imageHeight + 78;
        int bubbleWidth = (int) Math.round(w * 0.78);
        boolean left = index % 2 == 0;
        int bubbleX = left ? pad : w - pad - bubbleWidth;
        int bubbleHeight = Math.min(h - bubbleTop - 110, Math.max(260, h / 5));
        Color bubble = left ? new Color(36, 72, 108) : new Color(60, 61, 68);

        g.setColor(bubble);
        g.fillRoundRect(bubbleX, bubbleTop, bubbleWidth, bubbleHeight, 54, 54);
        drawPill(g, "MESSAGE " + (index + 1), bubbleX + 28, bubbleTop + 28, new Color(255, 255, 255, 44));
        drawWrappedText(
                g,
                shortExcerpt(narration, 300),
                bubbleX + 34,
                bubbleTop + 112,
                bubbleWidth - 68,
                Math.max(34, h / 50),
                Font.PLAIN,
                TEXT,
                6
        );

        drawTopFormatBar(g, w, format.label(), index, total, new Color(50, 78, 112, 230));
    }

    private static void drawLargeQuoteCard(
            Graphics2D g,
            String narration,
            int x,
            int y,
            int width,
            int height,
            String eyebrow
    ) {
        g.setColor(new Color(22, 20, 24));
        g.fillRoundRect(x, y, width, height, 44, 44);
        g.setColor(new Color(108, 79, 101));
        g.setStroke(new BasicStroke(4f));
        g.drawRoundRect(x, y, width, height, 44, 44);

        g.setFont(new Font("Arial", Font.BOLD, Math.max(18, height / 12)));
        g.setColor(MUTED);
        g.drawString(eyebrow, x + 34, y + 52);

        drawWrappedText(
                g,
                "“" + shortExcerpt(narration, 320) + "”",
                x + 34,
                y + 94,
                width - 68,
                Math.max(34, height / 8),
                Font.BOLD,
                TEXT,
                7
        );
    }

    private static void drawTopFormatBar(
            Graphics2D g,
            int width,
            String label,
            int index,
            int total,
            Color accent
    ) {
        int barHeight = Math.max(76, width / 11);
        g.setColor(new Color(0, 0, 0, 172));
        g.fillRect(0, 0, width, barHeight);
        g.setColor(accent);
        g.fillRect(0, barHeight - 8, width, 8);

        int fontSize = Math.max(24, width / 31);
        g.setFont(new Font("Arial", Font.BOLD, fontSize));
        g.setColor(TEXT);
        g.drawString(label, Math.max(28, width / 28), (barHeight + fontSize) / 2 - 4);

        String counter = (index + 1) + " / " + Math.max(1, total);
        g.setFont(new Font("Arial", Font.PLAIN, Math.max(20, fontSize - 6)));
        FontMetrics metrics = g.getFontMetrics();
        g.setColor(MUTED);
        g.drawString(counter, width - Math.max(28, width / 28) - metrics.stringWidth(counter),
                (barHeight + fontSize) / 2 - 5);
    }

    private static void drawPill(Graphics2D g, String text, int x, int y, Color color) {
        int fontSize = 26;
        g.setFont(new Font("Arial", Font.BOLD, fontSize));
        FontMetrics metrics = g.getFontMetrics();
        int width = metrics.stringWidth(text) + 34;
        int height = 44;
        g.setColor(color);
        g.fillRoundRect(x, y, width, height, 30, 30);
        g.setColor(TEXT);
        g.drawString(text, x + 17, y + 31);
    }

    private static void drawWrappedText(
            Graphics2D g,
            String text,
            int x,
            int y,
            int maxWidth,
            int fontSize,
            int style,
            Color color,
            int maxLines
    ) {
        Font font = new Font("Arial", style, fontSize);
        g.setFont(font);
        g.setColor(color);
        FontMetrics metrics = g.getFontMetrics(font);
        List<String> lines = wrap(text == null ? "" : text, metrics, maxWidth);
        int lineHeight = fontSize + Math.max(8, fontSize / 4);
        int currentY = y + metrics.getAscent();
        int count = 0;
        for (String line : lines) {
            if (count >= maxLines) {
                g.drawString("…", x, currentY);
                break;
            }
            g.drawString(line, x, currentY);
            currentY += lineHeight;
            count++;
        }
    }

    private static List<String> wrap(String text, FontMetrics metrics, int maxWidth) {
        if (text == null || text.isBlank()) {
            return List.of("");
        }
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.replace('\n', ' ').split("\\s+")) {
            if (lines.isEmpty()) {
                lines.add(paragraph);
                continue;
            }
            int lastIndex = lines.size() - 1;
            String candidate = lines.get(lastIndex) + " " + paragraph;
            if (metrics.stringWidth(candidate) <= maxWidth) {
                lines.set(lastIndex, candidate);
            } else {
                lines.add(paragraph);
            }
        }
        return lines;
    }

    private static void drawImageCover(
            Graphics2D g,
            BufferedImage source,
            int x,
            int y,
            int width,
            int height,
            double horizontalBias,
            double verticalBias
    ) {
        if (width <= 0 || height <= 0) {
            return;
        }
        double scale = Math.max((double) width / source.getWidth(), (double) height / source.getHeight());
        int scaledWidth = (int) Math.ceil(source.getWidth() * scale);
        int scaledHeight = (int) Math.ceil(source.getHeight() * scale);
        Image scaled = source.getScaledInstance(scaledWidth, scaledHeight, Image.SCALE_SMOOTH);

        int extraX = Math.max(0, scaledWidth - width);
        int extraY = Math.max(0, scaledHeight - height);
        int drawX = x - (int) Math.round(extraX * clamp(horizontalBias));
        int drawY = y - (int) Math.round(extraY * clamp(verticalBias));

        Shape oldClip = g.getClip();
        g.setClip(new RoundRectangle2D.Double(x, y, width, height, 28, 28));
        g.drawImage(scaled, drawX, drawY, null);
        g.setClip(oldClip);
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String shortExcerpt(String text, int maxChars) {
        String collapsed = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (collapsed.length() <= maxChars) {
            return collapsed;
        }
        int end = Math.max(0, maxChars - 1);
        int space = collapsed.lastIndexOf(' ', end);
        if (space > maxChars / 2) {
            end = space;
        }
        return collapsed.substring(0, end).trim() + "…";
    }

    private static void centerText(Graphics2D g, String text, int x, int y, int width, int height) {
        FontMetrics metrics = g.getFontMetrics();
        int textX = x + (width - metrics.stringWidth(text)) / 2;
        int textY = y + ((height - metrics.getHeight()) / 2) + metrics.getAscent();
        g.drawString(text, textX, textY);
    }

    private static void configure(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }
}
