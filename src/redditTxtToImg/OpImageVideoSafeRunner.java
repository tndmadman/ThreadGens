package redditTxtToImg;

import java.io.IOException;

/**
 * Backward-compatible entry point retained for existing Windows scripts.
 * P0Runner now owns the image/audio/video ordering and always renders dynamic
 * video after OP-image overlays are complete.
 */
public class OpImageVideoSafeRunner {
    public static void main(String[] args) {
        P0Runner.main(args);
    }

    static void run(String[] args) throws IOException, InterruptedException {
        P0Runner.runOrThrow(args == null ? new String[0] : args);
    }
}
