package redditTxtToImg;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
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
import java.util.Locale;
import java.util.Properties;
import java.util.Random;

import javax.imageio.ImageIO;

/**
 * X-style post and reply renderer.
 *
 * Kept separate from the Reddit renderer so the original Reddit path stays stable.
 */
public class XThreadGenerator {
    private static final int MARGIN = 64;
    private static final int PHONE_TOP = 76;
    private static final int TOP_BAR_HEIGHT = 132;
    private static final int CARD_TOP = 210;
    private static final int CARD_BOTTOM_PADDING = 260;

    private final Settings settings;
    private final String fileName;
    private final String displayName;
    private final String handle;
    private final String profileImageName;
    private final String text;
    private final int itemIndex;
    private final int totalItems;
    private final int replies;
    private final int reposts;
    private final int likes;
    private final int views;
    private final Path outputDirectory;

    public XThreadGenerator(String fileName, String displayName, String handle, String profileImageName,
                            String text, int itemIndex, int totalItems, int replies, int reposts,
                            int likes, int views, Path outputDirectory, Settings settings) {
        this.fileName = fileName;
        this.displayName = displayName;
        this.handle = handle;
        this.profileImageName = profileImageName;
        this.text = text;
        this.itemIndex = itemIndex;
        this.totalItems = Math.max(1, totalItems);
        this.replies = replies;
        this.reposts = reposts;
        this.likes = likes;
        this.views = views;
        this.outputDirectory = outputDirectory;
        this.settings = settings;
    }

    public static void main(String[] args) {
        Settings settings = Settings.fromArgs(args);
        System.setProperty("java.awt.headless", "true");
        try {
            if (settings.listVoices) {
                VoiceCatalog.printVoices(settings.voiceDirectory);
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

    public void generateImage() throws IOException {
        Files.createDirectories(outputDirectory);
        BufferedImage image = new BufferedImage(settings.width, settings.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        drawBackground(g2d);
        drawPhoneFrame(g2d);
        drawTopBar(g2d);
        drawPost(g2d);

        g2d.dispose();
        ImageIO.write(image, "png", outputDirectory.resolve(fileName + ".png").toFile());
    }

    private boolean isOriginalPost() {
        return itemIndex == 0;
    }

    private int cardLeft() {
        return MARGIN;
    }

    private int cardRight() {
        return settings.width - MARGIN;
    }

    private int cardWidth() {
        return cardRight() - cardLeft();
    }

    private int cardBottom() {
        return settings.height - CARD_BOTTOM_PADDING;
    }

    private void drawBackground(Graphics2D g2d) {
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, settings.width, settings.height);
    }

    private void drawPhoneFrame(Graphics2D g2d) {
        g2d.setColor(Color.BLACK);
        g2d.fillRoundRect(cardLeft(), PHONE_TOP, cardWidth(), settings.height - (PHONE_TOP * 2), 46, 46);
        g2d.setColor(new Color(47, 51, 54));
        g2d.setStroke(new BasicStroke(2));
        g2d.drawRoundRect(cardLeft(), PHONE_TOP, cardWidth(), settings.height - (PHONE_TOP * 2), 46, 46);
    }

    private void drawTopBar(Graphics2D g2d) {
        int x = cardLeft();
        int y = PHONE_TOP;
        int w = cardWidth();
        g2d.setColor(Color.BLACK);
        g2d.fillRoundRect(x, y, w, TOP_BAR_HEIGHT, 46, 46);
        g2d.setColor(new Color(47, 51, 54));
        g2d.setStroke(new BasicStroke(2));
        g2d.drawLine(x, y + TOP_BAR_HEIGHT, x + w, y + TOP_BAR_HEIGHT);

        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font(settings.fontName, Font.BOLD, 42));
        g2d.drawString(isOriginalPost() ? "Post" : "Thread", x + 52, y + 84);
        g2d.setFont(new Font(settings.fontName, Font.BOLD, 48));
        g2d.drawString("X", x + (w / 2) - 16, y + 86);
    }

    private void drawPost(Graphics2D g2d) {
        int x = cardLeft();
        int y = CARD_TOP;
        int w = cardWidth();
        int bottom = cardBottom();
        int avatarSize = isOriginalPost() ? 88 : 74;
        int avatarX = x + 46;
        int avatarY = y + 38;
        int contentX = avatarX + avatarSize + 24;
        int contentRight = x + w - 46;

        g2d.setColor(Color.BLACK);
        g2d.fillRect(x, y, w, bottom - y);

        if (!isOriginalPost()) {
            g2d.setColor(new Color(47, 51, 54));
            g2d.setStroke(new BasicStroke(5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2d.drawLine(x + 90, y - 34, x + 90, avatarY - 10);
        }

        drawProfilePicture(g2d, profileImageName, avatarX, avatarY, avatarSize);
        drawHeader(g2d, contentX, avatarY + 30);
        drawMoreDots(g2d, contentRight - 34, avatarY + 26);

        int textX = isOriginalPost() ? x + 46 : contentX;
        int textTop = isOriginalPost() ? avatarY + avatarSize + 54 : avatarY + 172;
        int maxTextWidth = isOriginalPost() ? contentRight - x - 46 : contentRight - contentX;
        drawTweetText(g2d, textX, textTop, maxTextWidth, bottom - 260);

        drawTimestampAndViews(g2d, x + 46, bottom - 205);
        drawDivider(g2d, x + 46, bottom - 170, contentRight);
        drawActionRow(g2d, x + 64, bottom - 110, contentRight - 40);
        drawDivider(g2d, x + 46, bottom - 58, contentRight);
    }

    private void drawHeader(Graphics2D g2d, int x, int baseline) {
        g2d.setFont(new Font(settings.fontName, Font.BOLD, isOriginalPost() ? 34 : 30));
        g2d.setColor(Color.WHITE);
        g2d.drawString(displayName, x, baseline);

        int nameWidth = g2d.getFontMetrics().stringWidth(displayName);
        if (settings.showVerifiedBadge) {
            drawVerifiedBadge(g2d, x + nameWidth + 14, baseline - 25, 26);
        }

        g2d.setFont(new Font(settings.fontName, Font.PLAIN, isOriginalPost() ? 28 : 25));
        g2d.setColor(new Color(113, 118, 123));
        String meta = "@" + handle + " · " + ageText();
        if (isOriginalPost()) {
            g2d.drawString(meta, x, baseline + 36);
        } else {
            g2d.drawString(meta, x, baseline + 32);
            g2d.drawString("Replying to @" + settings.originalHandle, x, baseline + 74);
        }
    }

    private void drawTweetText(Graphics2D g2d, int x, int y, int maxWidth, int maxBottom) {
        int fontSize = isOriginalPost() ? 56 : 44;
        Font textFont = new Font(settings.fontName, Font.PLAIN, fontSize);
        g2d.setFont(textFont);
        g2d.setColor(Color.WHITE);
        FontMetrics metrics = g2d.getFontMetrics(textFont);
        List<String> lines = CommentWrapper.wrapComment(text, metrics, maxWidth);
        int lineHeight = fontSize + 14;
        int currentY = y;
        for (String line : lines) {
            if (currentY > maxBottom) {
                g2d.drawString("...", x, currentY);
                return;
            }
            g2d.drawString(line, x, currentY);
            currentY += lineHeight;
        }
    }

    private void drawTimestampAndViews(Graphics2D g2d, int x, int y) {
        g2d.setFont(new Font(settings.fontName, Font.PLAIN, 26));
        g2d.setColor(new Color(113, 118, 123));
        String line = isOriginalPost()
                ? "8:41 PM · " + dateText() + " · " + compactNumber(views) + " Views"
                : ageText() + " · " + compactNumber(views) + " Views";
        g2d.drawString(line, x, y);
    }

    private void drawActionRow(Graphics2D g2d, int left, int y, int right) {
        int span = (right - left) / 4;
        drawReplyIcon(g2d, left, y - 24, 30);
        drawMetric(g2d, replies, left + 42, y);
        drawRepostIcon(g2d, left + span, y - 26, 34);
        drawMetric(g2d, reposts, left + span + 48, y);
        drawLikeIcon(g2d, left + (span * 2), y - 25, 34);
        drawMetric(g2d, likes, left + (span * 2) + 46, y);
        drawViewBars(g2d, left + (span * 3), y - 25, 34);
        drawMetric(g2d, views, left + (span * 3) + 48, y);
    }

    private void drawMetric(Graphics2D g2d, int value, int x, int y) {
        g2d.setFont(new Font(settings.fontName, Font.PLAIN, 25));
        g2d.setColor(new Color(113, 118, 123));
        g2d.drawString(compactNumber(value), x, y);
    }

    private void drawDivider(Graphics2D g2d, int x, int y, int right) {
        g2d.setColor(new Color(47, 51, 54));
        g2d.setStroke(new BasicStroke(2));
        g2d.drawLine(x, y, right, y);
    }

    private void drawMoreDots(Graphics2D g2d, int x, int y) {
        g2d.setColor(new Color(113, 118, 123));
        g2d.fillOval(x, y, 6, 6);
        g2d.fillOval(x + 14, y, 6, 6);
        g2d.fillOval(x + 28, y, 6, 6);
    }

    private void drawVerifiedBadge(Graphics2D g2d, int x, int y, int size) {
        g2d.setColor(new Color(29, 155, 240));
        g2d.fillOval(x, y, size, size);
        g2d.setColor(Color.WHITE);
        g2d.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2d.drawLine(x + 7, y + 14, x + 12, y + 19);
        g2d.drawLine(x + 12, y + 19, x + 20, y + 8);
    }

    private void drawReplyIcon(Graphics2D g2d, int x, int y, int size) {
        g2d.setColor(new Color(113, 118, 123));
        g2d.setStroke(new BasicStroke(3));
        g2d.drawRoundRect(x, y, size, size - 6, 14, 14);
        g2d.drawLine(x + 9, y + size - 6, x + 4, y + size + 3);
    }

    private void drawRepostIcon(Graphics2D g2d, int x, int y, int size) {
        g2d.setColor(new Color(113, 118, 123));
        g2d.setStroke(new BasicStroke(3));
        g2d.drawLine(x + 6, y + 9, x + size - 8, y + 9);
        g2d.drawLine(x + size - 12, y + 4, x + size - 6, y + 9);
        g2d.drawLine(x + size - 12, y + 14, x + size - 6, y + 9);
        g2d.drawLine(x + size - 6, y + size - 8, x + 8, y + size - 8);
        g2d.drawLine(x + 12, y + size - 13, x + 6, y + size - 8);
        g2d.drawLine(x + 12, y + size - 3, x + 6, y + size - 8);
    }

    private void drawLikeIcon(Graphics2D g2d, int x, int y, int size) {
        g2d.setColor(new Color(113, 118, 123));
        g2d.setStroke(new BasicStroke(3));
        g2d.drawArc(x, y, size / 2, size / 2, 0, 220);
        g2d.drawArc(x + size / 2 - 2, y, size / 2, size / 2, -40, 220);
        g2d.drawLine(x + 3, y + 17, x + size / 2, y + size);
        g2d.drawLine(x + size - 3, y + 17, x + size / 2, y + size);
    }

    private void drawViewBars(Graphics2D g2d, int x, int y, int size) {
        g2d.setColor(new Color(113, 118, 123));
        g2d.fillRoundRect(x + 3, y + size - 12, 5, 12, 3, 3);
        g2d.fillRoundRect(x + 14, y + size - 22, 5, 22, 3, 3);
        g2d.fillRoundRect(x + 25, y + size - 32, 5, 32, 3, 3);
    }

    private void drawProfilePicture(Graphics2D g2d, String imageName, int x, int y, int size) {
        BufferedImage profile = loadProfileImage(imageName);
        if (profile != null) {
            Shape oldClip = g2d.getClip();
            g2d.setClip(new Ellipse2D.Double(x, y, size, size));
            g2d.drawImage(profile, x, y, size, size, null);
            g2d.setClip(oldClip);
            return;
        }

        g2d.setColor(new Color(29, 155, 240));
        g2d.fillOval(x, y, size, size);
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font(settings.fontName, Font.BOLD, Math.max(24, size / 3)));
        String initial = displayName == null || displayName.isBlank() ? "?" : displayName.substring(0, 1).toUpperCase(Locale.ROOT);
        FontMetrics metrics = g2d.getFontMetrics();
        g2d.drawString(initial, x + (size - metrics.stringWidth(initial)) / 2, y + ((size - metrics.getHeight()) / 2) + metrics.getAscent());
    }

    private BufferedImage loadProfileImage(String imageName) {
        if (imageName == null || imageName.isBlank()) {
            return null;
        }
        Path path = Path.of("assets", "pfp", imageName);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return ImageIO.read(path.toFile());
        } catch (IOException e) {
            return null;
        }
    }

    private String ageText() {
        if (isOriginalPost()) {
            int hours = Math.max(1, Math.round((totalItems * 18 + 90) / 60.0f));
            return hours + "h";
        }
        int minutes = Math.max(7, (totalItems - itemIndex + 1) * 13);
        return minutes >= 60 ? Math.max(1, Math.round(minutes / 60.0f)) + "h" : minutes + "m";
    }

    private String dateText() {
        return "Jul 2, 2026";
    }

    private static String compactNumber(int value) {
        if (value >= 1_000_000) {
            return String.format(Locale.US, "%.1fM", value / 1_000_000.0).replace(".0M", "M");
        }
        if (value >= 10_000) {
            return String.format(Locale.US, "%.1fK", value / 1_000.0).replace(".0K", "K");
        }
        return NumberFormat.getIntegerInstance(Locale.US).format(value);
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
        VoiceGenerator voiceGenerator = new VoiceGenerator(settings.ttsEngine, settings.ttsCommand, settings.voiceModel, settings.ttsTimeoutSeconds);
        VideoGenerator videoGenerator = new VideoGenerator(settings.videoCommand, settings.videoTimeoutSeconds);
        List<Path> videoClips = new ArrayList<>();
        List<FrameJob> jobs = new ArrayList<>();
        Random rand = new Random();

        List<String> lines = new ArrayList<>(comments.getLines());
        if (settings.shuffle) {
            Collections.shuffle(lines, rand);
        }
        int total = lines.size();
        if (settings.count > -1) {
            total = Math.min(total, settings.count);
        }

        String originalAuthor = normalizeDisplayName(authors.getRandomEntry(rand));
        settings.originalHandle = toHandle(originalAuthor, rand);
        int originalViews = generateOriginalViews(rand, settings);

        for (int i = 0; i < total; i++) {
            String author = i == 0 ? originalAuthor : normalizeDisplayName(authors.getRandomEntry(rand));
            String handle = i == 0 ? settings.originalHandle : toHandle(author, rand);
            int views = i == 0 ? originalViews : generateReplyViews(rand, originalViews, i);
            int likes = Math.max(1, Math.min(views - 1, views / (i == 0 ? 6 : 9) + rand.nextInt(Math.max(8, views / 24))));
            int reposts = Math.max(0, likes / 7 + rand.nextInt(Math.max(3, Math.max(4, likes / 22))));
            int replies = Math.max(0, likes / 12 + rand.nextInt(Math.max(2, Math.max(3, likes / 35))));
            String currentFileName = i + settings.outputPrefix;
            Path imagePath = settings.outputDirectory.resolve(currentFileName + ".png");
            Path audioPath = settings.audioDirectory.resolve(currentFileName + ".wav");
            Path videoPath = settings.videoDirectory.resolve(currentFileName + ".mp4");
            String currentText = lines.get(i);
            String narrationText = i == 0 && settings.postTitle != null && !settings.postTitle.isBlank()
                    ? settings.postTitle + ". " + currentText
                    : currentText;

            XThreadGenerator generator = new XThreadGenerator(
                    currentFileName, author, handle, profileName.getRandomProfileName(), currentText,
                    i, total, replies, reposts, likes, views, settings.outputDirectory, settings);
            jobs.add(new FrameJob(narrationText, imagePath, audioPath, videoPath, generator));
        }

        System.out.println("Phase 1/4: rendering all X-style images...");
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

        if (settings.createVideo) {
            System.out.println("Phase 3/4: rendering all video clips...");
            if (!voiceGenerator.isEnabled()) {
                System.out.println("Skipping videos: enable voice first with --tts piper or --tts kokoro");
            } else {
                for (FrameJob job : jobs) {
                    videoGenerator.makeClip(job.imagePath, job.audioPath, job.videoPath, settings.width, settings.height, settings.videoFps);
                    videoClips.add(job.videoPath);
                    System.out.println("Generated video: " + job.videoPath);
                }
            }
        } else {
            System.out.println("Phase 3/4: skipping video clips.");
        }

        if (settings.concatVideo && !videoClips.isEmpty()) {
            System.out.println("Phase 4/4: stitching final video...");
            Path finalVideo = settings.videoDirectory.resolve(settings.finalVideoName);
            videoGenerator.combineClips(videoClips, finalVideo);
            System.out.println("Generated final video: " + finalVideo);
        } else {
            System.out.println("Phase 4/4: no final stitch needed.");
        }
    }

    private static int generateOriginalViews(Random rand, Settings settings) {
        int minimum = Math.min(settings.maxViews, 20000);
        if (settings.maxViews <= minimum) {
            return Math.max(1, settings.maxViews);
        }
        return minimum + rand.nextInt(settings.maxViews - minimum + 1);
    }

    private static int generateReplyViews(Random rand, int originalViews, int index) {
        int hardCap = Math.max(1, originalViews - 1);
        double factor = Math.max(0.08, 0.55 - (index * 0.06));
        int cap = Math.max(1, (int) Math.round(hardCap * factor));
        return 1 + rand.nextInt(cap);
    }

    private static String normalizeDisplayName(String value) {
        if (value == null || value.isBlank()) {
            return "X User";
        }
        String cleaned = value.replace('_', ' ').trim();
        return cleaned.length() > 24 ? cleaned.substring(0, 24).trim() : cleaned;
    }

    private static String toHandle(String displayName, Random rand) {
        String base = displayName == null ? "xuser" : displayName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (base.isBlank()) {
            base = "xuser";
        }
        if (base.length() > 14) {
            base = base.substring(0, 14);
        }
        return base + (10 + rand.nextInt(990));
    }

    private static void printUsage() {
        System.out.println("Usage: java -cp out redditTxtToImg.CheckedRunner [comments.txt] [output] --platform x [options]");
        System.out.println("Image options: --count N --prefix NAME --shuffle --top --no-watermark");
        System.out.println("Local LLM options: --auto --post-title TEXT --topic TEXT --llm-model MODEL --llm-url URL --script-out FILE --keep-ollama-loaded");
        System.out.println("Local TTS options: --tts none|piper|kokoro --voice NAME_OR_PATH --voice-dir DIR --list-voices --tts-command COMMAND --audio-dir DIR");
        System.out.println("Video options: --video --concat-video --video-dir DIR --video-command ffmpeg --fps 30 --final-video final.mp4");
    }

    private static class FrameJob {
        final String text;
        final Path imagePath;
        final Path audioPath;
        final Path videoPath;
        final XThreadGenerator generator;

        FrameJob(String text, Path imagePath, Path audioPath, Path videoPath, XThreadGenerator generator) {
            this.text = text;
            this.imagePath = imagePath;
            this.audioPath = audioPath;
            this.videoPath = videoPath;
            this.generator = generator;
        }
    }

    static class Settings {
        int width = 1080;
        int height = 1920;
        int maxViews = 50000;
        int count = -1;
        int autoTextCount = 10;
        int ttsTimeoutSeconds = 120;
        int videoTimeoutSeconds = 180;
        int videoFps = 30;
        boolean shuffle = false;
        boolean autoGenerateText = false;
        boolean unloadOllamaAfterText = true;
        boolean listVoices = false;
        boolean createVideo = false;
        boolean concatVideo = false;
        boolean showVerifiedBadge = true;
        String fontName = "Arial";
        String postTitle = "Finish this story in the comments";
        String outputPrefix = "aithread";
        String topic = "weird everyday stories";
        String llmModel = "llama3.1:8b";
        String ollamaUrl = "http://localhost:11434/api/generate";
        String ttsEngine = "none";
        String ttsCommand = "piper";
        String videoCommand = "ff" + "mpeg";
        String finalVideoName = "final.mp4";
        String originalHandle = "op";
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
                if ("--platform".equals(arg) && i + 1 < args.length) i++;
                else if ("--count".equals(arg) && i + 1 < args.length) settings.count = parseInt(args[++i], settings.count);
                else if ("--prefix".equals(arg) && i + 1 < args.length) settings.outputPrefix = args[++i];
                else if ("--names".equals(arg) && i + 1 < args.length) settings.authorNamesFile = Path.of(args[++i]);
                else if ("--profiles".equals(arg) && i + 1 < args.length) settings.profileDirectory = Path.of(args[++i]);
                else if ("--shuffle".equals(arg)) settings.shuffle = true;
                else if ("--auto".equals(arg)) settings.autoGenerateText = true;
                else if ("--keep-ollama-loaded".equals(arg)) settings.unloadOllamaAfterText = false;
                else if ("--post-title".equals(arg) && i + 1 < args.length) settings.postTitle = args[++i];
                else if ("--topic".equals(arg) && i + 1 < args.length) settings.topic = args[++i];
                else if ("--llm-model".equals(arg) && i + 1 < args.length) settings.llmModel = args[++i];
                else if ("--llm-url".equals(arg) && i + 1 < args.length) settings.ollamaUrl = args[++i];
                else if ("--script-out".equals(arg) && i + 1 < args.length) settings.generatedTextFile = Path.of(args[++i]);
                else if ("--tts".equals(arg) && i + 1 < args.length) settings.ttsEngine = args[++i].toLowerCase(Locale.ROOT);
                else if ("--voice".equals(arg) && i + 1 < args.length) {
                    String voiceValue = args[++i];
                    settings.voiceModel = "kokoro".equals(settings.ttsEngine)
                            ? Path.of(voiceValue)
                            : VoiceCatalog.resolveVoice(voiceValue, settings.voiceDirectory);
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
                settings.postTitle = properties.getProperty("postTitle", settings.postTitle);
                settings.topic = properties.getProperty("topic", settings.topic);
                settings.llmModel = properties.getProperty("llmModel", settings.llmModel);
                settings.ollamaUrl = properties.getProperty("ollamaUrl", settings.ollamaUrl);
                settings.ttsEngine = properties.getProperty("ttsEngine", settings.ttsEngine);
                settings.ttsCommand = properties.getProperty("ttsCommand", settings.ttsCommand);
                settings.voiceDirectory = Path.of(properties.getProperty("voiceDirectory", settings.voiceDirectory.toString()));
                String defaultVoice = properties.getProperty("voiceModel", settings.voiceModel.toString());
                settings.voiceModel = "kokoro".equals(settings.ttsEngine)
                        ? Path.of(defaultVoice)
                        : VoiceCatalog.resolveVoice(defaultVoice, settings.voiceDirectory);
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
}
