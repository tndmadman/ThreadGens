package redditTxtToImg;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ComfyUiImageGenerator {
    private static final Pattern FILENAME_PATTERN = Pattern.compile("\\\"filename\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern SUBFOLDER_PATTERN = Pattern.compile("\\\"subfolder\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
    private static final Pattern TYPE_PATTERN = Pattern.compile("\\\"type\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    Path generate(String imagePrompt, OpImageSettings settings, Path outputFile)
            throws IOException, InterruptedException {
        if (outputFile.getParent() != null) {
            Files.createDirectories(outputFile.getParent());
        }

        String baseUrl = trimTrailingSlash(settings.comfyUrl);
        String workflow = buildWorkflow(imagePrompt, settings);
        String body = "{\"client_id\":\"threadgens\",\"prompt\":" + workflow + "}";

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/prompt"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("ComfyUI /prompt failed with HTTP " + response.statusCode() + ": " + response.body());
        }

        String promptId = JsonText.extractString(response.body(), "prompt_id");
        if (promptId == null || promptId.isBlank()) {
            throw new IOException("ComfyUI did not return prompt_id: " + response.body());
        }

        ImageRef imageRef = waitForImage(baseUrl, promptId, settings.timeoutSeconds);
        downloadImage(baseUrl, imageRef, outputFile);
        return outputFile;
    }

    private ImageRef waitForImage(String baseUrl, String promptId, int timeoutSeconds)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(30, timeoutSeconds) * 1000L;
        URI historyUri = URI.create(baseUrl + "/history/" + encode(promptId));
        while (System.currentTimeMillis() < deadline) {
            HttpRequest request = HttpRequest.newBuilder(historyUri)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                ImageRef ref = parseImageRef(response.body());
                if (ref != null) {
                    return ref;
                }
            }
            Thread.sleep(2000);
        }
        throw new IOException("ComfyUI image generation timed out after " + timeoutSeconds + " seconds for prompt_id " + promptId);
    }

    private ImageRef parseImageRef(String json) {
        Matcher filename = FILENAME_PATTERN.matcher(json);
        if (!filename.find()) {
            return null;
        }
        String subfolder = "";
        Matcher subfolderMatcher = SUBFOLDER_PATTERN.matcher(json);
        if (subfolderMatcher.find()) {
            subfolder = subfolderMatcher.group(1);
        }
        String type = "output";
        Matcher typeMatcher = TYPE_PATTERN.matcher(json);
        if (typeMatcher.find()) {
            type = typeMatcher.group(1);
        }
        return new ImageRef(filename.group(1), subfolder, type);
    }

    private void downloadImage(String baseUrl, ImageRef imageRef, Path outputFile)
            throws IOException, InterruptedException {
        String viewUrl = baseUrl + "/view?filename=" + encode(imageRef.filename)
                + "&subfolder=" + encode(imageRef.subfolder)
                + "&type=" + encode(imageRef.type);
        HttpRequest request = HttpRequest.newBuilder(URI.create(viewUrl))
                .timeout(Duration.ofMinutes(2))
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("ComfyUI /view failed with HTTP " + response.statusCode());
        }
        Files.write(outputFile, response.body());
    }

    private String buildWorkflow(String prompt, OpImageSettings settings) {
        long seed = Math.abs(new Random().nextLong());
        if (seed == Long.MIN_VALUE) {
            seed = 1;
        }

        return "{"
                + node("1", "CheckpointLoaderSimple", "{\"ckpt_name\":" + JsonText.quote(settings.checkpoint) + "}") + ","
                + node("2", "CLIPTextEncode", "{\"text\":" + JsonText.quote(prompt) + ",\"clip\":[\"1\",1]}") + ","
                + node("3", "CLIPTextEncode", "{\"text\":" + JsonText.quote(settings.negativePrompt) + ",\"clip\":[\"1\",1]}") + ","
                + node("4", "EmptyLatentImage", "{\"width\":" + settings.width + ",\"height\":" + settings.height + ",\"batch_size\":1}") + ","
                + node("5", "KSampler", "{"
                        + "\"seed\":" + seed + ","
                        + "\"steps\":" + settings.steps + ","
                        + "\"cfg\":" + String.format(Locale.ROOT, "%.2f", settings.cfg) + ","
                        + "\"sampler_name\":" + JsonText.quote(settings.sampler) + ","
                        + "\"scheduler\":" + JsonText.quote(settings.scheduler) + ","
                        + "\"denoise\":1.0,"
                        + "\"model\":[\"1\",0],"
                        + "\"positive\":[\"2\",0],"
                        + "\"negative\":[\"3\",0],"
                        + "\"latent_image\":[\"4\",0]"
                        + "}") + ","
                + node("6", "VAEDecode", "{\"samples\":[\"5\",0],\"vae\":[\"1\",2]}") + ","
                + node("7", "SaveImage", "{\"filename_prefix\":\"threadgens/op_image\",\"images\":[\"6\",0]}")
                + "}";
    }

    private static String node(String id, String classType, String inputs) {
        return JsonText.quote(id) + ":{\"class_type\":" + JsonText.quote(classType) + ",\"inputs\":" + inputs + "}";
    }

    private static String trimTrailingSlash(String value) {
        String cleaned = value == null || value.isBlank() ? "http://127.0.0.1:8188" : value.trim();
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static class ImageRef {
        final String filename;
        final String subfolder;
        final String type;

        ImageRef(String filename, String subfolder, String type) {
            this.filename = filename;
            this.subfolder = subfolder;
            this.type = type;
        }
    }
}
