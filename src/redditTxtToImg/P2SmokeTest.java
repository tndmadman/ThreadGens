package redditTxtToImg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Fast deterministic tests for the P2 publish gate. */
public final class P2SmokeTest {
    private P2SmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        testEmptyHistoryPasses();
        testExactArtifactBlocks();
        testExactScriptBlocks();
        testSameVoiceAloneDoesNotBlock();
        testHistoryRoundTripAndCorruptionFailsClosed();
        testReportWriting();
        System.out.println("P2 smoke tests passed.");
    }

    private static void testEmptyHistoryPasses() {
        PrePublishAuditor auditor = new PrePublishAuditor(58, 78);
        PublishFingerprint candidate = fp("A completely new story about a lighthouse keeper.", "artifact-a", 0x1111L,
                "confession", "voice-a", List.of(2.1, 3.4, 4.2), "meta-a");
        PrePublishAuditor.Result result = auditor.assess(candidate, List.of());
        require(result.status() == PrePublishAuditor.Status.PASS, "empty history should pass");
    }

    private static void testExactArtifactBlocks() {
        PrePublishAuditor auditor = new PrePublishAuditor(58, 78);
        PublishFingerprint prior = fp("Old story words.", "same-artifact", 0x1234L,
                "debate", "voice-a", List.of(2.0, 2.5), "meta-a");
        PublishFingerprint candidate = fp("Different words entirely.", "same-artifact", 0x9999L,
                "best_answers", "voice-b", List.of(5.0, 1.0), "meta-b");
        PrePublishAuditor.Result result = auditor.assess(candidate,
                List.of(new PublishAuditHistory.Entry(prior, "PASS", 10)));
        require(result.status() == PrePublishAuditor.Status.BLOCK, "exact artifact must block");
    }

    private static void testExactScriptBlocks() {
        PrePublishAuditor auditor = new PrePublishAuditor(58, 78);
        String script = "The elevator opened and every person inside was wearing my coat.";
        PublishFingerprint prior = fp(script, "artifact-1", 0x123456L,
                "thread_story", "voice-a", List.of(2.5, 2.6, 2.4), "meta-a");
        PublishFingerprint candidate = fp(script, "artifact-2", 0x654321L,
                "thread_story", "voice-c", List.of(4.0, 3.0), "meta-c");
        PrePublishAuditor.Result result = auditor.assess(candidate,
                List.of(new PublishAuditHistory.Entry(prior, "PASS", 20)));
        require(result.status() == PrePublishAuditor.Status.BLOCK, "exact script must block");
    }

    private static void testSameVoiceAloneDoesNotBlock() {
        PrePublishAuditor auditor = new PrePublishAuditor(58, 78);
        PublishFingerprint prior = fp(
                "A chef loses a handwritten recipe during a thunderstorm and searches the restaurant.",
                "artifact-1", 0x0f0f0fL, "confession", "shared-voice", List.of(2.0, 4.0, 3.0), "meta-a");
        PublishFingerprint candidate = fp(
                "Astronomers discover an unusual signal while repairing a radio telescope in winter.",
                "artifact-2", 0xf0f0f0L, "debate", "shared-voice", List.of(5.5, 1.3), "meta-b");
        PrePublishAuditor.Result result = auditor.assess(candidate,
                List.of(new PublishAuditHistory.Entry(prior, "PASS", 20)));
        require(result.status() != PrePublishAuditor.Status.BLOCK,
                "same voice by itself must not block original content");
    }

    private static void testHistoryRoundTripAndCorruptionFailsClosed() throws Exception {
        Path dir = Files.createTempDirectory("threadgens-p2-history-");
        Path historyFile = dir.resolve("publish.jsonl");
        try {
            PublishAuditHistory history = new PublishAuditHistory(historyFile, 5);
            PublishFingerprint original = fp("History round trip script.", "artifact-x", 0xabcdefL,
                    "best_answers", "voice-x", List.of(1.2, 2.3), "meta-x");
            history.record(original, "PASS", 17);
            List<PublishAuditHistory.Entry> loaded = history.load();
            require(loaded.size() == 1, "history should round-trip one record");
            require(loaded.get(0).fingerprint().script.equals(original.script), "history script mismatch");

            Files.writeString(historyFile, "{bad history}\n", StandardCharsets.UTF_8);
            boolean failed = false;
            try {
                history.load();
            } catch (IOException expected) {
                failed = true;
            }
            require(failed, "corrupt publish history must fail closed");
        } finally {
            deleteTree(dir);
        }
    }

    private static void testReportWriting() throws Exception {
        Path dir = Files.createTempDirectory("threadgens-p2-report-");
        try {
            PublishFingerprint candidate = fp("Report script.", "artifact-report", 55L,
                    "escalating_conversation", "voice-z", List.of(2.0), "meta-z");
            PrePublishAuditor.Result result = new PrePublishAuditor(58, 78).assess(candidate, List.of());
            Path report = dir.resolve("publish_audit.json");
            PrePublishAuditor.writeReport(report, candidate, result, "block");
            String json = Files.readString(report, StandardCharsets.UTF_8);
            require(json.contains("\"status\": \"PASS\""), "report should contain status");
            require(json.contains("\"risk\""), "report should contain risk");
        } finally {
            deleteTree(dir);
        }
    }

    private static PublishFingerprint fp(String script, String artifact, long visual, String format,
                                         String voice, List<Double> pacing, String metadata) {
        return PublishFingerprint.forTest(script, artifact, visual, format, voice, pacing, metadata);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) Files.deleteIfExists(path);
        }
    }
}
