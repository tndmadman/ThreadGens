package redditTxtToImg;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Qwen3-TTS backend that talks to the persistent local Python/CUDA service. */
final class Qwen3VoiceGenerator extends VoiceGenerator {
    private final String command;
    private final int timeoutSeconds;
    private final VoicePlan.Delivery delivery;

    Qwen3VoiceGenerator(String command, Path voiceModel, int timeoutSeconds, VoicePlan.Delivery delivery) {
        super("none", command, voiceModel, timeoutSeconds, delivery);
        this.command = command == null || command.isBlank() ? "python" : command;
        this.timeoutSeconds = timeoutSeconds <= 0 ? 120 : timeoutSeconds;
        this.delivery = delivery == null
                ? VoicePlan.Delivery.resolve("natural", null, "a", null)
                : delivery;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void generateSpeech(String text, Path outputFile) throws IOException, InterruptedException {
        generateSpeech(text, outputFile, Path.of("Ryan"));
    }

    @Override
    void generateSpeech(String text, Path outputFile, Path selectedVoice) throws IOException, InterruptedException {
        String safeText = text == null ? "" : text.trim();
        if (safeText.isBlank()) {
            throw new IOException("TTS narration is empty for: " + outputFile);
        }
        if (outputFile.getParent() != null) {
            Files.createDirectories(outputFile.getParent());
        }

        Path script = Path.of("tools", "qwen3_tts.py");
        if (!Files.isRegularFile(script)) {
            throw new IOException("Qwen3-TTS helper script not found: " + script);
        }

        String voiceName = selectedVoice == null || selectedVoice.toString().isBlank()
                ? "Ryan"
                : selectedVoice.toString();
        Path textFile = outputFile.resolveSibling(
                outputFile.getFileName().toString().replaceAll("\\.wav$", "") + ".txt");
        Files.writeString(textFile, safeText, StandardCharsets.UTF_8);

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
        commandParts.add("--delivery");
        commandParts.add(delivery.preset());

        System.out.println("Starting Qwen3-TTS: " + outputFile + " [" + voiceName + "]");
        ProcessBuilder processBuilder = new ProcessBuilder(commandParts);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        StringBuilder output = new StringBuilder();
        Thread outputThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                    output.append(line).append(System.lineSeparator());
                }
            } catch (IOException e) {
                output.append("Could not read Qwen3-TTS output: ")
                        .append(e.getMessage()).append(System.lineSeparator());
            }
        });
        outputThread.setDaemon(true);
        outputThread.start();

        // First use may need to load/download the 1.7B model. Subsequent calls
        // are fast because qwen3_tts.py keeps a persistent localhost service.
        int qwenTimeout = Math.max(timeoutSeconds, 1800);
        boolean finished = process.waitFor(qwenTimeout, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            outputThread.join(1000);
            throw new IOException("Qwen3-TTS timed out after " + qwenTimeout
                    + " seconds. Last output: " + output);
        }

        outputThread.join(1000);
        if (process.exitValue() != 0) {
            throw new IOException("Qwen3-TTS failed with exit code " + process.exitValue() + ": " + output);
        }
        if (!Files.isRegularFile(outputFile)) {
            throw new IOException("Qwen3-TTS finished but did not create WAV: "
                    + outputFile + ". Output: " + output);
        }
        writeVoiceMetadata(outputFile, voiceName);
    }

    private void writeVoiceMetadata(Path outputFile, String voiceName) throws IOException {
        Path metadataFile = outputFile.resolveSibling(
                outputFile.getFileName().toString().replaceAll("\\.wav$", "") + ".voice.json");
        String json = "{\"engine\":\"qwen3\""
                + ",\"voice\":" + JsonText.quote(voiceName)
                + ",\"delivery\":" + JsonText.quote(delivery.preset())
                + ",\"speed\":" + String.format(Locale.ROOT, "%.4f", delivery.speed())
                + ",\"language\":" + JsonText.quote(delivery.language())
                + ",\"sentencePauseMs\":" + delivery.sentencePauseMs() + "}\n";
        Files.writeString(metadataFile, json, StandardCharsets.UTF_8);
    }

    private static String resolvePythonCommand(String configuredCommand) {
        if (configuredCommand == null || configuredCommand.isBlank()
                || "piper".equalsIgnoreCase(configuredCommand.trim())) {
            return "python";
        }
        return configuredCommand;
    }
}
