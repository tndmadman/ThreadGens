package redditTxtToImg;

import java.io.IOException;

/**
 * Backward-compatible entry point retained for existing Windows scripts.
 * P0Entrypoint now owns hidden-prompt generation and delegates rendering to the
 * P0 pipeline after OP-image ordering is safe.
 */
public class OpImageVideoSafeRunner {
    public static void main(String[] args) {
        P0Entrypoint.main(args);
    }

    static void run(String[] args) throws IOException, InterruptedException {
        P0Entrypoint.runOrThrow(args == null ? new String[0] : args);
    }
}
