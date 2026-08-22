package redditTxtToImg;

import java.io.IOException;

/**
 * Backward-compatible entry point retained for existing Windows scripts.
 * P2Entrypoint now owns the final production gate and invokes the full P0/P1
 * pipeline before approving completed output.
 */
public class OpImageVideoSafeRunner {
    public static void main(String[] args) {
        P2Entrypoint.main(args);
    }

    static void run(String[] args) throws IOException, InterruptedException {
        P2Entrypoint.runOrThrow(args == null ? new String[0] : args);
    }
}
