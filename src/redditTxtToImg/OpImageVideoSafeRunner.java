package redditTxtToImg;

import java.io.IOException;

/**
 * Backward-compatible entry point retained for existing Windows scripts.
 * Normal production now runs P0 generation/rendering and then the P2
 * pre-publish repetitiveness gate.
 */
public class OpImageVideoSafeRunner {
    public static void main(String[] args) {
        P2Entrypoint.main(args);
    }

    static void run(String[] args) throws IOException, InterruptedException {
        P2Entrypoint.runOrThrow(args == null ? new String[0] : args);
    }
}