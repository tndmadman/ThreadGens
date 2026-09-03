package redditTxtToImg;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Qwen3-TTS backend that talks directly to the persistent local Python/CUDA service. */
final class Qwen3VoiceGenerator extends VoiceGenerator {
    private static final Object STARTUP_LOCK = new Object();
    private static final String DEFAULT_URL = "http://127.0.0.1:8765";

    private final String command;
    private final int timeoutSeconds;
    private final VoicePlan.Delivery delivery;
    private final String baseUrl;
    private final String workerId;
    private final HttpClient httpClient;

    Qwen3VoiceGenerator(String command, Path voiceModel, int timeoutSeconds, VoicePlan.Delivery delivery) {
        super("none", command, voiceModel, timeoutSeconds, delivery);
        this.command = command == null || command.isBlank() ? "python" : command;
        this.timeoutSeconds = timeoutSeconds <= 0 ? 120 : timeoutSeconds;
        this.delivery = delivery == null
                ? VoicePlan.Delivery.resolve("natural", null, "a", null)
                : delivery;
        String configuredUrl = System.getenv("THREADGENS_QWEN3_URL");
        this.baseUrl = configuredUrl == null || configuredUrl.isBlank()
                ? DEFAULT_URL
                : configuredUrl.trim().replaceAll("/+$", "");
        String configuredWorkerId = System.getenv("THREADGENS_WORKER_ID");
        this.workerId = configuredWorkerId == null || configuredWorkerId.isBlank()
                ? "java-" + ProcessHandle.current().pid()
                : configuredWorkerId.trim();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
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

        String voiceName = selectedVoice == null || selectedVoice.toString().isBlank()
                ? "Ryan"
                : selectedVoice.toString();

        ensureServer();

        String requestId = workerId + "-"
                + outputFile.getFileName().toString().replaceAll("\\.wav$", "")
                + "-" + Long.toUnsignedString(System.nanoTime());
        String json = "{"
                + "\"text\":" + JsonText.quote(safeText)
                + ",\"speaker\":" + JsonText.quote(voiceName)
                + ",\"language\":" + JsonText.quote(delivery.language())
                + ",\"speed\":" + String.format(Locale.ROOT, "%.4f", delivery.speed())
                + ",\"sentence_pause_ms\":" + delivery.sentencePauseMs()
                + ",\"delivery\":" + JsonText.quote(delivery.preset())
                + ",\"worker_id\":" + JsonText.quote(workerId)
                + ",\"request_id\":" + JsonText.quote(requestId)
                + "}";

        System.out.println("Starting Qwen3-TTS: " + outputFile + " [" + voiceName + "] worker=" + workerId);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/synthesize"))
                .timeout(Duration.ofSeconds(Math.max(timeoutSeconds, 1800)))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException firstFailure) {
            // The persistent server may have exited between the health check and
            // synthesis. Bootstrap it once, then retry this request directly.
            ensureServerAfterFailure();
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException retryFailure) {
                retryFailure.addSuppressed(firstFailure);
                throw retryFailure;
            }
        }

        if (response.statusCode() != 200) {
            String body = new String(response.body(), StandardCharsets.UTF_8);
            throw new IOException("Qwen3-TTS synthesis failed with HTTP "
                    + response.statusCode() + ": " + body);
        }
        byte[] audio = response.body();
        if (audio.length < 44
                || audio[0] != 'R'
                || audio[1] != 'I'
                || audio[2] != 'F'
                || audio[3] != 'F') {
            throw new IOException("Qwen3-TTS service returned an invalid WAV payload.");
        }

        Files.write(outputFile, audio);
        writeVoiceMetadata(outputFile, voiceName);
    }

    private void ensureServer() throws IOException, InterruptedException {
        if (isServerHealthy()) {
            return;
        }
        ensureServerAfterFailure();
    }

    private void ensureServerAfterFailure() throws IOException, InterruptedException {
        synchronized (STARTUP_LOCK) {
            if (isServerHealthy()) {
                return;
            }

            Path script = Path.of("tools", "qwen3_tts.py");
            if (!Files.isRegularFile(script)) {
                throw new IOException("Qwen3-TTS helper script not found: " + script);
            }

            ProcessBuilder processBuilder = new ProcessBuilder(
                    resolvePythonCommand(command),
                    script.toString(),
                    "--ensure-server");
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            int startupTimeout = Math.max(timeoutSeconds, 1800);
            boolean finished = process.waitFor(startupTimeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Qwen3-TTS bootstrap timed out after "
                        + startupTimeout + " seconds.");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IOException("Qwen3-TTS bootstrap failed with exit code "
                        + process.exitValue() + ": " + output);
            }
            if (!isServerHealthy()) {
                throw new IOException("Qwen3-TTS bootstrap completed but the service is still unavailable. "
                        + output);
            }
        }
    }

    private boolean isServerHealthy() throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (IOException e) {
            return false;
        }
    }

    private void writeVoiceMetadata(Path outputFile, String voiceName) throws IOException {
        Path metadataFile = outputFile.resolveSibling(
                outputFile.getFileName().toString().replaceAll("\\.wav$", "") + ".voice.json");
        String json = "{\"engine\":\"qwen3\""
                + ",\"voice\":" + JsonText.quote(voiceName)
                + ",\"delivery\":" + JsonText.quote(delivery.preset())
                + ",\"speed\":" + String.format(Locale.ROOT, "%.4f", delivery.speed())
                + ",\"language\":" + JsonText.quote(delivery.language())
                + ",\"sentencePauseMs\":" + delivery.sentencePauseMs()
                + ",\"transport\":\"persistent-http-worker-batch\"}\n";
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
