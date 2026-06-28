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
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.List;
import java.util.Random;
import java.util.Scanner;

import javax.imageio.ImageIO;

public class RedditScreenshotGenerator {
    private static final int WIDTH = 1080;
    private static final int HEIGHT = 1920;
    private static final int MARGIN = 30;
    private static final int COMMENT_BOX_TOP = 120;
    private static final int COMMENT_BOX_BOTTOM_PADDING = 300;

    private final Random random = new Random();

    private String profileImageName;
    private String userName;
    private String postLocation;
    private String comment;
    private String fileName;
    private int upvotes;
    private int views;
    private Path outputDirectory;

    public RedditScreenshotGenerator(String fileName, String userName, String postLocation, String comment,
                                     String profileImageName, int upvotes, int views, Path outputDirectory) {
        this.userName = userName;
        this.postLocation = postLocation;
        this.comment = comment;
        this.profileImageName = profileImageName;
        this.upvotes = upvotes;
        this.views = views;
        this.fileName = fileName;
        this.outputDirectory = outputDirectory;
    }

    public void generateImage() throws IOException {
        Files.createDirectories(outputDirectory);

        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
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

        g2d.dispose();

        Path outputPath = outputDirectory.resolve(fileName + ".png");
        ImageIO.write(image, "png", outputPath.toFile());
    }

    private void drawBackground(Graphics2D g2d) {
        g2d.setColor(new Color(26, 26, 27));
        g2d.fillRect(0, 0, WIDTH, HEIGHT);
    }

    private void drawHeader(Graphics2D g2d) {
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("Arial", Font.BOLD, 30));
        g2d.drawString(userName, 100, 60);
        g2d.setFont(new Font("Arial", Font.PLAIN, 22));
        g2d.setColor(new Color(190, 190, 190));
        g2d.drawString(postLocation, 100, 90);
    }

    private void drawLogo(Graphics2D g2d) {
        Path logoPath = Path.of("assets", "ai_comment.png");
        BufferedImage logo = loadImageIfPresent(logoPath);
        int logoWidth = 90;
        int logoHeight = 80;
        int logoX = WIDTH - logoWidth - MARGIN;
        int logoY = MARGIN;

        if (logo != null) {
            g2d.drawImage(logo, logoX, logoY, logoWidth, logoHeight, null);
            return;
        }

        g2d.setColor(new Color(255, 69, 0));
        g2d.fillRoundRect(logoX, logoY, logoWidth, logoHeight, 20, 20);
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("Arial", Font.BOLD, 24));
        g2d.drawString("AI", logoX + 28, logoY + 50);
    }

    private void drawCommentBox(Graphics2D g2d) {
        g2d.setColor(new Color(37, 37, 38));
        g2d.fillRoundRect(MARGIN, COMMENT_BOX_TOP, WIDTH - (MARGIN * 2), HEIGHT - COMMENT_BOX_BOTTOM_PADDING, 20, 20);
    }

    private void drawComment(Graphics2D g2d) {
        g2d.setColor(Color.WHITE);
        Font commentFont = new Font("Arial", Font.PLAIN, 52);
        g2d.setFont(commentFont);
        FontMetrics metrics = g2d.getFontMetrics(commentFont);

        int textX = 50;
        int maxTextWidth = WIDTH - 100;
        List<String> wrappedCommentLines = CommentWrapper.wrapComment(comment, metrics, maxTextWidth);

        int lineHeight = 60;
        int y = 170;
        int maxY = HEIGHT - COMMENT_BOX_BOTTOM_PADDING - 40;

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
        int arrowY = HEIGHT - 150;
        int arrowWidth = 40;
        int arrowHeight = 40;

        g2d.setColor(new Color(135, 206, 250));
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
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("Arial", Font.PLAIN, 22));
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

    private void drawProfilePicture(Graphics2D g2d, String profileImageName, int x, int y, int size) {
        BufferedImage profile = null;
        if (profileImageName != null && !profileImageName.isBlank()) {
            profile = loadImageIfPresent(Path.of("assets", "pfp", profileImageName));
        }

        if (profile != null) {
            ShapeClipper.drawCircularImage(g2d, profile, x, y, size);
            return;
        }

        g2d.setPaint(new GradientPaint(x, y, new Color(255, 69, 0), x + size, y + size, new Color(135, 206, 250)));
        g2d.fill(new Ellipse2D.Double(x, y, size, size));
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("Arial", Font.BOLD, 26));
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

        g2d.setColor(new Color(190, 190, 190));
        g2d.setStroke(new BasicStroke(3));
        g2d.drawOval(x, y + 8, size, size / 2);
        g2d.fillOval(x + size / 2 - 4, y + size / 2 - 4, 8, 8);
    }

    private void drawClockIcon(Graphics2D g2d, int x, int y, int size) {
        g2d.setColor(new Color(190, 190, 190));
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
        System.setProperty("java.awt.headless", "true");

        try {
            Path commentsFile = resolveCommentsFile(args);
            Path outputDirectory = args.length >= 2 ? Path.of(args[1]) : Path.of("output");

            TextFileReader comments = TextFileReader.fromFile(commentsFile);
            TextFileReader authors = TextFileReader.fromFile(Path.of("data", "author_names.txt"));
            RandomProfileName profileName = new RandomProfileName(Path.of("assets", "pfp"));
            Random rand = new Random();

            for (int i = 0; i < comments.getSize(); i++) {
                int randomViews = rand.nextInt(50000);
                int randomLikes = rand.nextInt(15000);
                String randomAuthor = authors.getRandomEntry(rand);
                String randomProfileImage = profileName.getRandomProfileName();

                RedditScreenshotGenerator generator = new RedditScreenshotGenerator(
                        i + "aithread",
                        randomAuthor,
                        "/OverLord/comment",
                        comments.getEntry(i),
                        randomProfileImage,
                        randomLikes,
                        randomViews,
                        outputDirectory
                );
                generator.generateImage();
                System.out.println("Generated: " + outputDirectory.resolve(i + "aithread.png"));
            }
        } catch (IOException e) {
            System.err.println("Failed: " + e.getMessage());
            System.err.println("Usage: java -cp out redditTxtToImg.RedditScreenshotGenerator data/comments.txt output");
            e.printStackTrace();
        }
    }

    private static Path resolveCommentsFile(String[] args) {
        if (args.length >= 1) {
            return Path.of(args[0]);
        }

        Scanner scanner = new Scanner(System.in);
        System.out.print("Enter comments file path: ");
        return Path.of(scanner.nextLine());
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
