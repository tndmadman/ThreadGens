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
 * Generated/fictional output never fabricates likes, reposts, replies, views,
 * verification state, or precise posting timestamps. Legacy numeric constructor
 * parameters remain for source compatibility and are intentionally ignored.
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
    private final Path outputDirectory;
    private final List<RenderedWordLayout.Box> narrationWordBoxes = new ArrayList<>();

    public XThreadGenerator(String fileName, String displayName, String handle, String profileImageName,
                            String text, int itemIndex, int totalItems, int ignoredReplies, int ignoredReposts,
                            int ignoredLikes, int ignoredViews, Path outputDirectory, Settings settings) {
        this.fileName = fileName;
        this.displayName = displayName;
        this.handle = handle;
        this.profileImageName = profileImageName;
        this.text = text;
        this.itemIndex = itemIndex;
        this.totalItems = Math.max(1, totalItems);
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
            throw new IllegalStateException("X rendering failed", e);
        }
    }

    public void generateImage() throws IOException {
        Files.createDirectories(outputDirectory);
        Path imagePath = outputDirectory.resolve(fileName + ".png");
        Files.deleteIfExists(RenderedWordLayout.sidecarFor(imagePath));
        narrationWordBoxes.clear();

        BufferedImage image = new BufferedImage(settings.width, settings.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            configure(g);
            drawBackground(g);
            drawPhoneFrame(g);
            drawTopBar(g);
            drawPost(g);
        } finally {
            g.dispose();
        }

        ImageIO.write(image, "png", imagePath.toFile());
        String narration = RenderedWordLayout.narrationForVisibleText(text);
        try {
            RenderedWordLayout.write(imagePath, narration, settings.width, settings.height, narrationWordBoxes);
        } catch (IOException e) {
            Files.deleteIfExists(imagePath);
            throw e;
        }
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

    private void drawBackground(Graphics2D g) {
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, settings.width, settings.height);
    }

    private void drawPhoneFrame(Graphics2D g) {
        g.setColor(Color.BLACK);
        g.fillRoundRect(cardLeft(), PHONE_TOP, cardWidth(), settings.height - (PHONE_TOP * 2), 46, 46);
        g.setColor(new Color(47, 51, 54));
        g.setStroke(new BasicStroke(2));
        g.drawRoundRect(cardLeft(), PHONE_TOP, cardWidth(), settings.height - (PHONE_TOP * 2), 46, 46);
    }

    private void drawTopBar(Graphics2D g) {
        int x = cardLeft();
        int y = PHONE_TOP;
        int w = cardWidth();
        g.setColor(Color.BLACK);
        g.fillRoundRect(x, y, w, TOP_BAR_HEIGHT, 46, 46);
        g.setColor(new Color(47, 51, 54));
        g.setStroke(new BasicStroke(2));
        g.drawLine(x, y + TOP_BAR_HEIGHT, x + w, y + TOP_BAR_HEIGHT);
        g.setColor(Color.WHITE);
        g.setFont(new Font(settings.fontName, Font.BOLD, 42));
        g.drawString(isOriginalPost() ? "Post" : "Thread", x + 52, y + 84);
        g.setFont(new Font(settings.fontName, Font.BOLD, 48));
        g.drawString("X", x + (w / 2) - 16, y + 86);
    }

    private void drawPost(Graphics2D g) {
        int x = cardLeft();
        int y = CARD_TOP;
        int w = cardWidth();
        int bottom = cardBottom();
        int avatarSize = isOriginalPost() ? 88 : 74;
        int avatarX = x + 46;
        int avatarY = y + 38;
        int contentX = avatarX + avatarSize + 24;
        int contentRight = x + w - 46;

        g.setColor(Color.BLACK);
        g.fillRect(x, y, w, bottom - y);
        if (!isOriginalPost()) {
            g.setColor(new Color(47, 51, 54));
            g.setStroke(new BasicStroke(5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(x + 90, y - 34, x + 90, avatarY - 10);
        }

        drawProfilePicture(g, profileImageName, avatarX, avatarY, avatarSize);
        drawHeader(g, contentX, avatarY + 30);
        drawMoreDots(g, contentRight - 34, avatarY + 26);

        int textX = isOriginalPost() ? x + 46 : contentX;
        int textTop = isOriginalPost() ? avatarY + avatarSize + 54 : avatarY + 172;
        int maxTextWidth = isOriginalPost() ? contentRight - x - 46 : contentRight - contentX;
        drawTweetText(g, textX, textTop, maxTextWidth, bottom - 225);
        drawNeutralFooter(g, x + 46, bottom - 120, contentRight);
    }

    private void drawHeader(Graphics2D g, int x, int baseline) {
        g.setFont(new Font(settings.fontName, Font.BOLD, isOriginalPost() ? 34 : 30));
        g.setColor(Color.WHITE);
        g.drawString(displayName, x, baseline);
        g.setFont(new Font(settings.fontName, Font.PLAIN, isOriginalPost() ? 28 : 25));
        g.setColor(new Color(113, 118, 123));
        String meta = "@" + handle + " · " + ageText();
        g.drawString(meta, x, baseline + (isOriginalPost() ? 36 : 32));
        if (!isOriginalPost()) {
            g.drawString("Replying to @" + settings.originalHandle, x, baseline + 74);
        }
    }

    private void drawTweetText(Graphics2D g, int x, int y, int maxWidth, int maxBottom) {
        int fontSize = isOriginalPost() ? 56 : 44;
        Font font = new Font(settings.fontName, Font.PLAIN, fontSize);
        g.setFont(font);
        g.setColor(Color.WHITE);
        FontMetrics metrics = g.getFontMetrics(font);
        List<String> lines = CommentWrapper.wrapComment(text, metrics, maxWidth);
        int lineHeight = fontSize + 14;
        int maxLines = y > maxBottom ? 0 : ((maxBottom - y) / lineHeight) + 1;
        if (lines.size() > maxLines) {
            throw new IllegalArgumentException(
                    "X post/reply does not fit the visible social card; regenerate shorter visible text instead of truncating narration.");
        }
        int currentY = y;
        for (String line : lines) {
            g.drawString(line, x, currentY);
            RenderedWordLayout.addLineBoxes(
                    narrationWordBoxes, line, metrics, x, currentY, settings.width, settings.height);
            currentY += lineHeight;
        }
    }

    private void drawNeutralFooter(Graphics2D g, int left, int y, int right) {
        g.setColor(new Color(47, 51, 54));
        g.setStroke(new BasicStroke(2));
        g.drawLine(left, y - 28, right, y - 28);
        g.drawLine(left, y + 50, right, y + 50);
        g.setFont(new Font(settings.fontName, Font.PLAIN, 25));
        g.setColor(new Color(113, 118, 123));
        g.drawString(ageText() + " · Fictional thread", left + 8, y + 16);
        drawReplyIcon(g, right - 150, y - 10, 28);
        drawLikeIcon(g, right - 86, y - 11, 28);
    }

    private void drawMoreDots(Graphics2D g, int x, int y) {
        g.setColor(new Color(113, 118, 123));
        g.fillOval(x, y, 6, 6);
        g.fillOval(x + 14, y, 6, 6);
        g.fillOval(x + 28, y, 6, 6);
    }

    private void drawReplyIcon(Graphics2D g, int x, int y, int size) {
        g.setColor(new Color(113, 118, 123));
        g.setStroke(new BasicStroke(3));
        g.drawRoundRect(x, y, size, size - 6, 14, 14);
        g.drawLine(x + 9, y + size - 6, x + 4, y + size + 3);
    }

    private void drawLikeIcon(Graphics2D g, int x, int y, int size) {
        g.setColor(new Color(113, 118, 123));
        g.setStroke(new BasicStroke(3));
        g.drawArc(x, y, size / 2, size / 2, 0, 220);
        g.drawArc(x + size / 2 - 2, y, size / 2, size / 2, -40, 220);
        g.drawLine(x + 3, y + 14, x + size / 2, y + size);
        g.drawLine(x + size - 3, y + 14, x + size / 2, y + size);
    }

    private void drawProfilePicture(Graphics2D g, String imageName, int x, int y, int size) {
        BufferedImage profile = loadProfileImage(imageName);
        if (profile != null) {
            Shape oldClip = g.getClip();
            g.setClip(new Ellipse2D.Double(x, y, size, size));
            g.drawImage(profile, x, y, size, size, null);
            g.setClip(oldClip);
            return;
        }
        g.setColor(new Color(72, 93, 124));
        g.fillOval(x, y, size, size);
        g.setColor(Color.WHITE);
        g.setFont(new Font(settings.fontName, Font.BOLD, Math.max(24, size / 3)));
        String initial = displayName == null || displayName.isBlank()
                ? "?" : displayName.substring(0, 1).toUpperCase(Locale.ROOT);
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(initial, x + (size - metrics.stringWidth(initial)) / 2,
                y + ((size - metrics.getHeight()) / 2) + metrics.getAscent());
    }

    private BufferedImage loadProfileImage(String imageName) {
        Path path = ProfileImages.resolve(imageName);
        if (path == null || !Files.exists(path)) return null;
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

    private static void configure(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    private static void generateTextWithLocalLlm(Settings settings) throws IOException, InterruptedException {
        int requestedCount = settings.count > -1 ? settings.count : settings.autoTextCount;
        LocalLlmTextGenerator generator = new LocalLlmTextGenerator(settings.ollamaUrl, settings.llmModel);
        Path generatedFile = generator.generateToFile(
                settings.postTitle, settings.topic, requestedCount, settings.generatedTextFile);
        settings.commentsFile = generatedFile;
        if (settings.count < 0) settings.count = requestedCount;
        if (settings.unloadOllamaAfterText) generator.unloadModel();
    }

    private static void generateBatch(Settings settings) throws IOException, InterruptedException {
        TextFileReader comments = TextFileReader.fromFile(settings.commentsFile);
        TextFileReader authors = TextFileReader.fromFile(settings.authorNamesFile);
        RandomProfileName profileName = new RandomProfileName(settings.profileDirectory);
        VoicePlan voicePlan = new VoicePlan(
                settings.ttsEngine,
                settings.ttsCommand,
                settings.voiceModel,
                settings.voiceSeries,
                settings.voiceDirectory,
                settings.voiceSelection,
                settings.seriesId.isBlank() ? settings.postTitle + "|" + settings.topic : settings.seriesId,
                VoicePlan.Delivery.resolve(
                        settings.ttsDelivery,
                        settings.ttsSpeedConfigured ? settings.ttsSpeed : null,
                        settings.ttsLanguage,
                        settings.ttsSentencePauseConfigured ? settings.ttsSentencePauseMs : null),
                settings.ttsTimeoutSeconds);
        VideoGenerator videoGenerator = new VideoGenerator(settings.videoCommand, settings.videoTimeoutSeconds);
        List<Path> clips = new ArrayList<>();
        List<FrameJob> jobs = new ArrayList<>();
        Random rand = new Random();

        List<String> lines = new ArrayList<>(comments.getLines());
        if (settings.shuffle) Collections.shuffle(lines, rand);
        int total = settings.count > -1 ? Math.min(lines.size(), settings.count) : lines.size();
        if (total <= 0) {
            throw new IOException("No non-empty thread lines were available to render.");
        }
        IdentityHistory identityHistory = new IdentityHistory(
                settings.identityHistoryFile,
                settings.identityHistoryLimit,
                settings.identityHistoryEnabled);
        List<IdentityHistory.Identity> identities = identityHistory.selectAndRecord(
                authors.getLines(),
                profileName.profileImageNames(),
                profileName.aiProfileImageNames(),
                total,
                settings.outputPrefix + "-" + System.currentTimeMillis());
        String originalAuthor = normalizeDisplayName(identities.get(0).name());
        settings.originalHandle = toHandle(originalAuthor, rand);

        for (int i = 0; i < total; i++) {
            IdentityHistory.Identity identity = identities.get(i);
            String author = i == 0 ? originalAuthor : normalizeDisplayName(identity.name());
            String handle = i == 0 ? settings.originalHandle : toHandle(author, rand);
            String current = lines.get(i);
            // The batch title is a hidden reply-style instruction for X. Only
            // the visible post/reply text belongs in narration and word timing.
            String narration = RenderedWordLayout.narrationForVisibleText(current);
            String base = i + settings.outputPrefix;
            Path image = settings.outputDirectory.resolve(base + ".png");
            Path audio = settings.audioDirectory.resolve(base + ".wav");
            Path video = settings.videoDirectory.resolve(base + ".mp4");
            XThreadGenerator renderer = new XThreadGenerator(
                    base, author, handle, identity.profileImage(), current,
                    i, total, 0, 0, 0, 0, settings.outputDirectory, settings);
            jobs.add(new FrameJob(i, narration, image, audio, video, renderer));
        }

        System.out.println("Phase 1/4: rendering X images without synthetic engagement or verification...");
        for (FrameJob job : jobs) {
            job.generator.generateImage();
        }
        if (voicePlan.isEnabled()) {
            System.out.println("Phase 2/4: generating audio with " + settings.ttsEngine + "...");
            for (FrameJob job : jobs) voicePlan.generateSpeech(job.text, job.audioPath, job.index);
        } else {
            System.out.println("Phase 2/4: skipping audio because TTS is disabled.");
        }
        if (settings.createVideo && voicePlan.isEnabled()) {
            System.out.println("Phase 3/4: rendering legacy-compatible video clips...");
            for (FrameJob job : jobs) {
                videoGenerator.makeClip(job.imagePath, job.audioPath, job.videoPath,
                        settings.width, settings.height, settings.videoFps);
                clips.add(job.videoPath);
            }
        } else {
            System.out.println("Phase 3/4: skipping video clips.");
        }
        if (settings.concatVideo && !clips.isEmpty()) {
            Path finalVideo = settings.videoDirectory.resolve(settings.finalVideoName);
            videoGenerator.combineClips(clips, finalVideo);
            System.out.println("Generated final video: " + finalVideo);
        } else {
            System.out.println("Phase 4/4: no final stitch needed.");
        }
    }

    private static String normalizeDisplayName(String value) {
        if (value == null || value.isBlank()) return "X User";
        String cleaned = value.replace('_', ' ').trim();
        return cleaned.length() > 24 ? cleaned.substring(0, 24).trim() : cleaned;
    }

    private static String toHandle(String displayName, Random rand) {
        String base = displayName == null ? "xuser"
                : displayName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (base.isBlank()) base = "xuser";
        if (base.length() > 14) base = base.substring(0, 14);
        return base + (10 + rand.nextInt(990));
    }

    private static void printUsage() {
        System.out.println("Usage: java -cp out redditTxtToImg.XThreadGenerator [comments.txt] [output] [options]");
        System.out.println("Generated X output never fabricates engagement, verification, or precise timestamps.");
        System.out.println("P1 voice options: --voice-series LIST --voice-selection single|series|per-slide --series-id ID --tts-delivery PRESET --tts-speed RATE --tts-language CODE --tts-sentence-pause-ms N");
        System.out.println("P1 identity options: --identity-history-file PATH --identity-history-limit N --no-identity-history");
    }

    private static class FrameJob {
        final int index;
        final String text;
        final Path imagePath;
        final Path audioPath;
        final Path videoPath;
        final XThreadGenerator generator;

        FrameJob(int index, String text, Path imagePath, Path audioPath, Path videoPath, XThreadGenerator generator) {
            this.index = index;
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
        int count = -1;
        int autoTextCount = 10;
        int ttsTimeoutSeconds = 120;
        int ttsSentencePauseMs = 180;
        int identityHistoryLimit = 500;
        int videoTimeoutSeconds = 180;
        int videoFps = 30;
        boolean shuffle = false;
        boolean autoGenerateText = false;
        boolean unloadOllamaAfterText = true;
        boolean listVoices = false;
        boolean createVideo = false;
        boolean concatVideo = false;
        boolean showVerifiedBadge = false;
        boolean identityHistoryEnabled = true;
        boolean voiceExplicit = false;
        boolean ttsSpeedConfigured = false;
        boolean ttsSentencePauseConfigured = false;
        String fontName = "Arial";
        String postTitle = "Finish this story in the comments";
        String outputPrefix = "aithread";
        String topic = "weird everyday stories";
        String llmModel = "llama3.1:8b";
        String ollamaUrl = "http://localhost:11434/api/generate";
        String ttsEngine = "none";
        String ttsCommand = "piper";
        String voiceSeries = "";
        String voiceSelection = "single";
        String seriesId = "";
        String ttsDelivery = "natural";
        String ttsLanguage = "a";
        String videoCommand = "ffmpeg";
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
        Path identityHistoryFile = Path.of("data", "identity_history.jsonl");
        double ttsSpeed = 1.0;

        static Settings fromArgs(String[] args) {
            Settings settings = loadDefaults();
            if (args.length >= 1 && !args[0].startsWith("--")) settings.commentsFile = Path.of(args[0]);
            if (args.length >= 2 && !args[1].startsWith("--")) settings.outputDirectory = Path.of(args[1]);
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
                    settings.voiceExplicit = true;
                    settings.voiceModel = "kokoro".equals(settings.ttsEngine)
                            ? Path.of(voiceValue) : VoiceCatalog.resolveVoice(voiceValue, settings.voiceDirectory);
                }
                else if ("--voice-dir".equals(arg) && i + 1 < args.length) settings.voiceDirectory = Path.of(args[++i]);
                else if ("--voice-series".equals(arg) && i + 1 < args.length) settings.voiceSeries = args[++i];
                else if ("--voice-selection".equals(arg) && i + 1 < args.length) settings.voiceSelection = args[++i];
                else if ("--series-id".equals(arg) && i + 1 < args.length) settings.seriesId = args[++i];
                else if ("--tts-delivery".equals(arg) && i + 1 < args.length) settings.ttsDelivery = args[++i];
                else if ("--tts-speed".equals(arg) && i + 1 < args.length) {
                    settings.ttsSpeed = parseDouble(args[++i], settings.ttsSpeed);
                    settings.ttsSpeedConfigured = true;
                }
                else if ("--tts-language".equals(arg) && i + 1 < args.length) settings.ttsLanguage = args[++i];
                else if ("--tts-sentence-pause-ms".equals(arg) && i + 1 < args.length) {
                    settings.ttsSentencePauseMs = parseInt(args[++i], settings.ttsSentencePauseMs);
                    settings.ttsSentencePauseConfigured = true;
                }
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
                else if ("--identity-history-file".equals(arg) && i + 1 < args.length) settings.identityHistoryFile = Path.of(args[++i]);
                else if ("--identity-history-limit".equals(arg) && i + 1 < args.length) settings.identityHistoryLimit = parseInt(args[++i], settings.identityHistoryLimit);
                else if ("--no-identity-history".equals(arg)) settings.identityHistoryEnabled = false;
            }
            settings.normalizeVoiceForEngine();
            return settings;
        }

        private void normalizeVoiceForEngine() {
            if (voiceExplicit) {
                return;
            }
            String configured = voiceModel == null ? "" : voiceModel.toString();
            if ("kokoro".equalsIgnoreCase(ttsEngine)
                    && (configured.isBlank() || configured.toLowerCase(Locale.ROOT).endsWith(".onnx"))) {
                voiceModel = Path.of("af_heart");
            } else if ("piper".equalsIgnoreCase(ttsEngine)
                    && !configured.toLowerCase(Locale.ROOT).endsWith(".onnx")) {
                voiceModel = VoiceCatalog.resolveVoice(configured, voiceDirectory);
            }
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
                settings.voiceSeries = properties.getProperty("voiceSeries", settings.voiceSeries);
                settings.voiceSelection = properties.getProperty("voiceSelection", settings.voiceSelection);
                settings.seriesId = properties.getProperty("seriesId", settings.seriesId);
                settings.ttsDelivery = properties.getProperty("ttsDelivery", settings.ttsDelivery);
                String configuredSpeed = properties.getProperty("ttsSpeed", "").trim();
                if (!configuredSpeed.isBlank()) {
                    settings.ttsSpeed = parseDouble(configuredSpeed, settings.ttsSpeed);
                    settings.ttsSpeedConfigured = true;
                }
                settings.ttsLanguage = properties.getProperty("ttsLanguage", settings.ttsLanguage);
                String configuredPause = properties.getProperty("ttsSentencePauseMs", "").trim();
                if (!configuredPause.isBlank()) {
                    settings.ttsSentencePauseMs = parseInt(configuredPause, settings.ttsSentencePauseMs);
                    settings.ttsSentencePauseConfigured = true;
                }
                settings.voiceDirectory = Path.of(properties.getProperty("voiceDirectory", settings.voiceDirectory.toString()));
                String defaultVoice = properties.getProperty("voiceModel", settings.voiceModel.toString());
                settings.voiceModel = "kokoro".equals(settings.ttsEngine)
                        ? Path.of(defaultVoice) : VoiceCatalog.resolveVoice(defaultVoice, settings.voiceDirectory);
                settings.audioDirectory = Path.of(properties.getProperty("audioDirectory", settings.audioDirectory.toString()));
                settings.videoDirectory = Path.of(properties.getProperty("videoDirectory", settings.videoDirectory.toString()));
                settings.videoCommand = properties.getProperty("videoCommand", settings.videoCommand);
                settings.finalVideoName = properties.getProperty("finalVideoName", settings.finalVideoName);
                settings.unloadOllamaAfterText = Boolean.parseBoolean(properties.getProperty("unloadOllamaAfterText", "true"));
                settings.identityHistoryFile = Path.of(properties.getProperty(
                        "identityHistoryFile", settings.identityHistoryFile.toString()));
                settings.identityHistoryLimit = parseInt(
                        properties.getProperty("identityHistoryLimit"), settings.identityHistoryLimit);
                settings.identityHistoryEnabled = Boolean.parseBoolean(properties.getProperty(
                        "identityHistoryEnabled", String.valueOf(settings.identityHistoryEnabled)));
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

        private static double parseDouble(String value, double fallback) {
            if (value == null) return fallback;
            try {
                return Double.parseDouble(value.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }
}
