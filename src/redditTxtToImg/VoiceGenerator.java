package redditTxtToImg;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class VoiceGenerator {
    private final String engine;
    private final String command;
    private final Path voiceModel;
    private final int timeoutSeconds;
    private final VoicePlan.Delivery delivery;

    public VoiceGenerator(String engine, String command, Path voiceModel, int timeoutSeconds) {
        this(engine, command, voiceModel, timeoutSeconds,
                VoicePlan.Delivery.resolve("natural", null, "a", null));
    }

    VoiceGenerator(
            String engine,
            String command,
            Path voiceModel,
            int timeoutSeconds,
            VoicePlan.Delivery delivery
    ) {
        this.engine = engine == null ? "none" : engine.toLowerCase(Locale.ROOT);
        this.command = command == null || command.isBlank() ? defaultCommandFor(engine) : command;
        this.voiceModel = voiceModel;
        this.timeoutSeconds = timeoutSeconds <= 0 ? 120 : timeoutSeconds;
        this.delivery = delivery == null
                ? VoicePlan.Delivery.resolve("natural", null, "a", null)
                : delivery;
    }

    private static String defaultCommandFor(String engine) {
        if (engine != null && "kokoro".equalsIgnoreCase(engine.trim())) {
            return "python";
        }
        return "piper";
    }

    public boolean isEnabled() {
        return !"none".equals(engine) && !engine.isBlank();
    }

    public void generateSpeech(String text, Path outputFile) throws IOException, InterruptedException {
        generateSpeech(text, outputFile, voiceModel);
    }

    void generateSpeech(String text, Path outputFile, Path selectedVoice) throws IOException, InterruptedException {
        if (!isEnabled()) {
            return;
        }
        String safeText = text == null ? "" : text.trim();
        if (safeText.isBlank()) {
            throw new IOException("TTS narration is empty for: " + outputFile);
        }
        writeNarrationSidecar(safeText, outputFile);
        if ("piper".equals(engine)) {
            generateWithPiper(safeText, outputFile, selectedVoice);
        } else if ("kokoro".equals(engine)) {
            generateWithKokoro(safeText, outputFile, selectedVoice);
        } else {
            throw new IOException("Unsupported TTS engine: " + engine + ". Supported values: none, piper, kokoro.");
        }
        writeVoiceMetadata(outputFile, selectedVoice);
    }

    private void generateWithPiper(String text, Path outputFile, Path selectedVoice)
            throws IOException, InterruptedException {
        if (selectedVoice == null || selectedVoice.toString().isBlank()) {
            throw new IOException("Piper needs a voice model path. Use --voice path/to/voice.onnx");
        }
        if (!Files.exists(selectedVoice)) {
            throw new IOException("Piper voice model not found: " + selectedVoice
                    + ". Pick a listed voice or download it from the runner first.");
        }

        Path configFile = Path.of(selectedVoice.toString() + ".json");
        if (!Files.exists(configFile)) {
            System.err.println("Warning: Piper voice config not found: " + configFile);
        }

        if (outputFile.getParent() != null) {
            Files.createDirectories(outputFile.getParent());
        }

        List<String> commandParts = new ArrayList<>();
        commandParts.add(resolvePiperCommand(command));
        commandParts.add("--model");
        commandParts.add(selectedVoice.toString());
        commandParts.add("--output_file");
        commandParts.add(outputFile.toString());
        commandParts.add("--length_scale");
        commandParts.add(String.format(Locale.ROOT, "%.4f", 1.0 / delivery.speed()));
        if (delivery.sentencePauseMs() > 0) {
            commandParts.add("--sentence_silence");
            commandParts.add(String.format(Locale.ROOT, "%.3f", delivery.sentencePauseMs() / 1000.0));
        }

        ProcessRunner.runAndCapture(commandParts, "Piper", timeoutSeconds, text + System.lineSeparator());
    }

    private void generateWithKokoro(String text, Path outputFile, Path selectedVoice)
            throws IOException, InterruptedException {
        if (outputFile.getParent() != null) {
            Files.createDirectories(outputFile.getParent());
        }

        Path script = Path.of("tools", "kokoro_tts.py");
        if (!Files.exists(script)) {
            throw new IOException("Kokoro helper script not found: " + script);
        }

        String voiceName = selectedVoice == null || selectedVoice.toString().isBlank()
                ? "af_heart"
                : selectedVoice.toString();

        Path textFile = outputFile.resolveSibling(outputFile.getFileName().toString().replaceAll("\\.wav$", "") + ".txt");

        List<String> commandParts = new ArrayList<>();
        commandParts.add(resolvePythonCommand(command));
        commandParts.add(script.toString());
        commandParts.add("--text-file");
        commandParts.add(textFile.toString());
        commandParts.add("--output");
        commandParts.add(outputFile.toString());
        commandParts.add("--voice");
        commandParts.add(voiceName);
        commandParts.add("--speed");
        commandParts.add(String.format(Locale.ROOT, "%.4f", delivery.speed()));
        commandParts.add("--lang");
        commandParts.add(delivery.language());
        commandParts.add("--sentence-pause-ms");
        commandParts.add(String.valueOf(delivery.sentencePauseMs()));

        System.out.println("Starting Kokoro TTS: " + outputFile);
        ProcessBuilder processBuilder = new ProcessBuilder(commandParts);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        StringBuilder output = new StringBuilder();
        Thread outputThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                    output.append(line).append(System.lineSeparator());
                }
            } catch (IOException e) {
                output.append("Could not read Kokoro output: ").append(e.getMessage()).append(System.lineSeparator());
            }
        });
        outputThread.setDaemon(true);
        outputThread.start();

        int kokoroTimeout = Math.max(timeoutSeconds, 600);
        boolean finished = process.waitFor(kokoroTimeout, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            outputThread.join(1000);
            throw new IOException("Kokoro timed out after " + kokoroTimeout + " seconds. Last output: " + output);
        }

        outputThread.join(1000);
        if (process.exitValue() != 0) {
            throw new IOException("Kokoro failed with exit code " + process.exitValue() + ": " + output);
        }
        if (!Files.exists(outputFile)) {
            throw new IOException("Kokoro finished but did not create WAV: " + outputFile + ". Output: " + output);
        }
    }

    private void writeNarrationSidecar(String text, Path outputFile) throws IOException {
        if (outputFile.getParent() != null) {
            Files.createDirectories(outputFile.getParent());
        }
        Path textFile = outputFile.resolveSibling(
                outputFile.getFileName().toString().replaceAll("\\.wav$", "") + ".txt");
        Files.writeString(textFile, text, StandardCharsets.UTF_8);
    }

    private void writeVoiceMetadata(Path outputFile, Path selectedVoice) throws IOException {
        Path metadataFile = outputFile.resolveSibling(
                outputFile.getFileName().toString().replaceAll("\\.wav$", "") + ".voice.json");
        String json = "{\"engine\":" + JsonText.quote(engine)
                + ",\"voice\":" + JsonText.quote(portableVoiceLabel(selectedVoice))
                + ",\"delivery\":" + JsonText.quote(delivery.preset())
                + ",\"speed\":" + String.format(Locale.ROOT, "%.4f", delivery.speed())
                + ",\"language\":" + JsonText.quote(delivery.language())
                + ",\"sentencePauseMs\":" + delivery.sentencePauseMs() + "}\n";
        Files.writeString(metadataFile, json, StandardCharsets.UTF_8);
    }

    private static String portableVoiceLabel(Path voice) {
        if (voice == null || voice.toString().isBlank()) {
            return "";
        }
        Path absolute = voice.toAbsolutePath().normalize();
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path display = absolute.startsWith(workingDirectory)
                ? workingDirectory.relativize(absolute)
                : voice.getFileName();
        return display == null ? "" : display.toString().replace('\\', '/');
    }

    private String resolvePiperCommand(String configuredCommand) {
        if (configuredCommand == null || configuredCommand.isBlank() || "piper".equalsIgnoreCase(configuredCommand.trim())) {
            Path localPiper = Path.of("piper", "piper.exe");
            if (Files.exists(localPiper)) {
                return localPiper.toString();
            }
        }
        return configuredCommand;
    }

    private String resolvePythonCommand(String configuredCommand) {
        if (configuredCommand == null || configuredCommand.isBlank() || "piper".equalsIgnoreCase(configuredCommand.trim())) {
            return "python";
        }
        return configuredCommand;
    }
}

class VoiceCatalog {
    private VoiceCatalog() {
    }

    static Path resolveVoice(String voiceValue, Path voiceDirectory) {
        if (voiceValue == null || voiceValue.isBlank()) {
            return voiceDirectory.resolve("en_US-lessac-medium.onnx");
        }

        String value = voiceValue.trim();
        Path directPath = Path.of(value);
        boolean looksLikePath = value.contains("/") || value.contains("\\") || value.toLowerCase(Locale.ROOT).endsWith(".onnx");
        if (looksLikePath || Files.exists(directPath)) {
            return directPath;
        }

        String normalized = value.toLowerCase(Locale.ROOT).endsWith(".onnx") ? value : value + ".onnx";
        return voiceDirectory.resolve(normalized);
    }

    static List<String> listVoiceNames(Path voiceDirectory) {
        if (!Files.isDirectory(voiceDirectory)) {
            return Collections.emptyList();
        }

        List<String> voices = new ArrayList<>();
        try (Stream<Path> files = Files.list(voiceDirectory)) {
            files.filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".onnx"))
                    .map(name -> name.substring(0, name.length() - ".onnx".length()))
                    .sorted()
                    .forEach(voices::add);
        } catch (IOException e) {
            System.err.println("Could not list voices in " + voiceDirectory + ": " + e.getMessage());
        }
        return voices;
    }

    static void printVoices(Path voiceDirectory) {
        List<String> voices = listVoiceNames(voiceDirectory);
        if (voices.isEmpty()) {
            System.out.println("No Piper voices found in: " + voiceDirectory);
            System.out.println("Put .onnx voice files there or pass --voice path\\to\\voice.onnx");
        } else {
            System.out.println("Available Piper voices in " + voiceDirectory + ":");
            for (String voice : voices) {
                System.out.println("- " + voice);
            }
        }
        System.out.println();
        System.out.println("Common Kokoro voices: af_heart, af_bella, af_nicole, am_adam, am_michael, bf_emma, bm_george");
    }
}

class VideoGenerator {
    private static final double TRANSITION_SECONDS = 0.35;
    private static final double END_PAUSE_SECONDS = 0.70;
    private static final double FADE_IN_SECONDS = 0.08;
    private static final double FADE_OUT_SECONDS = 0.14;

    private final String command;
    private final int timeoutSeconds;

    VideoGenerator(String command, int timeoutSeconds) {
        this.command = command == null || command.isBlank() ? "ff" + "mpeg" : command;
        this.timeoutSeconds = timeoutSeconds <= 0 ? 180 : timeoutSeconds;
    }

    Path makeClip(Path imageFile, Path audioFile, Path outputFile, int width, int height, int fps)
            throws IOException, InterruptedException {
        if (outputFile.getParent() != null) {
            Files.createDirectories(outputFile.getParent());
        }

        double audioDuration = probeDurationSeconds(audioFile);
        double outputDuration = audioDuration + END_PAUSE_SECONDS;
        double fadeOutStart = Math.max(0.0, audioDuration - FADE_OUT_SECONDS);
        String audioFilter = "afade=t=in:st=0:d=" + formatSeconds(FADE_IN_SECONDS)
                + ",afade=t=out:st=" + formatSeconds(fadeOutStart) + ":d=" + formatSeconds(FADE_OUT_SECONDS)
                + ",apad=pad_dur=" + formatSeconds(END_PAUSE_SECONDS);

        List<String> parts = new ArrayList<>();
        parts.add(command);
        parts.add("-y");
        parts.add("-loop");
        parts.add("1");
        parts.add("-framerate");
        parts.add(String.valueOf(fps));
        parts.add("-i");
        parts.add(imageFile.toString());
        parts.add("-i");
        parts.add(audioFile.toString());
        parts.add("-t");
        parts.add(formatSeconds(outputDuration));
        parts.add("-vf");
        parts.add("scale=" + width + ":" + height);
        parts.add("-af");
        parts.add(audioFilter);
        parts.add("-c:v");
        parts.add("libx264");
        parts.add("-c:a");
        parts.add("aac");
        parts.add("-pix_fmt");
        parts.add("yuv420p");
        parts.add(outputFile.toString());

        run(parts, "video render");
        return outputFile;
    }

    Path combineClips(List<Path> clipFiles, Path outputFile) throws IOException, InterruptedException {
        List<Path> existingClips = new ArrayList<>();
        for (Path clipFile : clipFiles) {
            if (Files.exists(clipFile)) {
                existingClips.add(clipFile);
            }
        }
        if (existingClips.isEmpty()) {
            throw new IOException("No video clips found to stitch.");
        }
        if (existingClips.size() == 1) {
            reencodeSingleClip(existingClips.get(0), outputFile);
            return outputFile;
        }

        List<Double> durations = new ArrayList<>();
        for (Path clipFile : existingClips) {
            double duration = probeDurationSeconds(clipFile);
            if (duration <= TRANSITION_SECONDS + 0.10) {
                combineClipsWithConcat(existingClips, outputFile);
                return outputFile;
            }
            durations.add(duration);
        }

        combineClipsWithCrossfade(existingClips, durations, outputFile);
        return outputFile;
    }

    private void combineClipsWithCrossfade(List<Path> clipFiles, List<Double> durations, Path outputFile)
            throws IOException, InterruptedException {
        if (outputFile.getParent() != null) {
            Files.createDirectories(outputFile.getParent());
        }

        List<String> parts = new ArrayList<>();
        parts.add(command);
        parts.add("-y");
        for (Path clipFile : clipFiles) {
            parts.add("-i");
            parts.add(clipFile.toString());
        }

        StringBuilder filter = new StringBuilder();
        String previousVideo = "[0:v]";
        String previousAudio = "[0:a]";
        double runningDuration = durations.get(0);

        for (int i = 1; i < clipFiles.size(); i++) {
            boolean last = i == clipFiles.size() - 1;
            String videoOut = last ? "vout" : "v" + i;
            String audioOut = last ? "aout" : "a" + i;
            double offset = Math.max(0.01, runningDuration - TRANSITION_SECONDS);

            filter.append(previousVideo)
                    .append("[").append(i).append(":v]")
                    .append("xfade=transition=fade:duration=").append(formatSeconds(TRANSITION_SECONDS))
                    .append(":offset=").append(formatSeconds(offset))
                    .append("[").append(videoOut).append("];");

            filter.append(previousAudio)
                    .append("[").append(i).append(":a]")
                    .append("acrossfade=d=").append(formatSeconds(TRANSITION_SECONDS))
                    .append(":c1=tri:c2=tri")
                    .append("[").append(audioOut).append("];");

            previousVideo = "[" + videoOut + "]";
            previousAudio = "[" + audioOut + "]";
            runningDuration = runningDuration + durations.get(i) - TRANSITION_SECONDS;
        }

        parts.add("-filter_complex");
        parts.add(filter.toString());
        parts.add("-map");
        parts.add("[vout]");
        parts.add("-map");
        parts.add("[aout]");
        parts.add("-c:v");
        parts.add("libx264");
        parts.add("-c:a");
        parts.add("aac");
        parts.add("-pix_fmt");
        parts.add("yuv420p");
        parts.add("-movflags");
        parts.add("+faststart");
        parts.add(outputFile.toString());

        run(parts, "video stitch with transitions");
    }

    private void combineClipsWithConcat(List<Path> clipFiles, Path outputFile) throws IOException, InterruptedException {
        if (outputFile.getParent() != null) {
            Files.createDirectories(outputFile.getParent());
        }
        Path listFile = outputFile.getParent().resolve("video_list.txt");
        List<String> lines = new ArrayList<>();
        for (Path clipFile : clipFiles) {
            lines.add("file '" + clipFile.toAbsolutePath().toString().replace("\\", "/") + "'");
        }
        Files.write(listFile, lines, StandardCharsets.UTF_8);

        List<String> parts = new ArrayList<>();
        parts.add(command);
        parts.add("-y");
        parts.add("-f");
        parts.add("concat");
        parts.add("-safe");
        parts.add("0");
        parts.add("-i");
        parts.add(listFile.toString());
        parts.add("-c:v");
        parts.add("libx264");
        parts.add("-c:a");
        parts.add("aac");
        parts.add("-pix_fmt");
        parts.add("yuv420p");
        parts.add("-movflags");
        parts.add("+faststart");
        parts.add(outputFile.toString());

        run(parts, "video combine");
    }

    private void reencodeSingleClip(Path clipFile, Path outputFile) throws IOException, InterruptedException {
        if (outputFile.getParent() != null) {
            Files.createDirectories(outputFile.getParent());
        }
        List<String> parts = new ArrayList<>();
        parts.add(command);
        parts.add("-y");
        parts.add("-i");
        parts.add(clipFile.toString());
        parts.add("-c:v");
        parts.add("libx264");
        parts.add("-c:a");
        parts.add("aac");
        parts.add("-pix_fmt");
        parts.add("yuv420p");
        parts.add("-movflags");
        parts.add("+faststart");
        parts.add(outputFile.toString());
        run(parts, "single video finalize");
    }

    private double probeDurationSeconds(Path mediaFile) throws IOException, InterruptedException {
        List<String> parts = new ArrayList<>();
        parts.add(resolveProbeCommand());
        parts.add("-v");
        parts.add("error");
        parts.add("-show_entries");
        parts.add("format=duration");
        parts.add("-of");
        parts.add("default=noprint_wrappers=1:nokey=1");
        parts.add(mediaFile.toString());

        String output = runAndCapture(parts, "duration probe").trim();
        try {
            return Double.parseDouble(output);
        } catch (NumberFormatException e) {
            throw new IOException("Could not read duration for " + mediaFile + ": " + output, e);
        }
    }

    private String resolveProbeCommand() {
        String lower = command.toLowerCase(Locale.ROOT);
        if (lower.endsWith("ffmpeg.exe")) {
            return command.substring(0, command.length() - "ffmpeg.exe".length()) + "ffprobe.exe";
        }
        if (lower.endsWith("ffmpeg")) {
            return command.substring(0, command.length() - "ffmpeg".length()) + "ffprobe";
        }
        return "ffprobe";
    }

    private String formatSeconds(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }

    private void run(List<String> parts, String label) throws IOException, InterruptedException {
        runAndCapture(parts, label);
    }

    private String runAndCapture(List<String> parts, String label) throws IOException, InterruptedException {
        return ProcessRunner.runAndCapture(parts, label, timeoutSeconds);
    }
}
