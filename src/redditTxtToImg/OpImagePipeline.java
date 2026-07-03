package redditTxtToImg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
        Path generated = imageGenerator.generate(imagePrompt, settings, imageFile);
        System.out.println("Generated OP image with ComfyUI: " + generated);
        return generated;
    }

    private static String safeName(String value) {
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() ? "op" : cleaned;
    }
}
