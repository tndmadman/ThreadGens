package redditTxtToImg;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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

    public VoiceGenerator(String engine, String command, Path voiceModel, int timeoutSeconds) {
        this.engine = engine == null ? "none" : engine.toLowerCase(Locale.ROOT);
        this.command = command == null || command.isBlank() ? "piper" : command;
        this.voiceModel = voiceModel;
        this.timeoutSeconds = timeoutSeconds <= 0 ? 120 : timeoutSeconds;
    }

    public boolean isEnabled() {
        return !"none".equals(engine) && !engine.isBlank();
    }

    public void generateSpeech(String text, Path outputFile) throws IOException, InterruptedException {
        if (!isEnabled()) {
            return;
        }
        if ("piper".equals(engine)) {
            generateWithPiper(text, outputFile);
            return;
        }
        throw new IOException("Unsupported TTS engine: " + engine + ". Supported values: none, piper.");
    }

    private void generateWithPiper(String text, Path outputFile) throws IOException, InterruptedException {
        if (voiceModel == null || voiceModel.toString().isBlank()) {
            throw new IOException("Piper needs a voice model path. Use --voice path/to/voice.onnx");
        }
        if (outputFile.getParent() != null) {
            Files.createDirectories(outputFile.getParent());
        }

        List<String> commandParts = new ArrayList<>();
        commandParts.add(resolveCommand(command));
        commandParts.add("--model");
        commandParts.add(voiceModel.toString());
        commandParts.add("--output_file");
        commandParts.add(outputFile.toString());

        ProcessBuilder processBuilder = new ProcessBuilder(commandParts);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(text.getBytes(StandardCharsets.UTF_8));
            stdin.write('\n');
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        process.getInputStream().transferTo(output);

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Piper timed out after " + timeoutSeconds + " seconds.");
        }
        if (process.exitValue() != 0) {
            throw new IOException("Piper failed with exit code " + process.exitValue() + ": " + output.toString(StandardCharsets.UTF_8));
        }
    }

    private String resolveCommand(String configuredCommand) throws IOException {
        if (configuredCommand == null || configuredCommand.isBlank() || "piper".equalsIgnoreCase(configuredCommand.trim())) {
            Path localPiper = Path.of("piper", "piper.exe");
            if (Files.exists(localPiper)) {
                return localPiper.toString();
            }
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
            return;
        }

        System.out.println("Available voices in " + voiceDirectory + ":");
        for (String voice : voices) {
            System.out.println("- " + voice);
        }
    }
}

class VideoGenerator {
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
        parts.add("-c:v");
        parts.add("libx264");
        parts.add("-c:a");
        parts.add("aac");
        parts.add("-pix_fmt");
        parts.add("yuv420p");
        parts.add("-shortest");
        parts.add("-vf");
        parts.add("scale=" + width + ":" + height);
        parts.add(outputFile.toString());

        run(parts, "video render");
        return outputFile;
    }

    Path combineClips(List<Path> clipFiles, Path outputFile) throws IOException, InterruptedException {
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
        return outputFile;
    }

    private void run(List<String> parts, String label) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(parts);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        process.getInputStream().transferTo(output);

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException(label + " timed out after " + timeoutSeconds + " seconds.");
        }
        if (process.exitValue() != 0) {
            throw new IOException(label + " failed with exit code " + process.exitValue() + ": " + output.toString(StandardCharsets.UTF_8));
        }
    }
}
