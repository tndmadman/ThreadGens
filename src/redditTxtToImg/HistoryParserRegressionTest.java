package redditTxtToImg;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Regressions for long and URL-safe Base64 history rows used across P0/P2. */
public final class HistoryParserRegressionTest {
    private HistoryParserRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("threadgens-history-parser-regression-");
        try {
            testUrlSafeGenerationHistory(temp);
            testLongGenerationHistory(temp);
            testLongPublishHistory(temp);
            System.out.println("History parser regression tests passed.");
        } finally {
            deleteTree(temp);
        }
    }

    private static void testUrlSafeGenerationHistory(Path temp) throws Exception {
        Path historyFile = temp.resolve("generation-url-safe.jsonl");
        String script = "xxx\u083e";
        NoveltyGuard guard = new NoveltyGuard(historyFile, 48, 20);
        guard.record(script, "url-safe decoder regression", ContentFormat.CONFESSION);

        String raw = Files.readString(historyFile, StandardCharsets.UTF_8);
        require(raw.contains("-") || raw.contains("_"),
                "fixture must contain a URL-safe Base64 '-' or '_' character");

        List<String> loaded = SemanticNoveltyGuard.loadRecentScripts(historyFile, 20);
        require(loaded.size() == 1 && script.equals(loaded.get(0)),
                "semantic history must decode NoveltyGuard URL-safe Base64 exactly");
    }

    private static void testLongGenerationHistory(Path temp) throws Exception {
        Path historyFile = temp.resolve("generation-long.jsonl");
        String script = ("semantic-history-token ".repeat(12000) + "xxx\u083e").trim();
        NoveltyGuard guard = new NoveltyGuard(historyFile, 48, 20);
        guard.record(script, "long semantic history regression", ContentFormat.DEBATE);

        List<String> loaded = SemanticNoveltyGuard.loadRecentScripts(historyFile, 20);
        require(loaded.size() == 1 && script.equals(loaded.get(0)),
                "long generation history must parse without regex stack overflow or Base64 mismatch");
    }

    private static void testLongPublishHistory(Path temp) throws Exception {
        Path historyFile = temp.resolve("publish-long.jsonl");
        String script = "publish-history-token ".repeat(12000).trim();
        PublishFingerprint fingerprint = PublishFingerprint.forTest(
                script, "long-artifact", 0x12345678L,
                "confession", "voice-a", List.of(2.0, 3.0, 4.0), "meta-long");
        PublishAuditHistory history = new PublishAuditHistory(historyFile, 20);
        history.record(fingerprint, "PASS", 1);

        List<PublishAuditHistory.Entry> loaded = history.load();
        require(loaded.size() == 1 && script.equals(loaded.get(0).fingerprint().script),
                "long publish history must parse without regex stack overflow");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
