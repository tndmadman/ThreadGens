package redditTxtToImg;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Final production entry point. P0/P1 own generation, rendering, captions,
 * voices, identities and provenance; P2 runs after they succeed and gates the
 * completed video before it is publish-ready.
 */
public final class P2Entrypoint {
    /*
     * Generation-history format and script fields are controlled values. In
     * particular script_b64 is URL-safe Base64 and can become very large. Use
     * possessive repetition so java.util.regex never recursively backtracks
     * through the full encoded script and exhausts the JVM stack after render.
     */
    private static final Pattern HISTORY_FORMAT = Pattern.compile("\"format\"\\s*:\\s*\"([^\"]++)\"");
    private static final Pattern HISTORY_SCRIPT = Pattern.compile("\"script_b64\"\\s*:\\s*\"([^\"]++)\"");

    private P2Entrypoint() {
    }

    public static void main(String[] args) {
        try {
            runOrThrow(args == null ? new String[0] : args);
        } catch (Exception e) {
            System.err.println("ThreadGens P2 failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static void runOrThrow(String[] args) throws IOException, InterruptedException {
        String[] safeArgs = args == null ? new String[0] : args.clone();
        AuditConfig config = AuditConfig.fromArgs(safeArgs);
        String[] p0Args = config.stripP2Options(safeArgs);

        P0Entrypoint.runOrThrow(p0Args);

        if (config.utilityMode || !config.createVideo || !config.enabled) {
            if (config.createVideo && !config.enabled) {
                System.out.println("P2 publish audit: disabled by explicit configuration.");
            }
            return;
        }

        Path scriptPath = config.autoGenerateText ? config.scriptOut : config.commentsFile;
        String script = Files.exists(scriptPath)
                ? Files.readString(scriptPath, StandardCharsets.UTF_8).trim() : "";
        if (script.isBlank()) {
            throw new IOException("P2 could not read the rendered script for final publish audit: " + scriptPath);
        }

        int count = countNonBlankLines(scriptPath, config.count);
        String actualFormat = resolveActualFormat(config, script);
        List<Path> images = numbered(config.outputDirectory, config.outputPrefix, ".png", count);
        List<Path> audio = numbered(config.audioDirectory, config.outputPrefix, ".wav", count);
        List<Path> artifacts = new ArrayList<>();
        if (config.concatVideo) {
            Path finalVideo = config.videoDirectory.resolve(config.finalVideoName);
            if (Files.isRegularFile(finalVideo)) artifacts.add(finalVideo);
        } else {
            artifacts.addAll(numbered(config.videoDirectory, config.outputPrefix, ".mp4", count));
        }
        if (artifacts.isEmpty()) {
            throw new IOException("P2 did not find the completed video artifact(s) to audit.");
        }

        String metadataSignature = config.metadataSignature(safeArgs);
        PublishFingerprint fingerprint = PublishFingerprint.capture(new PublishFingerprint.CaptureInput(
                config.platform,
                actualFormat,
                script,
                artifacts,
                images,
                audio,
                config.voice,
                config.ttsEngine,
                metadataSignature,
                config.videoCommand
        ));

        PublishAuditHistory history = new PublishAuditHistory(config.publishHistory, config.publishHistoryLimit);
        try (PublishAuditHistory.LockHandle ignored = history.lockExclusive()) {
            List<PublishAuditHistory.Entry> recent = history.load();
            double semanticSimilarity = 0.0;
            if (config.semanticNoveltyEnabled && !recent.isEmpty()) {
                List<String> priorScripts = recent.stream()
                        .map(entry -> entry.fingerprint().script)
                        .filter(value -> value != null && !value.isBlank())
                        .toList();
                if (!priorScripts.isEmpty()) {
                    SemanticNoveltyGuard semantic = new SemanticNoveltyGuard(
                            config.ollamaUrl, config.embeddingModel, 1.0);
                    SemanticNoveltyGuard.Result semanticResult = semantic.assess(script, priorScripts);
                    semanticSimilarity = semanticResult.highestSimilarity();
                    System.out.println(String.format(Locale.US,
                            "P2 semantic premise similarity: %.0f%%", semanticSimilarity * 100.0));
                }
            }

            PrePublishAuditor auditor = new PrePublishAuditor(config.warnThreshold, config.blockThreshold);
            PrePublishAuditor.Result result = auditor.assess(fingerprint, recent, semanticSimilarity);
            PrePublishAuditor.writeReport(config.reportPath, fingerprint, result, config.mode);
            printResult(result, config.reportPath, history.file());

            if (result.blocked() && "block".equals(config.mode)) {
                throw new IOException("P2 pre-publish audit BLOCKED this video at " + result.risk()
                        + "/100 repetition risk. Review " + config.reportPath + ".");
            }

            String recordedStatus = result.blocked() ? "WARN_OVERRIDE" : result.status().name();
            history.record(fingerprint, recordedStatus, result.risk());
            System.out.println("P2 publish audit: approved history recorded as " + recordedStatus + ".");
        }
    }

    private static void printResult(PrePublishAuditor.Result result, Path report, Path history) {
        PrePublishAuditor.Scores s = result.scores();
        System.out.println("P2 pre-publish audit: " + result.status() + " — " + result.risk() + "/100 repetition risk");
        System.out.println(String.format(Locale.US,
                "  content %.0f%% | visual %.0f%% | identity %.0f%% | audio %.0f%% | format %.0f%% | pacing %.0f%% | metadata %.0f%%",
                s.content() * 100.0, s.visual() * 100.0, s.identity() * 100.0, s.audio() * 100.0,
                s.format() * 100.0, s.pacing() * 100.0, s.metadata() * 100.0));
        for (String finding : result.findings()) System.out.println("  - " + finding);
        System.out.println("P2 audit report: " + report);
        System.out.println("P2 publish history: " + history);
    }

    private static String resolveActualFormat(AuditConfig config, String script) throws IOException {
        if (!"auto".equalsIgnoreCase(config.requestedFormat)) return config.requestedFormat;
        if (Files.exists(config.generationHistory)) {
            List<String> lines = Files.readAllLines(config.generationHistory, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i);
                Matcher scriptMatcher = HISTORY_SCRIPT.matcher(line);
                Matcher formatMatcher = HISTORY_FORMAT.matcher(line);
                if (!scriptMatcher.find() || !formatMatcher.find()) continue;
                try {
                    String decoded = new String(
                            Base64.getUrlDecoder().decode(scriptMatcher.group(1)), StandardCharsets.UTF_8).trim();
                    if (decoded.equals(script.trim())) return formatMatcher.group(1);
                } catch (IllegalArgumentException ignored) {
                    // P0 owns strict generation-history validation when novelty is enabled.
                }
            }
        }

        NoveltyGuard formatHistory = new NoveltyGuard(config.generationHistory);
        String selectionTopic = config.autoGenerateText
                ? config.topic
                : config.topic + " " + script;
        return FormatSelector.resolve(
                "auto", formatHistory, config.postTitle, selectionTopic).id();
    }

    private static int countNonBlankLines(Path file, int requested) throws IOException {
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            int available = (int) lines.map(String::trim).filter(v -> !v.isBlank()).count();
            return requested >= 0 ? Math.min(requested, available) : available;
        }
    }

    private static List<Path> numbered(Path dir, String prefix, String suffix, int count) {
        List<Path> paths = new ArrayList<>();
        for (int i = 0; i < Math.max(0, count); i++) {
            Path path = dir.resolve(i + prefix + suffix);
            if (Files.isRegularFile(path)) paths.add(path);
        }
        return paths;
    }

    static final class AuditConfig {
        Path commentsFile = Path.of("data", "comments.txt");
        Path outputDirectory = Path.of("output");
        Path audioDirectory = Path.of("output", "audio");
        Path videoDirectory = Path.of("output", "video");
        Path metadataDirectory = null;
        Path scriptOut = Path.of("output", "script", "generated_comments.txt");
        Path generationHistory = Path.of("data", "generation_history.jsonl");
        Path publishHistory = Path.of("data", "publish_history.jsonl");
        Path reportPath = null;
        List<Path> metadataFiles = new ArrayList<>();

        String platform = "reddit";
        String requestedFormat = "auto";
        String outputPrefix = "aithread";
        String finalVideoName = "final.mp4";
        String videoCommand = "ffmpeg";
        String ttsEngine = "none";
        String voice = "unknown";
        String mode = "block";
        String ollamaUrl = "http://localhost:11434/api/generate";
        String embeddingModel = SemanticNoveltyGuard.DEFAULT_MODEL;
        String postTitle = "Finish this story in the comments";
        String topic = "weird everyday stories";

        int count = -1;
        int publishHistoryLimit = 100;
        int warnThreshold = 58;
        int blockThreshold = 78;
        boolean enabled = true;
        boolean createVideo = false;
        boolean concatVideo = false;
        boolean autoGenerateText = false;
        boolean utilityMode = false;
        boolean semanticNoveltyEnabled = true;
        boolean postTitleExplicit = false;

        static AuditConfig fromArgs(String[] args) throws IOException {
            AuditConfig config = loadDefaults();
            int positional = 0;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg == null || arg.isBlank()) continue;
                if (arg.startsWith("--")) {
                    switch (arg) {
                        case "--list-voices", "--gui" -> config.utilityMode = true;
                        case "--auto" -> config.autoGenerateText = true;
                        case "--video" -> config.createVideo = true;
                        case "--concat-video" -> { config.createVideo = true; config.concatVideo = true; }
                        case "--no-publish-audit" -> config.enabled = false;
                        case "--publish-audit" -> config.enabled = true;
                        case "--no-semantic-novelty" -> config.semanticNoveltyEnabled = false;
                        case "--platform" -> { if (i + 1 < args.length) config.platform = normalizePlatform(args[++i]); }
                        case "--format" -> { if (i + 1 < args.length) config.requestedFormat = args[++i]; }
                        case "--count" -> { if (i + 1 < args.length) config.count = parseInt(args[++i], config.count); }
                        case "--prefix" -> { if (i + 1 < args.length) config.outputPrefix = args[++i]; }
                        case "--post-title" -> {
                            if (i + 1 < args.length) {
                                config.postTitle = args[++i];
                                config.postTitleExplicit = true;
                            }
                        }
                        case "--topic" -> { if (i + 1 < args.length) config.topic = args[++i]; }
                        case "--tts" -> { if (i + 1 < args.length) config.ttsEngine = args[++i]; }
                        case "--voice" -> { if (i + 1 < args.length) config.voice = args[++i]; }
                        case "--audio-dir" -> { if (i + 1 < args.length) config.audioDirectory = Path.of(args[++i]); }
                        case "--video-dir" -> { if (i + 1 < args.length) config.videoDirectory = Path.of(args[++i]); }
                        case "--metadata-dir" -> { if (i + 1 < args.length) config.metadataDirectory = Path.of(args[++i]); }
                        case "--video-command" -> { if (i + 1 < args.length) config.videoCommand = args[++i]; }
                        case "--final-video" -> { if (i + 1 < args.length) config.finalVideoName = args[++i]; }
                        case "--script-out" -> { if (i + 1 < args.length) config.scriptOut = Path.of(args[++i]); }
                        case "--history-file" -> { if (i + 1 < args.length) config.generationHistory = Path.of(args[++i]); }
                        case "--llm-url" -> { if (i + 1 < args.length) config.ollamaUrl = args[++i]; }
                        case "--embedding-model" -> { if (i + 1 < args.length) config.embeddingModel = args[++i]; }
                        case "--publish-history" -> { if (i + 1 < args.length) config.publishHistory = Path.of(args[++i]); }
                        case "--publish-history-limit" -> { if (i + 1 < args.length) config.publishHistoryLimit = parseInt(args[++i], config.publishHistoryLimit); }
                        case "--publish-audit-warn" -> { if (i + 1 < args.length) config.warnThreshold = parseInt(args[++i], config.warnThreshold); }
                        case "--publish-audit-threshold" -> { if (i + 1 < args.length) config.blockThreshold = parseInt(args[++i], config.blockThreshold); }
                        case "--publish-audit-mode" -> { if (i + 1 < args.length) config.mode = normalizeMode(args[++i]); }
                        case "--audit-report" -> { if (i + 1 < args.length) config.reportPath = Path.of(args[++i]); }
                        case "--publish-metadata" -> { if (i + 1 < args.length) config.metadataFiles.add(Path.of(args[++i])); }
                        default -> {
                            if (CliOptions.isValueOption(arg) && i + 1 < args.length) i++;
                        }
                    }
                    continue;
                }
                if (positional == 0) config.commentsFile = Path.of(arg);
                else if (positional == 1) config.outputDirectory = Path.of(arg);
                positional++;
            }
            if (config.metadataDirectory == null) {
                config.metadataDirectory = config.outputDirectory.resolve("metadata");
            }
            if ("x".equals(config.platform) && !config.postTitleExplicit) {
                config.postTitle = "";
            }
            if (config.reportPath == null) config.reportPath = config.videoDirectory.resolve("publish_audit.json");
            return config;
        }

        private static AuditConfig loadDefaults() throws IOException {
            AuditConfig config = new AuditConfig();
            Path defaults = Path.of("defaults.txt");
            if (!Files.exists(defaults)) return config;
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(defaults)) { p.load(in); }
            config.platform = normalizePlatform(p.getProperty("platform", config.platform));
            config.outputPrefix = p.getProperty("prefix", config.outputPrefix);
            config.audioDirectory = Path.of(p.getProperty("audioDirectory", config.audioDirectory.toString()));
            config.videoDirectory = Path.of(p.getProperty("videoDirectory", config.videoDirectory.toString()));
            config.videoCommand = p.getProperty("videoCommand", config.videoCommand);
            config.finalVideoName = p.getProperty("finalVideoName", config.finalVideoName);
            config.ttsEngine = p.getProperty("ttsEngine", config.ttsEngine);
            config.requestedFormat = p.getProperty("format", config.requestedFormat);
            config.generationHistory = Path.of(p.getProperty("historyFile", config.generationHistory.toString()));
            config.ollamaUrl = p.getProperty("ollamaUrl", config.ollamaUrl);
            config.embeddingModel = p.getProperty("embeddingModel", config.embeddingModel);
            config.semanticNoveltyEnabled = Boolean.parseBoolean(
                    p.getProperty("semanticNoveltyEnabled", String.valueOf(config.semanticNoveltyEnabled)));
            config.postTitle = p.getProperty("postTitle", config.postTitle);
            config.topic = p.getProperty("topic", config.topic);
            String metadataDir = p.getProperty("metadataDirectory", "").trim();
            if (!metadataDir.isBlank()) config.metadataDirectory = Path.of(metadataDir);
            config.publishHistory = Path.of(p.getProperty("publishHistoryFile", config.publishHistory.toString()));
            config.publishHistoryLimit = parseInt(p.getProperty("publishHistoryLimit"), config.publishHistoryLimit);
            config.warnThreshold = parseInt(p.getProperty("publishAuditWarnThreshold"), config.warnThreshold);
            config.blockThreshold = parseInt(p.getProperty("publishAuditBlockThreshold"), config.blockThreshold);
            config.enabled = Boolean.parseBoolean(p.getProperty("publishAuditEnabled", String.valueOf(config.enabled)));
            config.mode = normalizeMode(p.getProperty("publishAuditMode", config.mode));
            return config;
        }

        String[] stripP2Options(String[] args) {
            List<String> result = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (isP2Flag(arg)) continue;
                if (isP2ValueOption(arg)) {
                    if (i + 1 < args.length) i++;
                    continue;
                }
                result.add(arg);
            }
            return result.toArray(new String[0]);
        }

        String metadataSignature(String[] args) throws IOException {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg == null) continue;
                if (isP1MetadataValueOption(arg)) {
                    out.append(arg);
                    if (i + 1 < args.length) out.append('=').append(args[++i]);
                    out.append('\n');
                } else if ("--no-provenance-metadata".equals(arg)) {
                    out.append(arg).append('\n');
                }
            }

            for (Path path : metadataFiles) {
                if (path != null && Files.isRegularFile(path)) {
                    out.append(path.getFileName()).append(':')
                            .append(PublishFingerprint.sha256(Files.readAllBytes(path))).append('\n');
                }
            }

            Path manifest = metadataDirectory == null
                    ? null : metadataDirectory.resolve(outputPrefix + "-provenance.json");
            if (manifest == null || !Files.isRegularFile(manifest)) {
                Path adjacent = videoDirectory.resolve(finalVideoName + ".provenance.json");
                if (Files.isRegularFile(adjacent)) manifest = adjacent;
            }
            if (manifest != null && Files.isRegularFile(manifest)) {
                appendStableP1ManifestSignature(out, manifest);
            }

            List<Path> legacyCandidates = List.of(
                    outputDirectory.resolve("production_manifest.json"),
                    videoDirectory.resolve("production_manifest.json"),
                    outputDirectory.resolve("p1_manifest.json"),
                    videoDirectory.resolve("p1_manifest.json"));
            for (Path path : legacyCandidates) {
                if (Files.isRegularFile(path)) {
                    out.append(path.getFileName()).append(':')
                            .append(PublishFingerprint.sha256(Files.readAllBytes(path))).append('\n');
                }
            }
            return out.toString();
        }

        private static void appendStableP1ManifestSignature(StringBuilder out, Path manifest) throws IOException {
            String json = Files.readString(manifest, StandardCharsets.UTF_8);
            out.append("p1-provenance:");
            for (String key : List.of(
                    "schema", "disclosure", "origin", "platform", "format", "llmModel",
                    "engine", "voiceSelection", "voiceSeries", "delivery", "language",
                    "imageMode", "imageCheckpoint", "captions", "captionTiming")) {
                String value = JsonText.extractString(json, key);
                if (value != null) out.append(key).append('=').append(value).append(';');
            }
            for (String key : List.of("speed", "sentencePauseMs", "dynamicSceneChanges")) {
                String value = jsonScalar(json, key);
                if (value != null) out.append(key).append('=').append(value).append(';');
            }
            out.append('\n');
        }

        private static String jsonScalar(String json, String key) {
            Pattern pattern = Pattern.compile("\"" + Pattern.quote(key)
                    + "\"\\s*:\\s*([^,}\\r\\n]+)");
            Matcher matcher = pattern.matcher(json);
            return matcher.find() ? matcher.group(1).trim() : null;
        }

        private static boolean isP1MetadataValueOption(String value) {
            return "--captions".equals(value) || "--caption-words".equals(value)
                    || "--visual-max-scenes".equals(value)
                    || "--voice-series".equals(value) || "--voice-selection".equals(value)
                    || "--series-id".equals(value) || "--tts-delivery".equals(value)
                    || "--tts-speed".equals(value) || "--tts-language".equals(value)
                    || "--tts-sentence-pause-ms".equals(value)
                    || "--identity-history-file".equals(value) || "--identity-history-limit".equals(value)
                    || "--metadata-dir".equals(value) || "--disclosure".equals(value)
                    || "--content-origin".equals(value);
        }

        private static boolean isP2Flag(String value) {
            return "--publish-audit".equals(value) || "--no-publish-audit".equals(value);
        }

        private static boolean isP2ValueOption(String value) {
            return "--publish-history".equals(value) || "--publish-history-limit".equals(value)
                    || "--publish-audit-warn".equals(value) || "--publish-audit-threshold".equals(value)
                    || "--publish-audit-mode".equals(value) || "--audit-report".equals(value)
                    || "--publish-metadata".equals(value);
        }

        private static String normalizeMode(String value) {
            return "warn".equalsIgnoreCase(value) ? "warn" : "block";
        }

        private static String normalizePlatform(String value) {
            if (value == null || value.isBlank()) return "reddit";
            String cleaned = value.trim().toLowerCase(Locale.ROOT);
            return "twitter".equals(cleaned) ? "x" : cleaned;
        }

        private static int parseInt(String value, int fallback) {
            if (value == null) return fallback;
            try { return Integer.parseInt(value.trim()); } catch (NumberFormatException e) { return fallback; }
        }
    }
}
