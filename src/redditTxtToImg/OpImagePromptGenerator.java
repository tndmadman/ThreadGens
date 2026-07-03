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

final class OpImagePromptGenerator {
    private final HttpClient httpClient;
    private final URI endpoint;
    private final String model;

    OpImagePromptGenerator(String endpointUrl, String model) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.endpoint = URI.create(endpointUrl);
        this.model = model;
    }

    String generatePrompt(String platform, String postTitle, String opBody, Path debugPromptFile)
            throws IOException, InterruptedException {
        String prompt = buildPrompt(platform, postTitle, opBody);
        String json = "{"
                + "\"model\":" + JsonText.quote(model) + ","
                + "\"prompt\":" + JsonText.quote(prompt) + ","
                + "\"stream\":false"
                + "}";

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Ollama image prompt request failed with HTTP " + response.statusCode() + ": " + response.body());
        }

        String generated = JsonText.extractString(response.body(), "response");
        if (generated == null || generated.isBlank()) {
            throw new IOException("Ollama did not return an image prompt.");
        }

        String cleaned = cleanPrompt(generated);
        if (debugPromptFile != null) {
            if (debugPromptFile.getParent() != null) {
                Files.createDirectories(debugPromptFile.getParent());
            }
            Files.writeString(debugPromptFile, cleaned, StandardCharsets.UTF_8);
        }
        return cleaned;
    }

    private static String buildPrompt(String platform, String postTitle, String opBody) {
        String visibleTitle = postTitle == null || postTitle.isBlank() ? "" : postTitle.trim();
        String body = opBody == null || opBody.isBlank() ? "a strange social media story" : opBody.trim();
        String platformName = platform == null || platform.isBlank() ? "social media" : platform;

        return "Turn this " + platformName + " original post into one detailed SDXL image prompt for RealVisXL.\n\n"
                + "Visible title, if any:\n" + visibleTitle + "\n\n"
                + "Original post body:\n" + body + "\n\n"
                + "Rules:\n"
                + "- Return only the final image prompt, no labels or explanations.\n"
                + "- Add concrete visual detail that fits the post context: setting, subject, mood, lighting, objects, camera angle.\n"
                + "- Make it photorealistic, cinematic, natural lighting, high detail, realistic texture.\n"
                + "- Do not include readable text, captions, UI, logos, watermarks, memes, screenshots, or speech bubbles.\n"
                + "- Do not invent celebrities, brands, copyrighted characters, or graphic violence.\n"
                + "- Keep it under 90 words.\n";
    }

    private static String cleanPrompt(String value) {
        String cleaned = value.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
        cleaned = cleaned.replaceAll("(?i)^(prompt|image prompt|final prompt)\\s*[:.-]\\s*", "").trim();
        if ((cleaned.startsWith("\"") && cleaned.endsWith("\"")) || (cleaned.startsWith("'") && cleaned.endsWith("'"))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }
}
