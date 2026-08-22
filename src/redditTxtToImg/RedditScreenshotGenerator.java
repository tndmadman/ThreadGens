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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Random;

import javax.imageio.ImageIO;

/**
 * Reddit-style renderer.
 *
 * Generated/fictional output deliberately contains no fabricated upvote or view
 * counts. The legacy constructor keeps those integer parameters for source
 * compatibility, but they are intentionally ignored.
 */
public class RedditScreenshotGenerator {
    private static final int MARGIN = 64;
    private static final int COMMENT_BOX_TOP = 260;
    private static final int COMMENT_BOX_BOTTOM_PADDING = 330;

    private final Settings settings;
    private final Style style;
    private final String profileImageName;
    private final String userName;
    private final String postLocation;
    private final String comment;
    private final String fileName;
    private final Path outputDirectory;
    private final int itemIndex;
    private final int totalItems;

    public RedditScreenshotGenerator(String fileName, String userName, String postLocation, String comment,
                                     String profileImageName, int ignoredUpvotes, int ignoredViews,
                                     Path outputDirectory, Settings settings, Style style,
                                     int itemIndex, int totalItems) {
        this.userName = userName;
        this.postLocation = postLocation;
        this.comment = comment;
        this.profileImageName = profileImageName;
        this.fileName = fileName;
        this.outputDirectory = outputDirectory;
        this.settings = settings;
        this.style = style;
        this.itemIndex = itemIndex;
        this.totalItems = Math.max(1, totalItems);
    }

    public void generateImage() throws IOException {
        Files.createDirectories(outputDirectory);
        BufferedImage image = new BufferedImage(settings.width, settings.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        configure(g);
        drawBackground(g);
        drawCommentBox(g);
        drawProfilePicture(g, profileImageName, contentLeft(), COMMENT_BOX_TOP + 48, isOriginalPost() ? 78 : 66);
        drawLogo(g);
        drawHeader(g);
        drawComment(g);
        drawNeutralFooter(g);
        drawWatermark(g);
        g.dispose();
        ImageIO.write(image, "png", outputDirectory.resolve(fileName + ".png").toFile());
    }

    private boolean isOriginalPost() {
        return itemIndex == 0;
    }

    private int boxLeft() {
        return isOriginalPost() ? MARGIN : MARGIN + 72;
    }

    private int contentLeft() {
        return boxLeft() + 42;
    }

    private int boxBottom() {
        return settings.height - COMMENT_BOX_BOTTOM_PADDING;
    }

    private int boxRight() {
        return settings.width - MARGIN;
    }

    private int boxWidth() {
        return boxRight() - boxLeft();
    }

    private void drawBackground(Graphics2D g) {
        g.setColor(style.background);
        g.fillRect(0, 0, settings.width, settings.height);
    }

    private void drawCommentBox(Graphics2D g) {
        if (!isOriginalPost()) {
            int lineX = MARGIN + 34;
            g.setColor(new Color(style.muted.getRed(), style.muted.getGreen(), style.muted.getBlue(), 95));
            g.setStroke(new BasicStroke(5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(lineX, COMMENT_BOX_TOP + 30, lineX, boxBottom() - 30);
            g.drawLine(lineX, COMMENT_BOX_TOP + 80, boxLeft() - 18, COMMENT_BOX_TOP + 80);
        }
        g.setColor(style.card);
        g.fillRoundRect(boxLeft(), COMMENT_BOX_TOP, boxWidth(), boxBottom() - COMMENT_BOX_TOP, 34, 34);
        if (isOriginalPost()) {
            g.setColor(new Color(style.accent.getRed(), style.accent.getGreen(), style.accent.getBlue(), 180));
            g.fillRoundRect(boxLeft(), COMMENT_BOX_TOP, boxWidth(), 10, 10, 10);
        }
    }

    private void drawHeader(Graphics2D g) {
        int textX = contentLeft() + (isOriginalPost() ? 98 : 86);
        int avatarY = COMMENT_BOX_TOP + 48;
        g.setColor(style.text);
        g.setFont(new Font(settings.fontName, Font.BOLD, settings.authorFontSize));
        g.drawString(userName, textX, avatarY + 28);
        g.setFont(new Font(settings.fontName, Font.PLAIN, settings.locationFontSize));
        g.setColor(style.muted);
        g.drawString(headerSubline(), textX, avatarY + 62);
        drawTypePill(g, textX, avatarY + 82);
    }

    private void drawTypePill(Graphics2D g, int x, int y) {
        String label = isOriginalPost() ? "ORIGINAL POST" : "REPLY " + itemIndex;
        Font font = new Font(settings.fontName, Font.BOLD, 16);
        g.setFont(font);
        FontMetrics metrics = g.getFontMetrics(font);
        int width = metrics.stringWidth(label) + 24;
        g.setColor(isOriginalPost() ? style.accent : new Color(75, 75, 78));
        g.fillRoundRect(x, y, width, 28, 18, 18);
        g.setColor(Color.WHITE);
        g.drawString(label, x + 12, y + 20);
    }

    private String headerSubline() {
        if (isOriginalPost()) {
            return communityName() + " • posted " + ageText();
        }
        return "replying to OP • " + ageText();
    }

    private String communityName() {
        if (postLocation == null || postLocation.isBlank() || "/thread/comment".equals(postLocation)) {
            return "r/AskReddit";
        }
        String cleaned = postLocation.trim();
        return cleaned.startsWith("r/") ? cleaned : cleaned.replace("/thread/comment", "r/thread");
    }

    private String ageText() {
        int minutesAgo = isOriginalPost()
                ? Math.max(90, totalItems * 18 + 90)
                : Math.max(6, (totalItems - itemIndex + 1) * 14);
        if (minutesAgo >= 60) {
            return Math.max(1, Math.round(minutesAgo / 60.0f)) + "h ago";
        }
        return minutesAgo + "m ago";
    }

    private void drawLogo(Graphics2D g) {
        int badgeWidth = 168;
        int badgeHeight = 72;
        int badgeX = boxRight() - 42 - badgeWidth;
        int badgeY = COMMENT_BOX_TOP + 48;
        g.setColor(style.accent);
        g.fillRoundRect(badgeX, badgeY, badgeWidth, badgeHeight, 24, 24);
        int iconX = badgeX + 18;
        int iconY = badgeY + 16;
        g.setColor(Color.WHITE);
        g.fillOval(iconX, iconY, 40, 40);
        g.setColor(style.accent);
        g.fillOval(iconX + 13, iconY + 15, 5, 5);
        g.fillOval(iconX + 24, iconY + 15, 5, 5);
        g.drawArc(iconX + 12, iconY + 19, 18, 10, 180, 180);
        g.setColor(Color.WHITE);
        g.setFont(new Font(settings.fontName, Font.BOLD, 28));
        g.drawString("reddit", badgeX + 66, badgeY + 46);
    }

    private void drawComment(Graphics2D g) {
        if (isOriginalPost() && settings.postTitle != null && !settings.postTitle.isBlank()) {
            drawOriginalPostTitleAndBody(g);
            return;
        }
        int fontSize = isOriginalPost() ? Math.max(54, settings.commentFontSize - 2) : Math.max(48, settings.commentFontSize - 6);
        drawPlainComment(g, fontSize);
    }

    private void drawOriginalPostTitleAndBody(Graphics2D g) {
        int textX = contentLeft();
        int maxTextWidth = boxWidth() - 84;
        int panelY = COMMENT_BOX_TOP + 178;
        int paddingX = 30;
        int paddingY = 24;
        int bodyBottom = boxBottom() - 150;
        int titleFontSize = 48;
        int bodyFontSize = Math.max(48, settings.commentFontSize - 8);
        Font titleFont = new Font(settings.fontName, Font.BOLD, titleFontSize);
        Font bodyFont = new Font(settings.fontName, Font.PLAIN, bodyFontSize);
        FontMetrics titleMetrics = g.getFontMetrics(titleFont);
        FontMetrics bodyMetrics = g.getFontMetrics(bodyFont);

        List<String> titleLines = CommentWrapper.wrapComment(settings.postTitle.trim(), titleMetrics,
                maxTextWidth - (paddingX * 2) - 14);
        if (titleLines.size() > 2) {
            titleLines = new ArrayList<>(titleLines.subList(0, 2));
            titleLines.set(1, titleLines.get(1) + "...");
        }
        int titleLineHeight = titleFontSize + 8;
        int panelHeight = Math.max(108, titleLines.size() * titleLineHeight + (paddingY * 2));
        g.setColor(new Color(30, 30, 31));
        g.fillRoundRect(textX, panelY, maxTextWidth, panelHeight, 26, 26);
        g.setColor(style.accent);
        g.fillRoundRect(textX, panelY, 9, panelHeight, 9, 9);
        g.setColor(new Color(style.muted.getRed(), style.muted.getGreen(), style.muted.getBlue(), 80));
        g.setStroke(new BasicStroke(2));
        g.drawRoundRect(textX, panelY, maxTextWidth, panelHeight, 26, 26);
        g.setFont(titleFont);
        g.setColor(style.text);
        int titleY = panelY + paddingY + titleMetrics.getAscent();
        for (String line : titleLines) {
            g.drawString(line, textX + paddingX, titleY);
            titleY += titleLineHeight;
        }

        List<String> bodyLines = CommentWrapper.wrapComment(comment, bodyMetrics, maxTextWidth);
        g.setFont(bodyFont);
        g.setColor(style.text);
        int bodyTop = panelY + panelHeight + 66;
        int lineHeight = bodyFontSize + 10;
        int y = bodyTop;
        if (settings.centerShortComments) {
            y += Math.max(0, ((bodyBottom - bodyTop) - bodyLines.size() * lineHeight) / 2);
        }
        for (String line : bodyLines) {
            if (y > bodyBottom) {
                g.drawString("...", textX, y);
                break;
            }
            g.drawString(line, textX, y);
            y += lineHeight;
        }
    }

    private void drawPlainComment(Graphics2D g, int fontSize) {
        Font font = new Font(settings.fontName, Font.PLAIN, fontSize);
        g.setFont(font);
        g.setColor(style.text);
        FontMetrics metrics = g.getFontMetrics(font);
        int textX = contentLeft();
        int maxWidth = boxWidth() - 84;
        List<String> lines = CommentWrapper.wrapComment(comment, metrics, maxWidth);
        int lineHeight = fontSize + 10;
        int top = COMMENT_BOX_TOP + 230;
        int bottom = boxBottom() - 150;
        int y = top;
        if (settings.centerShortComments) {
            y += Math.max(0, ((bottom - top) - lines.size() * lineHeight) / 2);
        }
        for (String line : lines) {
            if (y > bottom) {
                g.drawString("...", textX, y);
                break;
            }
            g.drawString(line, textX, y);
            y += lineHeight;
        }
    }

    private void drawNeutralFooter(Graphics2D g) {
        int y = boxBottom() - 54;
        int x = contentLeft();
        g.setColor(style.muted);
        g.setStroke(new BasicStroke(2));
        g.drawLine(x, y - 42, boxRight() - 42, y - 42);
        drawClockIcon(g, x, y - 25, 26);
        g.setFont(new Font(settings.fontName, Font.PLAIN, 22));
        g.drawString(ageText(), x + 38, y);
        g.drawString("Fictional thread", x + 190, y);
    }

    private void drawClockIcon(Graphics2D g, int x, int y, int size) {
        g.setColor(style.muted);
        g.setStroke(new BasicStroke(3));
        g.drawOval(x, y, size, size);
        g.drawLine(x + size / 2, y + size / 2, x + size / 2, y + 7);
        g.drawLine(x + size / 2, y + size / 2, x + size - 8, y + size / 2);
    }

    private void drawWatermark(Graphics2D g) {
        if (!settings.showWatermark || settings.watermarkText == null || settings.watermarkText.isBlank()) {
            return;
        }
        g.setColor(new Color(style.muted.getRed(), style.muted.getGreen(), style.muted.getBlue(), 80));
        g.setFont(new Font(settings.fontName, Font.BOLD, 24));
        g.drawString(settings.watermarkText, boxRight() - 150, boxBottom() - 28);
    }

    private void drawProfilePicture(Graphics2D g, String imageName, int x, int y, int size) {
        BufferedImage profile = imageName == null || imageName.isBlank()
                ? null : loadImageIfPresent(Path.of("assets", "pfp", imageName));
        if (profile != null) {
            java.awt.Shape oldClip = g.getClip();
            g.setClip(new Ellipse2D.Double(x, y, size, size));
            g.drawImage(profile, x, y, size, size, null);
            g.setClip(oldClip);
            return;
        }
        g.setPaint(new GradientPaint(x, y, style.accent, x + size, y + size, style.secondary));
        g.fill(new Ellipse2D.Double(x, y, size, size));
        g.setColor(Color.WHITE);
        g.setFont(new Font(settings.fontName, Font.BOLD, 28));
        String initial = userName == null || userName.isBlank() ? "?" : userName.substring(0, 1).toUpperCase();
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(initial, x + (size - metrics.stringWidth(initial)) / 2,
                y + ((size - metrics.getHeight()) / 2) + metrics.getAscent());
    }

    private BufferedImage loadImageIfPresent(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return ImageIO.read(path.toFile());
        } catch (IOException e) {
            return null;
        }
    }

    private static void configure(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
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
            throw new IllegalStateException("Reddit rendering failed", e);
        }
    }

    private static void generateTextWithLocalLlm(Settings settings) throws IOException, InterruptedException {
        int requestedCount = settings.count > -1 ? settings.count : settings.autoTextCount;
        LocalLlmTextGenerator generator = new LocalLlmTextGenerator(settings.ollamaUrl, settings.llmModel);
        Path generatedFile = generator.generateToFile(settings.postTitle, settings.topic, requestedCount, settings.generatedTextFile);
        settings.commentsFile = generatedFile;
        if (settings.count < 0) {
            settings.count = requestedCount;
        }
        System.out.println("Generated script: " + generatedFile);
        if (settings.unloadOllamaAfterText) {
            generator.unloadModel();
        }
    }

    private static void generateBatch(Settings settings) throws IOException, InterruptedException {
        TextFileReader comments = TextFileReader.fromFile(settings.commentsFile);
        TextFileReader authors = TextFileReader.fromFile(settings.authorNamesFile);
        RandomProfileName profileName = new RandomProfileName(settings.profileDirectory);
        VoiceGenerator voiceGenerator = new VoiceGenerator(
                settings.ttsEngine, settings.ttsCommand, settings.voiceModel, settings.ttsTimeoutSeconds);
        VideoGenerator videoGenerator = new VideoGenerator(settings.videoCommand, settings.videoTimeoutSeconds);
        List<Path> videoClips = new ArrayList<>();
        List<FrameJob> jobs = new ArrayList<>();
        Random rand = new Random();
        Style style = Style.load(settings.styleName);

        List<String> lines = new ArrayList<>(comments.getLines());
        if (settings.shuffle) {
            Collections.shuffle(lines, rand);
        }
        int total = settings.count > -1 ? Math.min(lines.size(), settings.count) : lines.size();
        for (int i = 0; i < total; i++) {
            String author = authors.getRandomEntry(rand);
            String profile = profileName.getRandomProfileName();
            String current = lines.get(i);
            String narration = i == 0 && settings.postTitle != null && !settings.postTitle.isBlank()
                    ? settings.postTitle + ". " + current : current;
            String base = i + settings.outputPrefix;
            Path image = settings.outputDirectory.resolve(base + ".png");
            Path audio = settings.audioDirectory.resolve(base + ".wav");
            Path video = settings.videoDirectory.resolve(base + ".mp4");
            RedditScreenshotGenerator renderer = new RedditScreenshotGenerator(
                    base, author, settings.postLocation, current, profile,
                    0, 0, settings.outputDirectory, settings, style, i, total);
            jobs.add(new FrameJob(narration, image, audio, video, renderer));
        }

        System.out.println("Phase 1/4: rendering all images without synthetic engagement...");
        for (FrameJob job : jobs) {
            job.generator.generateImage();
            System.out.println("Generated image: " + job.imagePath);
        }
        if (voiceGenerator.isEnabled()) {
            System.out.println("Phase 2/4: generating all audio with " + settings.ttsEngine + "...");
            for (FrameJob job : jobs) {
                voiceGenerator.generateSpeech(job.text, job.audioPath);
                System.out.println("Generated audio: " + job.audioPath);
            }
        } else {
            System.out.println("Phase 2/4: skipping audio because TTS is disabled.");
        }
        if (settings.createVideo && voiceGenerator.isEnabled()) {
            System.out.println("Phase 3/4: rendering all legacy-compatible video clips...");
            for (FrameJob job : jobs) {
                videoGenerator.makeClip(job.imagePath, job.audioPath, job.videoPath,
                        settings.width, settings.height, settings.videoFps);
                videoClips.add(job.videoPath);
            }
        } else {
            System.out.println("Phase 3/4: skipping video clips.");
        }
        if (settings.concatVideo && !videoClips.isEmpty()) {
            Path finalVideo = settings.videoDirectory.resolve(settings.finalVideoName);
            videoGenerator.combineClips(videoClips, finalVideo);
            System.out.println("Generated final video: " + finalVideo);
        } else {
            System.out.println("Phase 4/4: no final stitch needed.");
        }
    }

    private static void printUsage() {
        System.err.println("Usage: java -cp out redditTxtToImg.RedditScreenshotGenerator [comments.txt] [output] [options]");
        System.err.println("Generated Reddit output never fabricates engagement metrics.");
    }

    private static class FrameJob {
        final String text;
        final Path imagePath;
        final Path audioPath;
        final Path videoPath;
        final RedditScreenshotGenerator generator;

        FrameJob(String text, Path imagePath, Path audioPath, Path videoPath, RedditScreenshotGenerator generator) {
            this.text = text;
            this.imagePath = imagePath;
            this.audioPath = audioPath;
            this.videoPath = videoPath;
            this.generator = generator;
        }
    }

    private static class Settings {
        int width = 1080;
        int height = 1920;
        int commentFontSize = 60;
        int authorFontSize = 30;
        int locationFontSize = 22;
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
        boolean unloadOllamaAfterText = true;
        boolean listVoices = false;
        boolean createVideo = false;
        boolean concatVideo = false;
        String fontName = "Arial";
        String postLocation = "/thread/comment";
        String postTitle = "Finish this story in the comments";
        String outputPrefix = "aithread";
        String styleName = "dark";
        String watermarkText = "";
        String topic = "weird everyday stories";
        String llmModel = "llama3.1:8b";
        String ollamaUrl = "http://localhost:11434/api/generate";
        String ttsEngine = "none";
        String ttsCommand = "piper";
        String videoCommand = "ffmpeg";
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
            if (args.length >= 1 && !args[0].startsWith("--")) settings.commentsFile = Path.of(args[0]);
            if (args.length >= 2 && !args[1].startsWith("--")) settings.outputDirectory = Path.of(args[1]);
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
                else if ("--keep-ollama-loaded".equals(arg)) settings.unloadOllamaAfterText = false;
                else if ("--post-title".equals(arg) && i + 1 < args.length) settings.postTitle = args[++i];
                else if ("--topic".equals(arg) && i + 1 < args.length) settings.topic = args[++i];
                else if ("--llm-model".equals(arg) && i + 1 < args.length) settings.llmModel = args[++i];
                else if ("--llm-url".equals(arg) && i + 1 < args.length) settings.ollamaUrl = args[++i];
                else if ("--script-out".equals(arg) && i + 1 < args.length) settings.generatedTextFile = Path.of(args[++i]);
                else if ("--tts".equals(arg) && i + 1 < args.length) settings.ttsEngine = args[++i].toLowerCase();
                else if ("--voice".equals(arg) && i + 1 < args.length) {
                    String voiceValue = args[++i];
                    settings.voiceModel = "kokoro".equals(settings.ttsEngine)
                            ? Path.of(voiceValue) : VoiceCatalog.resolveVoice(voiceValue, settings.voiceDirectory);
                }
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
                settings.postTitle = properties.getProperty("postTitle", settings.postTitle);
                settings.topic = properties.getProperty("topic", settings.topic);
                settings.llmModel = properties.getProperty("llmModel", settings.llmModel);
                settings.ollamaUrl = properties.getProperty("ollamaUrl", settings.ollamaUrl);
                settings.ttsEngine = properties.getProperty("ttsEngine", settings.ttsEngine);
                settings.ttsCommand = properties.getProperty("ttsCommand", settings.ttsCommand);
                settings.voiceDirectory = Path.of(properties.getProperty("voiceDirectory", settings.voiceDirectory.toString()));
                String defaultVoice = properties.getProperty("voiceModel", settings.voiceModel.toString());
                settings.voiceModel = "kokoro".equals(settings.ttsEngine)
                        ? Path.of(defaultVoice) : VoiceCatalog.resolveVoice(defaultVoice, settings.voiceDirectory);
                settings.audioDirectory = Path.of(properties.getProperty("audioDirectory", settings.audioDirectory.toString()));
                settings.videoDirectory = Path.of(properties.getProperty("videoDirectory", settings.videoDirectory.toString()));
                settings.videoCommand = properties.getProperty("videoCommand", settings.videoCommand);
                settings.finalVideoName = properties.getProperty("finalVideoName", settings.finalVideoName);
                settings.unloadOllamaAfterText = Boolean.parseBoolean(properties.getProperty("unloadOllamaAfterText", "true"));
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
                for (String line : Files.readAllLines(path)) {
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
}
