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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Random;

import javax.imageio.ImageIO;

public class RedditScreenshotGenerator {
    private static final int MARGIN = 64;
    private static final int COMMENT_BOX_TOP = 260;
    private static final int COMMENT_BOX_BOTTOM_PADDING = 330;

    private final Random random = new Random();
    private final Settings settings;
    private final Style style;

    private final String profileImageName;
    private final String userName;
    private final String postLocation;
    private final String comment;
    private final String fileName;
    private final int upvotes;
    private final int views;
    private final Path outputDirectory;

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
        drawCommentBox(g2d);
        drawProfilePicture(g2d, profileImageName, MARGIN + 42, COMMENT_BOX_TOP + 48, 72);
        drawLogo(g2d);
        drawHeader(g2d);
        drawComment(g2d);
        drawStats(g2d);
        drawWatermark(g2d);

        g2d.dispose();
        ImageIO.write(image, "png", outputDirectory.resolve(fileName + ".png").toFile());
    }

    private int boxBottom() {
        return settings.height - COMMENT_BOX_BOTTOM_PADDING;
    }

    private int boxHeight() {
        return boxBottom() - COMMENT_BOX_TOP;
    }

    private int boxRight() {
        return settings.width - MARGIN;
    }

    private int boxWidth() {
        return settings.width - (MARGIN * 2);
    }

    private void drawBackground(Graphics2D g2d) {
        g2d.setColor(style.background);
        g2d.fillRect(0, 0, settings.width, settings.height);
    }

    private void drawCommentBox(Graphics2D g2d) {
        g2d.setColor(style.card);
        g2d.fillRoundRect(MARGIN, COMMENT_BOX_TOP, boxWidth(), boxHeight(), 34, 34);
    }

    private void drawHeader(Graphics2D g2d) {
        int textX = MARGIN + 136;
        int avatarY = COMMENT_BOX_TOP + 48;
        g2d.setColor(style.text);
        g2d.setFont(new Font(settings.fontName, Font.BOLD, settings.authorFontSize));
        g2d.drawString(userName, textX, avatarY + 30);
        g2d.setFont(new Font(settings.fontName, Font.PLAIN, settings.locationFontSize));
        g2d.setColor(style.muted);
        g2d.drawString(postLocation, textX, avatarY + 63);
    }

    private void drawLogo(Graphics2D g2d) {
        int badgeWidth = 168;
        int badgeHeight = 72;
        int badgeX = boxRight() - 42 - badgeWidth;
        int badgeY = COMMENT_BOX_TOP + 48;

        g2d.setColor(style.accent);
        g2d.fillRoundRect(badgeX, badgeY, badgeWidth, badgeHeight, 24, 24);

        int iconX = badgeX + 18;
        int iconY = badgeY + 16;
        g2d.setColor(Color.WHITE);
        g2d.fillOval(iconX, iconY, 40, 40);
        g2d.setColor(style.accent);
        g2d.fillOval(iconX + 13, iconY + 15, 5, 5);
        g2d.fillOval(iconX + 24, iconY + 15, 5, 5);
        g2d.drawArc(iconX + 12, iconY + 19, 18, 10, 180, 180);

        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font(settings.fontName, Font.BOLD, 28));
        g2d.drawString("reddit", badgeX + 66, badgeY + 46);
    }

    private void drawComment(Graphics2D g2d) {
        g2d.setColor(style.text);
        Font commentFont = new Font(settings.fontName, Font.PLAIN, settings.commentFontSize);
        g2d.setFont(commentFont);
        FontMetrics metrics = g2d.getFontMetrics(commentFont);

        int textX = MARGIN + 42;
        int maxTextWidth = boxWidth() - 84;
        List<String> wrappedCommentLines = CommentWrapper.wrapComment(comment, metrics, maxTextWidth);

        int lineHeight = settings.commentFontSize + 10;
        int textAreaTop = COMMENT_BOX_TOP + 178;
        int textAreaBottom = boxBottom() - 190;
        int y = textAreaTop;

        if (settings.centerShortComments) {
            int textBlockHeight = wrappedCommentLines.size() * lineHeight;
            y = textAreaTop + Math.max(0, ((textAreaBottom - textAreaTop) - textBlockHeight) / 2);
        }

        for (String line : wrappedCommentLines) {
            if (y > textAreaBottom) {
                g2d.drawString("...", textX, y);
                break;
            }
            g2d.drawString(line, textX, y);
            y += lineHeight;
        }
    }

    private void drawStats(Graphics2D g2d) {
        int arrowX = MARGIN + 42;
        int arrowY = boxBottom() - 112;
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

        int iconX = arrowX + arrowWidth + 48;
        drawViewIcon(g2d, iconX, arrowY + arrowHeight - 10, 30);
        g2d.drawString(numberFormat.format(views) + " views", iconX + 40, arrowY + arrowHeight * 3 / 2);

        drawClockIcon(g2d, iconX, arrowY + arrowHeight * 2 + 10, 30);
        String[] timeUnits = {"days", "weeks", "months", "years"};
        String randomTimeUnit = timeUnits[random.nextInt(timeUnits.length)];
        int randomTimeValue = random.nextInt(7) + 1;
        g2d.drawString(randomTimeValue + " " + randomTimeUnit + " ago", iconX + 40, arrowY + arrowHeight * 5 / 2);
    }

    private void drawWatermark(Graphics2D g2d) {
        if (!settings.showWatermark || settings.watermarkText == null || settings.watermarkText.isBlank()) {
            return;
        }
        g2d.setColor(new Color(style.muted.getRed(), style.muted.getGreen(), style.muted.getBlue(), 80));
        g2d.setFont(new Font(settings.fontName, Font.BOLD, 24));
        g2d.drawString(settings.watermarkText, boxRight() - 150, boxBottom() - 28);
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
        g2d.setFont(new Font(settings.fontName, Font.BOLD, 28));
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
            if (settings.listVoices) {
                VoiceCatalog.printVoices(settings.voiceDirectory);
                return;
            }
            if (settings.guiMode) {
                GuiApp.open();
                return;
            }
            if (settings.autoGenerateText) {
                generateTextWithLocalLlm(settings);
            }
            generateBatch(settings);
        } catch (Exception e) {
            System.err.println("Failed: " + e.getMessage());
            printUsage();
            e.printStackTrace();
        }
    }

    private static void generateTextWithLocalLlm(Settings settings) throws IOException, InterruptedException {
        int requestedCount = settings.count > -1 ? settings.count : settings.autoTextCount;
        LocalLlmTextGenerator generator = new LocalLlmTextGenerator(settings.ollamaUrl, settings.llmModel);
        Path generatedFile = generator.generateToFile(settings.topic, requestedCount, settings.generatedTextFile);
        settings.commentsFile = generatedFile;
        if (settings.count < 0) {
            settings.count = requestedCount;
        }
        System.out.println("Generated script: " + generatedFile);
    }

    private static void generateBatch(Settings settings) throws IOException, InterruptedException {
        TextFileReader comments = TextFileReader.fromFile(settings.commentsFile);
        TextFileReader authors = TextFileReader.fromFile(settings.authorNamesFile);
        RandomProfileName profileName = new RandomProfileName(settings.profileDirectory);
        VoiceGenerator voiceGenerator = new VoiceGenerator(settings.ttsEngine, settings.ttsCommand, settings.voiceModel, settings.ttsTimeoutSeconds);
        VideoGenerator videoGenerator = new VideoGenerator(settings.videoCommand, settings.videoTimeoutSeconds);
        List<Path> videoClips = new ArrayList<>();
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
            String currentComment = commentLines.get(i);
            String currentFileName = i + settings.outputPrefix;

            RedditScreenshotGenerator generator = new RedditScreenshotGenerator(
                    currentFileName,
                    randomAuthor,
                    settings.postLocation,
                    currentComment,
                    randomProfileImage,
                    randomLikes,
                    randomViews,
                    settings.outputDirectory,
                    settings,
                    style
            );
            generator.generateImage();
            Path imagePath = settings.outputDirectory.resolve(currentFileName + ".png");
            System.out.println("Generated image: " + imagePath);

            Path audioPath = null;
            if (voiceGenerator.isEnabled()) {
                audioPath = settings.audioDirectory.resolve(currentFileName + ".wav");
                voiceGenerator.generateSpeech(currentComment, audioPath);
                System.out.println("Generated audio: " + audioPath);
            }

            if (settings.createVideo) {
                if (audioPath == null) {
                    System.out.println("Skipping video for " + currentFileName + ": enable voice first with --tts piper");
                } else {
                    Path videoPath = settings.videoDirectory.resolve(currentFileName + ".mp4");
                    videoGenerator.makeClip(imagePath, audioPath, videoPath, settings.width, settings.height, settings.videoFps);
                    videoClips.add(videoPath);
                    System.out.println("Generated video: " + videoPath);
                }
            }
        }

        if (settings.concatVideo && !videoClips.isEmpty()) {
            Path finalVideo = settings.videoDirectory.resolve(settings.finalVideoName);
            videoGenerator.combineClips(videoClips, finalVideo);
            System.out.println("Generated final video: " + finalVideo);
        }
    }

    private static void printUsage() {
        System.err.println("Usage: java -cp out redditTxtToImg.RedditScreenshotGenerator [comments.txt] [output] [options]");
        System.err.println("Image options: --count N --prefix NAME --style dark|light --shuffle --center --top --no-watermark --gui");
        System.err.println("Local LLM options: --auto --topic TEXT --llm-model MODEL --llm-url URL --script-out FILE");
        System.err.println("Local TTS options: --tts none|piper --voice NAME_OR_PATH --voice-dir DIR --list-voices --tts-command piper --audio-dir DIR");
        System.err.println("Video options: --video --concat-video --video-dir DIR --video-command ffmpeg --fps 30 --final-video final.mp4");
    }

    private static class Settings {
        int width = 1080;
        int height = 1920;
        int commentFontSize = 60;
        int authorFontSize = 30;
        int locationFontSize = 22;
        int maxViews = 50000;
        int maxLikes = 15000;
        int count = -1;
        int autoTextCount = 10;
        int ttsTimeoutSeconds = 120;
        int videoTimeoutSeconds = 180;
        int videoFps = 30;
        boolean shuffle = false;
        boolean centerShortComments = true;
        boolean showWatermark = false;
        boolean guiMode = false;
        boolean autoGenerateText = false;
        boolean listVoices = false;
        boolean createVideo = false;
        boolean concatVideo = false;
        String fontName = "Arial";
        String postLocation = "/thread/comment";
        String outputPrefix = "aithread";
        String styleName = "dark";
        String watermarkText = "";
        String topic = "weird everyday stories";
        String llmModel = "llama3.1:8b";
        String ollamaUrl = "http://localhost:11434/api/generate";
        String ttsEngine = "none";
        String ttsCommand = "piper";
        String videoCommand = "ff" + "mpeg";
        String finalVideoName = "final.mp4";
        Path commentsFile = Path.of("data", "comments.txt");
        Path outputDirectory = Path.of("output");
        Path authorNamesFile = Path.of("data", "author_names.txt");
        Path profileDirectory = Path.of("assets", "pfp");
        Path generatedTextFile = Path.of("output", "script", "generated_comments.txt");
        Path audioDirectory = Path.of("output", "audio");
        Path videoDirectory = Path.of("output", "video");
        Path voiceDirectory = Path.of("voices");
        Path voiceModel = Path.of("voices", "en_US-lessac-medium.onnx");

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
                else if ("--style".equals(arg) && i + 1 < args.length) settings.styleName = args[++i].replace("reddit_", "");
                else if ("--names".equals(arg) && i + 1 < args.length) settings.authorNamesFile = Path.of(args[++i]);
                else if ("--profiles".equals(arg) && i + 1 < args.length) settings.profileDirectory = Path.of(args[++i]);
                else if ("--shuffle".equals(arg)) settings.shuffle = true;
                else if ("--top".equals(arg)) settings.centerShortComments = false;
                else if ("--center".equals(arg)) settings.centerShortComments = true;
                else if ("--no-watermark".equals(arg)) settings.showWatermark = false;
                else if ("--gui".equals(arg)) settings.guiMode = true;
                else if ("--auto".equals(arg)) settings.autoGenerateText = true;
                else if ("--topic".equals(arg) && i + 1 < args.length) settings.topic = args[++i];
                else if ("--llm-model".equals(arg) && i + 1 < args.length) settings.llmModel = args[++i];
                else if ("--llm-url".equals(arg) && i + 1 < args.length) settings.ollamaUrl = args[++i];
                else if ("--script-out".equals(arg) && i + 1 < args.length) settings.generatedTextFile = Path.of(args[++i]);
                else if ("--tts".equals(arg) && i + 1 < args.length) settings.ttsEngine = args[++i];
                else if ("--voice".equals(arg) && i + 1 < args.length) settings.voiceModel = VoiceCatalog.resolveVoice(args[++i], settings.voiceDirectory);
                else if ("--voice-dir".equals(arg) && i + 1 < args.length) settings.voiceDirectory = Path.of(args[++i]);
                else if ("--list-voices".equals(arg)) settings.listVoices = true;
                else if ("--tts-command".equals(arg) && i + 1 < args.length) settings.ttsCommand = args[++i];
                else if ("--audio-dir".equals(arg) && i + 1 < args.length) settings.audioDirectory = Path.of(args[++i]);
                else if ("--tts-timeout".equals(arg) && i + 1 < args.length) settings.ttsTimeoutSeconds = parseInt(args[++i], settings.ttsTimeoutSeconds);
                else if ("--video".equals(arg)) settings.createVideo = true;
                else if ("--concat-video".equals(arg)) { settings.createVideo = true; settings.concatVideo = true; }
                else if ("--video-dir".equals(arg) && i + 1 < args.length) settings.videoDirectory = Path.of(args[++i]);
                else if ("--video-command".equals(arg) && i + 1 < args.length) settings.videoCommand = args[++i];
                else if ("--fps".equals(arg) && i + 1 < args.length) settings.videoFps = parseInt(args[++i], settings.videoFps);
                else if ("--video-timeout".equals(arg) && i + 1 < args.length) settings.videoTimeoutSeconds = parseInt(args[++i], settings.videoTimeoutSeconds);
                else if ("--final-video".equals(arg) && i + 1 < args.length) settings.finalVideoName = args[++i];
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
                settings.centerShortComments = Boolean.parseBoolean(properties.getProperty("centerShortComments", "true"));
                settings.topic = properties.getProperty("topic", settings.topic);
                settings.llmModel = properties.getProperty("llmModel", settings.llmModel);
                settings.ollamaUrl = properties.getProperty("ollamaUrl", settings.ollamaUrl);
                settings.ttsEngine = properties.getProperty("ttsEngine", settings.ttsEngine);
                settings.ttsCommand = properties.getProperty("ttsCommand", settings.ttsCommand);
                settings.voiceDirectory = Path.of(properties.getProperty("voiceDirectory", settings.voiceDirectory.toString()));
                settings.voiceModel = VoiceCatalog.resolveVoice(properties.getProperty("voiceModel", settings.voiceModel.toString()), settings.voiceDirectory);
                settings.audioDirectory = Path.of(properties.getProperty("audioDirectory", settings.audioDirectory.toString()));
                settings.videoDirectory = Path.of(properties.getProperty("videoDirectory", settings.videoDirectory.toString()));
                settings.videoCommand = properties.getProperty("videoCommand", settings.videoCommand);
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

    private static class ShapeClipper {
        static void drawCircularImage(Graphics2D g2d, Image image, int x, int y, int size) {
            java.awt.Shape oldClip = g2d.getClip();
            g2d.setClip(new Ellipse2D.Double(x, y, size, size));
            g2d.drawImage(image, x, y, size, size, null);
            g2d.setClip(oldClip);
        }
    }
}
