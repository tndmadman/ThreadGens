package redditTxtToImg;

import java.util.Set;

final class CliOptions {
    static final Set<String> VALUE_OPTIONS = Set.of(
            "--platform", "--count", "--prefix", "--style", "--names", "--profiles",
            "--post-title", "--topic", "--llm-model", "--llm-url", "--script-out",
            "--tts", "--voice", "--voice-dir", "--tts-command", "--audio-dir", "--tts-timeout",
            "--video-dir", "--video-command", "--fps", "--video-timeout", "--final-video",
            "--image-mode", "--op-image", "--image-dir", "--image-cache-dir", "--comfy-url"
    );

    static final Set<String> LLM_ONLY_VALUE_OPTIONS = Set.of(
            "--post-title", "--topic", "--llm-model", "--llm-url", "--script-out"
    );

    private CliOptions() {
    }

    static boolean isValueOption(String arg) {
        return VALUE_OPTIONS.contains(arg);
    }

    static boolean isLlmOnlyValueOption(String arg) {
        return LLM_ONLY_VALUE_OPTIONS.contains(arg);
    }
}
