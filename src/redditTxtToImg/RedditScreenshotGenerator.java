package redditTxtToImg;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.Scanner;

import javax.imageio.ImageIO;

public class RedditScreenshotGenerator {
    private static final int MARGIN = 30;
    private static final int COMMENT_BOX_TOP = 120;
    private static final int COMMENT_BOX_BOTTOM_PADDING = 300;

    private final Random random = new Random();
    private final Settings settings;
    private final Style style;

    private String profileImageName;
    private String userName;
    private String postLocation;
    private String comment;
    private String fileName;
    private int upvotes;
    private int views;
    private Path outputDirectory;

    public RedditScreenshotGenerator(String fileName, String userName, String postLocation, String comment,
                                     String profileImageName, int upvotes, int views, Path outputDirectory,
                                     Settings settings, Style style) {
        this.userName = userName;
        this.postLocation = postLocation;
        this.comment = comment;
        this.profileImageName = profileImageName;
        this.upvotes = upvotes;
        this.views = views;
        this.fileName = fileName;
        this.outputDirectory = outputDirectory;
        this.settings = settings;
        this.style = style;
    }

    public void generateImage() throws IOException {
        Files.createDirectories(outputDirectory);

        BufferedImage image = new BufferedImage(settings.width, settings.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        drawBackground(g2d);
        drawProfilePicture(g2d, profileImageName, MARGIN, MARGIN, 60);
        drawLogo(g2d);
        drawHeader(g2d);
        drawCommentBox(g2d);
        drawComment(g2d);
        drawStats(g2d);
        drawWatermark(g2d);

        g2d.dispose();

        Path outputPath = outputDirectory.resolve(fileName + ".png");
        ImageIO.write(image, "png", outputPath.toFile());
    }

    private void drawBackground(Graphics2D g2d) {
        g2d.setColor(style.background);
        g2d.fillRect(0, 0, settings.width, settings.height);
    }

    private void drawHeader(Graphics2D g2d) {
        g2d.setColor(style.text);
        g2d.setFont(new Font(settings.fontName, Font.BOLD, settings.authorFontSize));
        g2d.drawString(userName, 100, 60);
        g2d.setFont(new Font(settings.fontName, Font.PLAIN, settings.locationFontSize));
        g2d.setColor(style.muted);
        g2d.drawString(postLocation, 100, 90);
    }

    private void drawLogo(Graphics2D g2d) {
        BufferedImage logo = loadImageIfPresent(Path.of("assets", "ai_comment.png"));
        int logoWidth = 90;
        int logoHeight = 80;
        int logoX = settings.width - logoWidth - MARGIN;
        int logoY = MARGIN;

        if (logo != null) {
            g2d.drawImage(logo, logoX, logoY, logoWidth, logoHeight, null);
            return;
        }

        g2d.setColor(style.accent);
        g2d.fillRoundRect(logoX, logoY, logoWidth, logoHeight, 20, 20);
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font(settings.fontName, Font.BOLD, 24));
        g2d.drawString("AI", logoX + 28, logoY + 50);
    }

    private void drawCommentBox(Graphics2D g2d) {
        g2d.setColor(style.card);
        g2d.fillRoundRect(MARGIN, COMMENT_BOX_TOP, settings.width - (MARGIN * 2), settings.height - COMMENT_BOX_BOTTOM_PADDING, 20, 20);
    }

    private void drawComment(Graphics2D g2d) {
        g2d.setColor(style.text);
        Font commentFont = new Font(settings.fontName, Font.PLAIN, settings.commentFontSize);
        g2d.setFont(commentFont);
        FontMetrics metrics = g2d.getFontMetrics(commentFont);

        int textX = 50;
        int maxTextWidth = settings.width - 100;
        List<String> wrappedCommentLines = CommentWrapper.wrapComment(comment, metrics, maxTextWidth);

        int lineHeight = settings.commentFontSize + 8;
        int y = 170;
        int maxY = settings.height - COMMENT_BOX_BOTTOM_PADDING - 40;

        if (settings.centerShortComments && wrappedCommentLines.size() <= 3) {
            int cardHeight = settings.height - COMMENT_BOX_BOTTOM_PADDING - COMMENT_BOX_TOP;
            y = COMMENT_BOX_TOP + (cardHeight / 2) - ((wrappedCommentLines.size() * lineHeight) / 2);
        }

        for (String line : wrappedCommentLines) {
            if (y > maxY) {
                g2d.drawString("...", textX, y);
                break;
            }
            g2d.drawString(line, textX, y);
            y += lineHeight;
        }
    }

    private void drawStats(Graphics2D g2d) {
        int arrowX = 50;
        int arrowY = settings.height - 150;
        int arrowWidth = 40;
        int arrowHeight = 40;

        g2d.setColor(style.secondary);
        g2d.fillPolygon(
                new int[]{arrowX, arrowX + arrowWidth / 2, arrowX + arrowWidth},
                new int[]{arrowY + arrowHeight, arrowY, arrowY + arrowHeight},
                3
        );

        g2d.setColor(Color.GRAY);
        g2d.fillPolygon(
                new int[]{arrowX, arrowX + arrowWidth / 2, arrowX + arrowWidth},
                new int[]{arrowY + arrowHeight * 2, arrowY + arrowHeight * 3, arrowY + arrowHeight * 2},
                3
        );

        NumberFormat numberFormat = NumberFormat.getNumberInstance();
        g2d.setColor(style.text);
        g2d.setFont(new Font(settings.fontName, Font.PLAIN, 22));
        g2d.drawString(numberFormat.format(upvotes), arrowX + arrowWidth / 4 - 15, arrowY + arrowHeight * 3 / 2 + 8);

        int iconX = arrowX + arrowWidth + 40;
        drawViewIcon(g2d, iconX, arrowY + arrowHeight - 10, 30);
        g2d.drawString(numberFormat.format(views) + " views", arrowX + arrowWidth + 80, arrowY + arrowHeight * 3 / 2);

        drawClockIcon(g2d, iconX, arrowY + arrowHeight * 2 + 10, 30);
        String[] timeUnits = {"days", "weeks", "months", "years"};
        String randomTimeUnit = timeUnits[random.nextInt(timeUnits.length)];
        int randomTimeValue = random.nextInt(7) + 1;
        g2d.drawString(randomTimeValue + " " + randomTimeUnit + " ago", arrowX + arrowWidth + 80, arrowY + arrowHeight * 5 / 2);
    }

    private void drawWatermark(Graphics2D g2d) {
        if (!settings.showWatermark) {
            return;
        }
        g2d.setColor(new Color(style.muted.getRed(), style.muted.getGreen(), style.muted.getBlue(), 80));
        g2d.setFont(new Font(settings.fontName, Font.BOLD, 24));
        g2d.drawString(settings.watermarkText, settings.width - 150, settings.height - 40);
    }

    private void drawProfilePicture(Graphics2D g2d, String profileImageName, int x, int y, int size) {
        BufferedImage profile = null;
        if (profileImageName != null && !profileImageName.isBlank()) {
            profile = loadImageIfPresent(Path.of("assets", "pfp", profileImageName));
        }

        if (profile != null) {
            ShapeClipper.drawCircularImage(g2d, profile, x, y, size);
            return;
        }

        g2d.setPaint(new GradientPaint(x, y, style.accent, x + size, y + size, style.secondary));
        g2d.fill(new Ellipse2D.Double(x, y, size, size));
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font(settings.fontName, Font.BOLD, 26));
        String initial = userName == null || userName.isBlank() ? "?" : userName.substring(0, 1).toUpperCase();
        FontMetrics metrics = g2d.getFontMetrics();
        int textX = x + (size - metrics.stringWidth(initial)) / 2;
        int textY = y + ((size - metrics.getHeight()) / 2) + metrics.getAscent();
        g2d.drawString(initial, textX, textY);
    }

    private void drawViewIcon(Graphics2D g2d, int x, int y, int size) {
        BufferedImage icon = loadImageIfPresent(Path.of("assets", "viewed_icon.png"));
        if (icon != null) {
            g2d.drawImage(icon, x, y, size, size, null);
            return;
        }

        g2d.setColor(style.muted);
        g2d.setStroke(new BasicStroke(3));
        g2d.drawOval(x, y + 8, size, size / 2);
        g2d.fillOval(x + size / 2 - 4, y + size / 2 - 4, 8, 8);
    }

    private void drawClockIcon(Graphics2D g2d, int x, int y, int size) {
        g2d.setColor(style.muted);
        g2d.setStroke(new BasicStroke(3));
        g2d.drawOval(x, y, size, size);
        g2d.drawLine(x + size / 2, y + size / 2, x + size / 2, y + 7);
        g2d.drawLine(x + size / 2, y + size / 2, x + size - 8, y + size / 2);
    }

    private BufferedImage loadImageIfPresent(Path path) {
        if (!Files.exists(path)) {
            return null;
        }

        try {
            return ImageIO.read(path.toFile());
        } catch (IOException e) {
            System.err.println("Could not load image: " + path + " (using fallback drawing)");
            return null;
        }
    }

    public static void main(String[] args) {
        Settings settings = Settings.fromArgs(args);
        if (!settings.guiMode) {
            System.setProperty("java.awt.headless", "true");
        }

        try {
            if (settings.guiMode) {
                ThreadGensWindow.open();
                return;
            }
            generateBatch(settings);
        } catch (IOException e) {
            System.err.println("Failed: " + e.getMessage());
            printUsage();
            e.printStackTrace();
        }
    }

    private static void generateBatch(Settings settings) throws IOException {
        TextFileReader comments = TextFileReader.fromFile(settings.commentsFile);
        TextFileReader authors = TextFileReader.fromFile(settings.authorNamesFile);
        RandomProfileName profileName = new RandomProfileName(settings.profileDirectory);
        Random rand = new Random();
        Style style = Style.load(settings.styleName);

        List<String> commentLines = comments.getLines();
        if (settings.shuffle) {
            Collections.shuffle(commentLines, rand);
        }

        int total = commentLines.size();
        if (settings.count > -1) {
            total = Math.min(total, settings.count);
        }

        for (int i = 0; i < total; i++) {
            int randomViews = rand.nextInt(settings.maxViews);
            int randomLikes = rand.nextInt(settings.maxLikes);
            String randomAuthor = authors.getRandomEntry(rand);
            String randomProfileImage = profileName.getRandomProfileName();

            RedditScreenshotGenerator generator = new RedditScreenshotGenerator(
                    i + settings.outputPrefix,
                    randomAuthor,
                    settings.postLocation,
                    commentLines.get(i),
                    randomProfileImage,
                    randomLikes,
                    randomViews,
                    settings.outputDirectory,
                    settings,
                    style
            );
            generator.generateImage();
            System.out.println("Generated: " + settings.outputDirectory.resolve(i + settings.outputPrefix + ".png"));
        }
    }

    private static void printUsage() {
        System.err.println("Usage: java -cp out redditTxtToImg.RedditScreenshotGenerator data/comments.txt output [options]");
        System.err.println("Options: --count N --prefix NAME --style dark|light --shuffle --center --no-watermark --gui");
    }

    private static class Settings {
        int width = 1080;
        int height = 1920;
        int commentFontSize = 52;
        int authorFontSize = 30;
        int locationFontSize = 22;
        int maxViews = 50000;
        int maxLikes = 15000;
        int count = -1;
        boolean shuffle = false;
        boolean centerShortComments = false;
        boolean showWatermark = true;
        boolean guiMode = false;
        String fontName = "Arial";
        String postLocation = "/thread/comment";
        String outputPrefix = "aithread";
        String styleName = "dark";
        String watermarkText = "MOCKUP";
        Path commentsFile = Path.of("data", "comments.txt");
        Path outputDirectory = Path.of("output");
        Path authorNamesFile = Path.of("data", "author_names.txt");
        Path profileDirectory = Path.of("assets", "pfp");

        static Settings fromArgs(String[] args) {
            Settings settings = loadDefaults();
            if (args.length >= 1 && !args[0].startsWith("--")) {
                settings.commentsFile = Path.of(args[0]);
            }
            if (args.length >= 2 && !args[1].startsWith("--")) {
                settings.outputDirectory = Path.of(args[1]);
            }

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--count".equals(arg) && i + 1 < args.length) settings.count = parseInt(args[++i], settings.count);
                else if ("--prefix".equals(arg) && i + 1 < args.length) settings.outputPrefix = args[++i];
                else if ("--style".equals(arg) && i + 1 < args.length) settings.styleName = args[++i];
                else if ("--names".equals(arg) && i + 1 < args.length) settings.authorNamesFile = Path.of(args[++i]);
                else if ("--profiles".equals(arg) && i + 1 < args.length) settings.profileDirectory = Path.of(args[++i]);
                else if ("--shuffle".equals(arg)) settings.shuffle = true;
                else if ("--center".equals(arg)) settings.centerShortComments = true;
                else if ("--no-watermark".equals(arg)) settings.showWatermark = false;
                else if ("--gui".equals(arg)) settings.guiMode = true;
            }
            return settings;
        }

        private static Settings loadDefaults() {
            Settings settings = new Settings();
            Path defaults = Path.of("defaults.txt");
            if (!Files.exists(defaults)) return settings;

            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(defaults)) {
                properties.load(input);
                settings.width = parseInt(properties.getProperty("width"), settings.width);
                settings.height = parseInt(properties.getProperty("height"), settings.height);
                settings.outputPrefix = properties.getProperty("prefix", settings.outputPrefix);
                settings.styleName = properties.getProperty("style", settings.styleName).replace("reddit_", "");
            } catch (IOException ignored) {
                return settings;
            }
            return settings;
        }

        private static int parseInt(String value, int fallback) {
            if (value == null) return fallback;
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }

    private static class Style {
        Color background = new Color(26, 26, 27);
        Color card = new Color(37, 37, 38);
        Color text = Color.WHITE;
        Color muted = new Color(190, 190, 190);
        Color accent = new Color(255, 69, 0);
        Color secondary = new Color(135, 206, 250);

        static Style load(String name) {
            Style style = new Style();
            Path path = Path.of("templates", name + ".txt");
            if (!Files.exists(path)) return style;

            try {
                List<String> lines = Files.readAllLines(path);
                for (String line : lines) {
                    String[] pair = line.split("=", 2);
                    if (pair.length != 2) continue;
                    Color color = parseColor(pair[1]);
                    if (color == null) continue;
                    if ("background".equals(pair[0])) style.background = color;
                    else if ("card".equals(pair[0])) style.card = color;
                    else if ("text".equals(pair[0])) style.text = color;
                    else if ("muted".equals(pair[0])) style.muted = color;
                    else if ("accent".equals(pair[0])) style.accent = color;
                    else if ("secondary".equals(pair[0])) style.secondary = color;
                }
            } catch (IOException ignored) {
                return style;
            }
            return style;
        }

        private static Color parseColor(String value) {
            String[] parts = value.trim().split("\\s+");
            if (parts.length != 3) return null;
            try {
                return new Color(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private static class ThreadGensWindow {
        static void open() {
            javax.swing.SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    javax.swing.JFrame frame = new javax.swing.JFrame("ThreadGens");
                    frame.setDefaultCloseOperation(javax.swing.JFrame.EXIT_ON_CLOSE);
                    frame.setSize(460, 220);
                    javax.swing.JPanel panel = new javax.swing.JPanel();
                    panel.setLayout(new java.awt.GridLayout(0, 1, 8, 8));
                    javax.swing.JLabel label = new javax.swing.JLabel("ThreadGens GUI placeholder");
                    javax.swing.JLabel help = new javax.swing.JLabel("CLI is ready: choose full GUI controls next.");
                    panel.add(label);
                    panel.add(help);
                    frame.add(panel);
                    frame.setLocationRelativeTo(null);
                    frame.setVisible(true);
                }
            });
        }
    }

    private static class ShapeClipper {
        static void drawCircularImage(Graphics2D g2d, Image image, int x, int y, int size) {
            java.awt.Shape oldClip = g2d.getClip();
            g2d.setClip(new Ellipse2D.Double(x, y, size, size));
            g2d.drawImage(image, x, y, size, size, null);
            g2d.setClip(oldClip);
        }
    }
}
