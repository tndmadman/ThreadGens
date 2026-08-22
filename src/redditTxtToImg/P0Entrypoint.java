package redditTxtToImg;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * User-facing production entry point. It owns format-aware auto text generation
 * so hidden format/novelty instructions can never leak into the visible OP.
 */
public final class P0Entrypoint {
    private P0Entrypoint() {
    }

    public static void main(String[] args) {
        try {
            runOrThrow(args == null ? new String[0] : args);
        } catch (Exception e) {
            System.err.println("ThreadGens P0 failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    static void runOrThrow(String[] args) throws IOException, InterruptedException {
        String[] safeArgs = args == null ? new String[0] : args.clone();
        if (contains(safeArgs, "--list-voices") || contains(safeArgs, "--gui")) {
            P0Runner.runOrThrow(protectManualScriptInput(safeArgs));
            return;
        }

        P0Runner.RunConfig initialConfig = P0Runner.RunConfig.fromArgs(safeArgs);
        if ("x".equals(initialConfig.platform) && !hasValueOption(safeArgs, "--post-title")) {
            safeArgs = appendValueOption(safeArgs, "--post-title", "");
            initialConfig.postTitle = "";
        }

        if (!contains(safeArgs, "--auto")) {
            P0Runner.RunConfig manualConfig = initialConfig;
            NoveltyGuard manualHistory = new NoveltyGuard(
                    manualConfig.historyFile, manualConfig.noveltyThreshold, manualConfig.historyLimit);
            ContentFormat manualFormat = FormatSelector.resolve(
                    manualConfig.requestedFormat,
                    manualHistory,
                    manualConfig.postTitle,
                    manualConfig.topic + " " + manualConfig.readCurrentScript());
            P0Runner.runOrThrow(applyResolvedFormat(
                    protectManualScriptInput(safeArgs), manualFormat));
            return;
        }

        P0Runner.RunConfig config = initialConfig;
        AutoSettings auto = AutoSettings.fromArgs(safeArgs);
        NoveltyGuard guard = new NoveltyGuard(
                config.historyFile, config.noveltyThreshold, config.historyLimit);
        ContentFormat format = FormatSelector.resolve(
                config.requestedFormat, guard, config.postTitle, config.topic);
        FormatAwareTextGenerator generator = new FormatAwareTextGenerator(auto.ollamaUrl, auto.llmModel);

        // Always validate an existing history file when novelty is enabled. Even
        // if semantic comparison is intentionally disabled, corrupt history must
        // never silently look like a clean slate during automatic generation.
        List<String> validatedHistory = config.noveltyEnabled
                ? SemanticNoveltyGuard.loadRecentScripts(config.historyFile, auto.semanticHistoryLimit)
                : List.of();
        SemanticNoveltyGuard semanticGuard = null;
        if (config.noveltyEnabled && auto.semanticNoveltyEnabled) {
            semanticGuard = new SemanticNoveltyGuard(
                    auto.ollamaUrl, auto.embeddingModel, auto.semanticThreshold);
            System.out.println("P0 semantic novelty: enabled with " + auto.embeddingModel
                    + " against " + validatedHistory.size() + " recent scripts.");
        }

        int requestedCount = config.count >= 0 ? config.count : 10;
        P0Runner.clearVideoOutputs(config, requestedCount);
        int maxAttempts = config.noveltyEnabled ? Math.max(1, config.noveltyRetries + 1) : 1;
        String feedback = "";
        NoveltyGuard.Result deterministicResult = null;
        SemanticNoveltyGuard.Result semanticResult = null;

        try {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                System.out.println("P0 hidden-prompt generation attempt " + attempt + "/" + maxAttempts
                        + " using format " + format.id());
                generator.generateToFile(
                        config.platform,
                        config.postTitle,
                        config.topic,
                        requestedCount,
                        format,
                        feedback,
                        config.scriptOut
                );

                if (!config.noveltyEnabled) {
                    break;
                }

                String candidate = Files.readString(config.scriptOut);
                deterministicResult = guard.assess(candidate);
                printNovelty(deterministicResult);

                String rejectionFeedback = "";
                if (!deterministicResult.accepted()) {
                    rejectionFeedback = deterministicResult.feedbackForRegeneration();
                } else if (semanticGuard != null && !validatedHistory.isEmpty()) {
                    semanticResult = semanticGuard.assess(candidate, validatedHistory);
                    printSemanticNovelty(semanticResult);
                    if (!semanticResult.accepted()) {
                        rejectionFeedback = semanticResult.feedbackForRegeneration();
                    }
                }

                if (rejectionFeedback.isBlank()) {
                    break;
                }
                if (attempt >= maxAttempts) {
                    String details = !deterministicResult.accepted()
                            ? "Deterministic score: " + deterministicResult.noveltyScore() + "/100. "
                                    + String.join(" ", deterministicResult.reasons())
                            : semanticResult == null ? "" : semanticResult.reason();
                    throw new IOException(
                            "Novelty guard rejected all " + maxAttempts + " generated candidates. " + details);
                }
                feedback = rejectionFeedback;
                System.out.println("P0 novelty: regenerating with explicit anti-repeat feedback.");
            }
        } finally {
            if (!auto.keepOllamaLoaded) {
                generator.unloadModel();
            }
        }

        String[] renderedArgs = prepareGeneratedScriptArgs(safeArgs, config.scriptOut, format);
        P0Runner.runOrThrow(renderedArgs);
    }

    static String[] applyResolvedFormat(String[] args, ContentFormat format) {
        List<String> result = new ArrayList<>();
        boolean written = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--format".equals(arg)) {
                if (i + 1 < args.length) {
                    i++;
                }
                result.add("--format");
                result.add(format.id());
                written = true;
                continue;
            }
            result.add(arg);
        }
        if (!written) {
            result.add("--format");
            result.add(format.id());
        }
        return result.toArray(new String[0]);
    }

    static String[] prepareGeneratedScriptArgs(String[] args, Path generatedScript, ContentFormat format) {
        List<String> result = new ArrayList<>();
        boolean replacedFirstPositional = false;
        boolean formatWritten = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--auto".equals(arg)) {
                continue;
            }
            if ("--format".equals(arg)) {
                if (i + 1 < args.length) {
                    i++;
                }
                result.add("--format");
                result.add(format.id());
                formatWritten = true;
                continue;
            }
            if (arg != null && arg.startsWith("--")) {
                result.add(arg);
                if (CliOptions.isValueOption(arg) || isP0ValueOption(arg)) {
                    if (i + 1 < args.length) {
                        result.add(args[++i]);
                    }
                }
                continue;
            }
            if (!replacedFirstPositional) {
                result.add(generatedScript.toString());
                replacedFirstPositional = true;
            } else {
                result.add(arg);
            }
        }

        if (!replacedFirstPositional) {
            result.add(0, generatedScript.toString());
        }
        if (!formatWritten) {
            result.add("--format");
            result.add(format.id());
        }
        return result.toArray(new String[0]);
    }

    static String[] protectManualScriptInput(String[] args) {
        if (contains(args, "--auto")) {
            return args.clone();
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--script-out".equals(arg)) {
                result.add(arg);
                if (i + 1 < args.length) {
                    i++;
                }
                result.add(Path.of("output", ".p0-manual-unused", "generated_comments.txt").toString());
                continue;
            }
            result.add(arg);
        }
        if (!contains(args, "--script-out")) {
            result.add("--script-out");
            result.add(Path.of("output", ".p0-manual-unused", "generated_comments.txt").toString());
        }
        return result.toArray(new String[0]);
    }

    private static boolean hasValueOption(String[] args, String option) {
        for (int i = 0; i < args.length; i++) {
            if (option.equals(args[i])) {
                return i + 1 < args.length;
            }
        }
        return false;
    }

    private static String[] appendValueOption(String[] args, String option, String value) {
        List<String> result = new ArrayList<>(List.of(args));
        result.add(option);
        result.add(value == null ? "" : value);
        return result.toArray(new String[0]);
    }

    private static void printNovelty(NoveltyGuard.Result result) {
        System.out.println("P0 pre-render novelty score: " + result.noveltyScore() + "/100");
        for (String reason : result.reasons()) {
            System.out.println("  - " + reason);
        }
    }

    private static void printSemanticNovelty(SemanticNoveltyGuard.Result result) {
        System.out.println("P0 semantic similarity: "
                + String.format(Locale.US, "%.0f%%", result.highestSimilarity() * 100.0));
        System.out.println("  - " + result.reason());
        if (!result.closestExcerpt().isBlank()) {
            System.out.println("  - Closest semantic match: " + result.closestExcerpt());
        }
    }

    private static boolean isP0ValueOption(String arg) {
        return "--format".equals(arg)
                || "--history-file".equals(arg)
                || "--history-limit".equals(arg)
                || "--novelty-threshold".equals(arg)
                || "--novelty-retries".equals(arg)
                || "--embedding-model".equals(arg)
                || "--semantic-threshold".equals(arg)
                || "--semantic-history-limit".equals(arg);
    }

    private static boolean contains(String[] args, String value) {
        for (String arg : args) {
            if (value.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static final class AutoSettings {
        String llmModel = "llama3.1:8b";
        String ollamaUrl = "http://localhost:11434/api/generate";
        String embeddingModel = SemanticNoveltyGuard.DEFAULT_MODEL;
        double semanticThreshold = SemanticNoveltyGuard.DEFAULT_THRESHOLD;
        int semanticHistoryLimit = SemanticNoveltyGuard.DEFAULT_HISTORY_LIMIT;
        boolean semanticNoveltyEnabled = true;
        boolean keepOllamaLoaded = false;

        static AutoSettings fromArgs(String[] args) throws IOException {
            AutoSettings settings = loadDefaults();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--llm-model".equals(arg) && i + 1 < args.length) {
                    settings.llmModel = args[++i];
                } else if ("--llm-url".equals(arg) && i + 1 < args.length) {
                    settings.ollamaUrl = args[++i];
                } else if ("--embedding-model".equals(arg) && i + 1 < args.length) {
                    settings.embeddingModel = args[++i];
                } else if ("--semantic-threshold".equals(arg) && i + 1 < args.length) {
                    settings.semanticThreshold = parseDouble(args[++i], settings.semanticThreshold);
                } else if ("--semantic-history-limit".equals(arg) && i + 1 < args.length) {
                    settings.semanticHistoryLimit = parseInt(args[++i], settings.semanticHistoryLimit);
                } else if ("--no-semantic-novelty".equals(arg)) {
                    settings.semanticNoveltyEnabled = false;
                } else if ("--keep-ollama-loaded".equals(arg)) {
                    settings.keepOllamaLoaded = true;
                }
            }
            return settings;
        }

        private static AutoSettings loadDefaults() throws IOException {
            AutoSettings settings = new AutoSettings();
            Path defaults = Path.of("defaults.txt");
            if (!Files.exists(defaults)) {
                return settings;
            }
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(defaults)) {
                properties.load(input);
            }
            settings.llmModel = properties.getProperty("llmModel", settings.llmModel);
            settings.ollamaUrl = properties.getProperty("ollamaUrl", settings.ollamaUrl);
            settings.embeddingModel = properties.getProperty("embeddingModel", settings.embeddingModel);
            settings.semanticThreshold = parseDouble(
                    properties.getProperty("semanticThreshold"), settings.semanticThreshold);
            settings.semanticHistoryLimit = parseInt(
                    properties.getProperty("semanticHistoryLimit"), settings.semanticHistoryLimit);
            settings.semanticNoveltyEnabled = Boolean.parseBoolean(
                    properties.getProperty("semanticNoveltyEnabled", String.valueOf(settings.semanticNoveltyEnabled)));
            settings.keepOllamaLoaded = !Boolean.parseBoolean(
                    properties.getProperty("unloadOllamaAfterText", "true").toLowerCase(Locale.ROOT));
            return settings;
        }

        private static int parseInt(String value, int fallback) {
            if (value == null) {
                return fallback;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        private static double parseDouble(String value, double fallback) {
            if (value == null) {
                return fallback;
            }
            try {
                return Double.parseDouble(value.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }
}
