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

final class OpImagePipeline {
    private OpImagePipeline() {
    }

    static void generateAndOverlay(String platform, String postTitle, String opBody,
                                   Path opScreenshotPath, String outputPrefix,
                                   OpImageSettings settings)
            throws IOException, InterruptedException {
        if (settings == null || !settings.isEnabled()) {
            return;
        }

        Path opImage = resolveOrGenerateImage(platform, postTitle, opBody, outputPrefix, settings);
        if (opImage == null) {
            return;
        }

        OpImageCompositor.overlay(platform, opScreenshotPath, opImage);
        System.out.println("Added OP image overlay: " + opImage + " -> " + opScreenshotPath);
    }

    private static Path resolveOrGenerateImage(String platform, String postTitle, String opBody,
                                               String outputPrefix, OpImageSettings settings)
            throws IOException, InterruptedException {
        if (settings.opImagePath != null) {
            if (!Files.exists(settings.opImagePath)) {
                throw new IOException("--op-image was set, but the image file does not exist: " + settings.opImagePath);
            }
            return settings.opImagePath;
        }

        if (settings.isLocalMode()) {
            throw new IOException("--image-mode local requires --op-image path/to/image.png");
        }
        if (!settings.isComfyMode()) {
            throw new IOException("Unsupported --image-mode: " + settings.imageMode + ". Use none, local, or comfyui.");
        }

        Files.createDirectories(settings.imageDirectory);
        Files.createDirectories(settings.imageCacheDirectory);

        String safePrefix = safeName(outputPrefix == null || outputPrefix.isBlank() ? "op" : outputPrefix);
        Path promptFile = settings.imageCacheDirectory.resolve("0" + safePrefix + "_prompt.txt");
        Path imageFile = settings.imageDirectory.resolve("0" + safePrefix + "_op.png");

        OpImagePromptGenerator promptGenerator = new OpImagePromptGenerator(settings.ollamaUrl, settings.llmModel);
        String imagePrompt = promptGenerator.generatePrompt(platform, postTitle, opBody, promptFile);
        Files.writeString(promptFile, imagePrompt, StandardCharsets.UTF_8);
        System.out.println("Generated OP image prompt: " + promptFile);

        ComfyUiImageGenerator imageGenerator = new ComfyUiImageGenerator();
        System.out.println("Waiting for exclusive ThreadGens GPU lane for ComfyUI: " + GpuAiLane.lockPath());
        try (GpuAiLane ignored = GpuAiLane.acquireExclusive()) {
            System.out.println("Acquired exclusive ThreadGens GPU lane for ComfyUI.");
            releaseQwenGpuIfConfigured();
            Path generated = imageGenerator.generate(imagePrompt, settings, imageFile);
            System.out.println("Generated OP image with ComfyUI: " + generated);
            return generated;
        }
    }

    private static void releaseQwenGpuIfConfigured() throws IOException, InterruptedException {
        String primary = System.getenv("THREADGENS_TTS_ENGINE_OVERRIDE");
        String fallback = System.getenv("THREADGENS_TTS_FALLBACK_ENGINE");
        if (!isQwenEngine(primary) && !isQwenEngine(fallback)) {
            return;
        }

        String configuredUrl = System.getenv("THREADGENS_QWEN3_URL");
        String baseUrl = configuredUrl == null || configuredUrl.isBlank()
                ? "http://127.0.0.1:8765"
                : configuredUrl.trim().replaceAll("/+$", "");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/release-gpu"))
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException(
                        "Qwen3-TTS refused to release GPU memory before ComfyUI (HTTP "
                                + response.statusCode() + "): " + response.body());
            }
            System.out.println("Qwen3-TTS GPU memory released for ComfyUI.");
        } catch (java.net.ConnectException e) {
            // No Qwen server means there is no resident Qwen model to evict.
            System.out.println("Qwen3-TTS server is not running; ComfyUI already has the GPU lane.");
        }
    }

    private static boolean isQwenEngine(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim();
        return "qwen3".equalsIgnoreCase(normalized) || "qwen3-tts".equalsIgnoreCase(normalized);
    }

    private static String safeName(String value) {
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() ? "op" : cleaned;
    }
}
