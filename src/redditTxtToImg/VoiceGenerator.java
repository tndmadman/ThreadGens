package redditTxtToImg;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

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
        commandParts.add(command);
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
}
