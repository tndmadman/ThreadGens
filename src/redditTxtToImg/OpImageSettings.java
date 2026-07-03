package redditTxtToImg;

import java.nio.file.Path;
import java.util.Properties;

final class OpImageSettings {
    String imageMode = "none";
    Path opImagePath = null;
    Path imageDirectory = Path.of("output", "images");
    Path imageCacheDirectory = Path.of("output", "cache", "images");
    String comfyUrl = "http://127.0.0.1:8188";
    String ollamaUrl = "http://localhost:11434/api/generate";
    String llmModel = "llama3.1:8b";
    String checkpoint = "RealVisXL_V5.0_fp32.safetensors";
    String sampler = "dpmpp_2m_sde";
    String scheduler = "karras";
    String negativePrompt = "low quality, blurry, distorted, deformed, cartoon, anime, text, captions, watermark, logo, signature, bad anatomy, extra fingers";
    int width = 1024;
    int height = 768;
    int steps = 30;
    int timeoutSeconds = 900;
    double cfg = 5.0;

    static OpImageSettings from(Properties properties) {
        OpImageSettings settings = new OpImageSettings();
        if (properties == null) {
            return settings;
        }
        settings.imageMode = properties.getProperty("imageMode", settings.imageMode).trim().toLowerCase();
        settings.imageDirectory = Path.of(properties.getProperty("imageDirectory", settings.imageDirectory.toString()));
        settings.imageCacheDirectory = Path.of(properties.getProperty("imageCacheDirectory", settings.imageCacheDirectory.toString()));
        settings.comfyUrl = properties.getProperty("comfyUrl", settings.comfyUrl);
        settings.ollamaUrl = properties.getProperty("ollamaUrl", settings.ollamaUrl);
        settings.llmModel = properties.getProperty("llmModel", settings.llmModel);
        settings.checkpoint = properties.getProperty("imageCheckpoint", settings.checkpoint);
        settings.sampler = properties.getProperty("imageSampler", settings.sampler);
        settings.scheduler = properties.getProperty("imageScheduler", settings.scheduler);
        settings.negativePrompt = properties.getProperty("imageNegative", settings.negativePrompt);
        settings.width = parseInt(properties.getProperty("imageWidth"), settings.width);
        settings.height = parseInt(properties.getProperty("imageHeight"), settings.height);
        settings.steps = parseInt(properties.getProperty("imageSteps"), settings.steps);
        settings.timeoutSeconds = parseInt(properties.getProperty("imageTimeout"), settings.timeoutSeconds);
        settings.cfg = parseDouble(properties.getProperty("imageCfg"), settings.cfg);
        return settings;
    }

    void applyArg(String arg, String value) {
        if (value == null) {
            return;
        }
        if ("--image-mode".equals(arg)) imageMode = value.trim().toLowerCase();
        else if ("--op-image".equals(arg)) opImagePath = Path.of(value);
        else if ("--image-dir".equals(arg)) imageDirectory = Path.of(value);
        else if ("--image-cache-dir".equals(arg)) imageCacheDirectory = Path.of(value);
        else if ("--comfy-url".equals(arg)) comfyUrl = value;
        else if ("--llm-url".equals(arg)) ollamaUrl = value;
        else if ("--llm-model".equals(arg)) llmModel = value;
        else if ("--image-checkpoint".equals(arg)) checkpoint = value;
        else if ("--image-sampler".equals(arg)) sampler = value;
        else if ("--image-scheduler".equals(arg)) scheduler = value;
        else if ("--image-negative".equals(arg)) negativePrompt = value;
        else if ("--image-width".equals(arg)) width = parseInt(value, width);
        else if ("--image-height".equals(arg)) height = parseInt(value, height);
        else if ("--image-steps".equals(arg)) steps = parseInt(value, steps);
        else if ("--image-timeout".equals(arg)) timeoutSeconds = parseInt(value, timeoutSeconds);
        else if ("--image-cfg".equals(arg)) cfg = parseDouble(value, cfg);
    }

    boolean isEnabled() {
        return !"none".equalsIgnoreCase(imageMode) && !imageMode.isBlank();
    }

    boolean isComfyMode() {
        return "comfyui".equalsIgnoreCase(imageMode) || "comfy".equalsIgnoreCase(imageMode);
    }

    boolean isLocalMode() {
        return "local".equalsIgnoreCase(imageMode);
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        if (value == null) return fallback;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
