package redditTxtToImg;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * X platform adapter.
 *
 * X has no visible title, so --auto uses an X-specific prompt generator first and then hands the
 * generated script file to XThreadGenerator for normal image/audio/video rendering.
 */
public class XPlatformRunner {
    public static void main(String[] args) {
        try {
            XThreadGenerator.main(prepareArgs(args));
        } catch (Exception e) {
            System.err.println("Failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    static String[] prepareArgs(String[] args) throws IOException, InterruptedException {
        Settings settings = Settings.fromArgs(args);
        if (!settings.autoGenerateText) {
            return args == null ? new String[0] : args.clone();
        }

        int requestedCount = settings.count > -1 ? settings.count : 10;
        System.out.println("X visible original post: " + settings.originalPost);
        if (settings.replyInstruction != null && !settings.replyInstruction.isBlank()) {
            System.out.println("X hidden reply style: " + settings.replyInstruction);
        }

        XLocalLlmTextGenerator generator = new XLocalLlmTextGenerator(settings.ollamaUrl, settings.llmModel);
        Path generatedFile = generator.generateToFile(settings.replyInstruction, settings.originalPost, requestedCount, settings.generatedTextFile);
        System.out.println("Generated X script: " + generatedFile);
        if (settings.unloadOllamaAfterText) {
            generator.unloadModel();
        }

        return buildRendererArgs(args, generatedFile, settings.outputDirectory);
    }

    private static String[] buildRendererArgs(String[] args, Path generatedFile, Path outputDirectory) {
        List<String> rebuilt = new ArrayList<>();
        rebuilt.add(generatedFile.toString());
        rebuilt.add(outputDirectory.toString());
        rebuilt.add("--post-title");
        rebuilt.add("");

        int positional = 0;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null || arg.isBlank()) {
                continue;
            }

            if (arg.startsWith("--")) {
                if ("--auto".equals(arg) || "--keep-ollama-loaded".equals(arg)) {
                    continue;
                }
                if (isLlmOnlyValueOption(arg)) {
                    i++;
                    continue;
                }
                rebuilt.add(arg);
                if (hasValue(arg) && i + 1 < args.length) {
                    rebuilt.add(args[++i]);
                }
                continue;
            }

            positional++;
        }

        return rebuilt.toArray(new String[0]);
    }

    private static boolean isLlmOnlyValueOption(String arg) {
        return "--post-title".equals(arg)
                || "--topic".equals(arg)
                || "--llm-model".equals(arg)
                || "--llm-url".equals(arg)
                || "--script-out".equals(arg);
    }

    private static boolean hasValue(String arg) {
        return "--count".equals(arg)
                || "--prefix".equals(arg)
                || "--style".equals(arg)
                || "--names".equals(arg)
                || "--profiles".equals(arg)
                || "--tts".equals(arg)
                || "--voice".equals(arg)
                || "--voice-dir".equals(arg)
                || "--tts-command".equals(arg)
                || "--audio-dir".equals(arg)
                || "--tts-timeout".equals(arg)
                || "--video-dir".equals(arg)
                || "--video-command".equals(arg)
                || "--fps".equals(arg)
                || "--video-timeout".equals(arg)
                || "--final-video".equals(arg);
    }

    private static class Settings {
        Path commentsFile = Path.of("data", "comments.txt");
        Path outputDirectory = Path.of("output");
        Path generatedTextFile = Path.of("output", "script", "generated_comments.txt");
        String replyInstruction = "";
        String originalPost = "I just saw something weird and I need someone else to explain it.";
        String llmModel = "llama3.1:8b";
        String ollamaUrl = "http://localhost:11434/api/generate";
        int count = -1;
        boolean autoGenerateText = false;
        boolean unloadOllamaAfterText = true;

        static Settings fromArgs(String[] args) {
            Settings settings = new Settings();
            int positional = 0;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg == null || arg.isBlank()) {
                    continue;
                }
                if (arg.startsWith("--")) {
                    if ("--auto".equals(arg)) {
                        settings.autoGenerateText = true;
                    } else if ("--keep-ollama-loaded".equals(arg)) {
                        settings.unloadOllamaAfterText = false;
                    } else if ("--post-title".equals(arg) && i + 1 < args.length) {
                        settings.replyInstruction = normalizeReplyInstruction(args[++i]);
                    } else if ("--topic".equals(arg) && i + 1 < args.length) {
                        settings.originalPost = normalizeOriginalPost(args[++i]);
                    } else if ("--llm-model".equals(arg) && i + 1 < args.length) {
                        settings.llmModel = args[++i];
                    } else if ("--llm-url".equals(arg) && i + 1 < args.length) {
                        settings.ollamaUrl = args[++i];
                    } else if ("--script-out".equals(arg) && i + 1 < args.length) {
                        settings.generatedTextFile = Path.of(args[++i]);
                    } else if ("--count".equals(arg) && i + 1 < args.length) {
                        settings.count = parseInt(args[++i], settings.count);
                    } else if (hasValue(arg) && i + 1 < args.length) {
                        i++;
                    }
                    continue;
                }

                if (positional == 0) {
                    settings.commentsFile = Path.of(arg);
                } else if (positional == 1) {
                    settings.outputDirectory = Path.of(arg);
                }
                positional++;
            }
            return settings;
        }

        private static String normalizeReplyInstruction(String value) {
            if (value == null || value.isBlank()) {
                return "";
            }
            String cleaned = value.trim();
            if ("Finish this story in the comments".equalsIgnoreCase(cleaned)) {
                return "Finish this story in the replies";
            }
            return cleaned;
        }

        private static String normalizeOriginalPost(String value) {
            if (value == null || value.isBlank()) {
                return "I just saw something weird and I need someone else to explain it.";
            }
            String cleaned = value.trim();
            cleaned = cleaned.replaceAll("(?i)^finish this story in the comments\\s*[:.-]?\\s*", "").trim();
            cleaned = cleaned.replaceAll("(?i)^finish this story in the replies\\s*[:.-]?\\s*", "").trim();
            return cleaned.isBlank() ? "I just saw something weird and I need someone else to explain it." : cleaned;
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
    }
}
