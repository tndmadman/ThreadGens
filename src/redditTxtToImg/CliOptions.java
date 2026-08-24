package redditTxtToImg;

import java.util.Set;

final class CliOptions {
    static final Set<String> VALUE_OPTIONS = Set.of(
            "--platform", "--count", "--prefix", "--style", "--names", "--profiles",
            "--post-title", "--topic", "--llm-model", "--llm-url", "--script-out",
            "--tts", "--voice", "--voice-dir", "--tts-command", "--audio-dir", "--tts-timeout",
            "--voice-series", "--voice-selection", "--series-id", "--tts-delivery", "--tts-speed",
            "--tts-language", "--tts-sentence-pause-ms",
            "--video-dir", "--video-command", "--fps", "--video-timeout", "--final-video",
            "--captions", "--caption-words", "--visual-max-scenes",
            "--identity-history-file", "--identity-history-limit",
            "--metadata-dir", "--disclosure", "--content-origin",
            "--image-mode", "--op-image", "--image-dir", "--image-cache-dir", "--comfy-url",
            "--image-checkpoint", "--image-width", "--image-height", "--image-steps", "--image-cfg",
            "--image-sampler", "--image-scheduler", "--image-negative", "--image-timeout",
            "--format", "--format-variant", "--history-file", "--history-limit", "--novelty-threshold", "--novelty-retries",
            "--embedding-model", "--semantic-threshold", "--semantic-history-limit"
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
