package redditTxtToImg;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Selects a stable or rotating voice pool and applies one delivery profile. */
final class VoicePlan {
    enum Selection {
        SINGLE,
        SERIES,
        PER_SLIDE;

        static Selection resolve(String value) {
            if (value == null || value.isBlank()) {
                return SINGLE;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
                case "single" -> SINGLE;
                case "series", "per_video" -> SERIES;
                case "per_slide", "rotate", "rotating" -> PER_SLIDE;
                default -> throw new IllegalArgumentException(
                        "Unsupported voice selection: " + value + ". Use single, series, or per-slide.");
            };
        }
    }

    record Delivery(String preset, double speed, String language, int sentencePauseMs) {
        Delivery {
            preset = normalizePreset(preset);
            if (!Double.isFinite(speed) || speed < 0.60 || speed > 1.60) {
                throw new IllegalArgumentException("TTS speed must be between 0.60 and 1.60.");
            }
            language = language == null || language.isBlank() ? "a" : language.trim();
            if (sentencePauseMs < 0 || sentencePauseMs > 2000) {
                throw new IllegalArgumentException("TTS sentence pause must be between 0 and 2000 ms.");
            }
        }

        static Delivery resolve(String preset, Double explicitSpeed, String language, Integer explicitPauseMs) {
            String normalized = normalizePreset(preset);
            double presetSpeed = switch (normalized) {
                case "calm" -> 0.92;
                case "energetic" -> 1.08;
                case "dramatic" -> 0.96;
                default -> 1.0;
            };
            int presetPause = switch (normalized) {
                case "calm" -> 280;
                case "energetic" -> 100;
                case "dramatic" -> 360;
                default -> 180;
            };
            return new Delivery(
                    normalized,
                    explicitSpeed == null ? presetSpeed : explicitSpeed,
                    language,
                    explicitPauseMs == null ? presetPause : explicitPauseMs);
        }

        private static String normalizePreset(String value) {
            String normalized = value == null || value.isBlank()
                    ? "natural"
                    : value.trim().toLowerCase(Locale.ROOT);
            if (!Set.of("natural", "calm", "energetic", "dramatic").contains(normalized)) {
                throw new IllegalArgumentException(
                        "Unsupported TTS delivery: " + value + ". Use natural, calm, energetic, or dramatic.");
            }
            return normalized;
        }
    }

    private final VoiceGenerator generator;
    private final List<Path> voices;
    private final Selection selection;
    private final int seriesVoiceIndex;

    VoicePlan(
            String engine,
            String command,
            Path primaryVoice,
            String voiceSeries,
            Path voiceDirectory,
            String selection,
            String seriesKey,
            Delivery delivery,
            int timeoutSeconds
    ) {
        String configuredEngine = engine == null ? "none" : engine.trim().toLowerCase(Locale.ROOT);
        String effectiveEngine = resolveEngineOverride(configuredEngine);
        String effectiveCommand = resolveCommandOverride(command, configuredEngine, effectiveEngine);
        this.selection = Selection.resolve(selection);
        this.voices = resolveVoices(effectiveEngine, primaryVoice, voiceSeries, voiceDirectory, this.selection);
        String key = seriesKey == null || seriesKey.isBlank() ? "threadgens-default-series" : seriesKey.trim();
        this.seriesVoiceIndex = Math.floorMod(key.hashCode(), voices.size());
        this.generator = isQwenEngine(effectiveEngine)
                ? new Qwen3VoiceGenerator(effectiveCommand, voices.get(0), timeoutSeconds, delivery)
                : new VoiceGenerator(effectiveEngine, effectiveCommand, voices.get(0), timeoutSeconds, delivery);
    }

    boolean isEnabled() {
        return generator.isEnabled();
    }

    Path voiceFor(int slideIndex) {
        return switch (selection) {
            case SINGLE -> voices.get(0);
            case SERIES -> voices.get(seriesVoiceIndex);
            case PER_SLIDE -> voices.get(Math.floorMod(slideIndex, voices.size()));
        };
    }

    void generateSpeech(String text, Path outputFile, int slideIndex) throws IOException, InterruptedException {
        generator.generateSpeech(text, outputFile, voiceFor(slideIndex));
    }

    List<String> voiceLabels() {
        return voices.stream().map(Path::toString).toList();
    }

    Selection selection() {
        return selection;
    }

    private static String resolveEngineOverride(String configuredEngine) {
        String override = System.getenv("THREADGENS_TTS_ENGINE_OVERRIDE");
        if (override == null || override.isBlank()) {
            return configuredEngine;
        }
        String normalized = override.trim().toLowerCase(Locale.ROOT);
        if (isQwenEngine(normalized)) {
            return "qwen3";
        }
        if ("kokoro".equals(normalized) || "piper".equals(normalized) || "none".equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException(
                "Unsupported THREADGENS_TTS_ENGINE_OVERRIDE: " + override
                        + ". Use qwen3, kokoro, piper, or none.");
    }

    private static String resolveCommandOverride(String command, String configuredEngine, String effectiveEngine) {
        if (!isQwenEngine(effectiveEngine) || isQwenEngine(configuredEngine)) {
            return command;
        }
        String explicitPython = System.getenv("THREADGENS_QWEN3_PYTHON");
        if (explicitPython != null && !explicitPython.isBlank()) {
            return explicitPython.trim();
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return Path.of(".venv-qwen3-tts", "Scripts", "python.exe").toString();
        }
        return Path.of(".venv-qwen3-tts", "bin", "python").toString();
    }

    private static List<Path> resolveVoices(
            String engine,
            Path primaryVoice,
            String voiceSeries,
            Path voiceDirectory,
            Selection selection
    ) {
        Set<String> rawVoices = new LinkedHashSet<>();
        if (selection == Selection.SINGLE && primaryVoice != null && !primaryVoice.toString().isBlank()) {
            rawVoices.add(primaryVoice.toString().trim());
        }
        if (selection != Selection.SINGLE && voiceSeries != null && !voiceSeries.isBlank()) {
            for (String value : voiceSeries.split("[,;]")) {
                if (!value.isBlank()) {
                    rawVoices.add(value.trim());
                }
            }
        }
        if (rawVoices.isEmpty() && primaryVoice != null && !primaryVoice.toString().isBlank()) {
            rawVoices.add(primaryVoice.toString().trim());
        }
        if (rawVoices.isEmpty()) {
            if ("kokoro".equalsIgnoreCase(engine)) {
                rawVoices.add("af_heart");
            } else if (isQwenEngine(engine)) {
                rawVoices.add("Ryan");
            } else {
                rawVoices.add("en_US-lessac-medium");
            }
        }

        List<Path> result = new ArrayList<>();
        for (String value : rawVoices) {
            if ("kokoro".equalsIgnoreCase(engine)) {
                result.add(Path.of(value));
            } else if (isQwenEngine(engine)) {
                result.add(Path.of(normalizeQwenVoice(value)));
            } else {
                result.add(VoiceCatalog.resolveVoice(value, voiceDirectory));
            }
        }
        return List.copyOf(result);
    }

    private static boolean isQwenEngine(String engine) {
        return "qwen3".equalsIgnoreCase(engine) || "qwen3-tts".equalsIgnoreCase(engine);
    }

    private static String normalizeQwenVoice(String value) {
        String safe = value == null ? "" : value.trim();
        if (safe.isBlank()) {
            return "Ryan";
        }
        String fileName = Path.of(safe).getFileName().toString();
        if (fileName.toLowerCase(Locale.ROOT).endsWith(".onnx")) {
            fileName = fileName.substring(0, fileName.length() - ".onnx".length());
        }
        // Existing CLI parsing resolves non-Kokoro --voice values as Piper paths.
        // If the inherited default Piper voice leaks through, use Qwen's default
        // English voice instead of sending an invalid speaker name to the model.
        if ("en_us-lessac-medium".equalsIgnoreCase(fileName)) {
            return "Ryan";
        }
        return fileName;
    }
}
