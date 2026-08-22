package redditTxtToImg;

/**
 * Explicit compatibility/debug entry point that bypasses P0 orchestration while
 * retaining artifact existence checks. Normal users should use CheckedRunner or
 * P0Entrypoint instead.
 */
public final class RawCheckedRunner {
    private RawCheckedRunner() {
    }

    public static void main(String[] args) {
        try {
            CheckedRunner.runRawOrThrow(args == null ? new String[0] : args);
        } catch (Exception e) {
            System.err.println("Raw checked run failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
