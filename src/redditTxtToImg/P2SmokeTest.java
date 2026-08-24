package redditTxtToImg;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Fast deterministic tests for the P2 publish gate. */
public final class P2SmokeTest {
    private P2SmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args != null && args.length == 2 && "lock-process".equals(args[0])) {
            runCrossProcessLockProbe(Path.of(args[1]));
            return;
        }

        testEmptyHistoryPasses();
        testExactArtifactBlocks();
        testExactScriptBlocks();
        testSemanticPremiseBlocks();
        testSameVoiceAloneDoesNotBlock();
        testUnknownVoiceDoesNotCreateReusePenalty();
        testRenderedIdentityReuseIsScored();
        testFormatVariantScoring();
        testHistoryRoundTripAndCorruptionFailsClosed();
        testSchemaOneHistoryRemainsReadable();
        testSchemaTwoHistoryRemainsReadable();
        testConcurrentHistoryTransactionSerializes();
        testLongGenerationHistoryFormatResolutionIsStackSafe();
        testReportWriting();
        System.out.println("P2 smoke tests passed.");
    }

    private static void runCrossProcessLockProbe(Path historyFile) throws Exception {
        PublishAuditHistory history = new PublishAuditHistory(historyFile, 20);
        PublishFingerprint candidate = PublishFingerprint.forTest(
                "Cross-process lock candidate.", "cross-process-artifact", 0x1234L, 0x5678L,
                "confession", "voice-a", List.of(2.0, 3.0), "meta-a");
        try (PublishAuditHistory.LockHandle ignored = history.lockExclusive()) {
            if (history.load().isEmpty()) {
                Thread.sleep(300);
                history.record(candidate, "PASS", 1);
                System.out.println("P2 lock probe recorded first candidate.");
            } else {
                System.out.println("P2 lock probe observed existing candidate.");
            }
        }
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

    private static void testSemanticPremiseBlocks() {
        PrePublishAuditor auditor = new PrePublishAuditor(58, 78);
        PublishFingerprint prior = fp(
                "I found an old suitcase under my uncle's bed after the funeral and it contained a second set of family photos.",
                "artifact-semantic-1", 0x11112222L, "confession", "voice-a", List.of(2.2, 3.1), "meta-a");
        PublishFingerprint candidate = fp(
                "While clearing a relative's room I discovered a sealed trunk with photographs of people who looked exactly like us.",
                "artifact-semantic-2", 0x99998888L, "thread_story", "voice-b", List.of(4.7, 1.8), "meta-b");
        PrePublishAuditor.Result result = auditor.assess(candidate,
                List.of(new PublishAuditHistory.Entry(prior, "PASS", 20)), 0.92);
        require(result.status() == PrePublishAuditor.Status.BLOCK,
                "semantic premise similarity above the hard threshold must block");
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

    private static void testUnknownVoiceDoesNotCreateReusePenalty() {
        PrePublishAuditor auditor = new PrePublishAuditor(58, 78);
        PublishFingerprint candidate = fp("New candidate topic about coral restoration.", "artifact-new", 0x1234L,
                "debate", "unknown", List.of(2.0, 4.5), "");
        List<PublishAuditHistory.Entry> history = List.of(
                new PublishAuditHistory.Entry(fp("Alpha mountain rescue story.", "a1", 0xffffL,
                        "confession", "unknown", List.of(1.0, 8.0), ""), "PASS", 10),
                new PublishAuditHistory.Entry(fp("Beta bakery argument.", "a2", 0xff00L,
                        "best_answers", "unknown", List.of(9.0), ""), "PASS", 10),
                new PublishAuditHistory.Entry(fp("Gamma weather balloon story.", "a3", 0xf0f0L,
                        "thread_story", "unknown", List.of(3.0, 3.0, 3.0), ""), "PASS", 10),
                new PublishAuditHistory.Entry(fp("Delta antique radio mystery.", "a4", 0xaaaaL,
                        "escalating_conversation", "unknown", List.of(7.0, 1.0), ""), "PASS", 10)
        );
        PrePublishAuditor.Result result = auditor.assess(candidate, history);
        require(result.scores().audio() < 0.95,
                "unknown voice identifiers must not be treated as a known repeated voice");
    }

    private static void testRenderedIdentityReuseIsScored() {
        PrePublishAuditor auditor = new PrePublishAuditor(58, 78);
        PublishFingerprint prior = PublishFingerprint.forTest(
                "A story about a lost telescope.", "identity-a", 0x11110000L, 0x55aa55aaL,
                "confession", "voice-a", List.of(2.0, 3.0), "meta-a");
        PublishFingerprint candidate = PublishFingerprint.forTest(
                "A completely unrelated story about a flooded greenhouse.", "identity-b", 0xeeeeffffL, 0x55aa55aaL,
                "debate", "voice-b", List.of(6.0, 1.0), "meta-b");
        PrePublishAuditor.Result result = auditor.assess(candidate,
                List.of(new PublishAuditHistory.Entry(prior, "PASS", 10)));
        require(result.scores().identity() > 0.99,
                "same rendered identity fingerprint must be recognized");
        require(result.status() != PrePublishAuditor.Status.BLOCK,
                "same rendered identity alone must not hard-block unrelated content");
    }

    private static void testFormatVariantScoring() {
        PublishFingerprint base = PublishFingerprint.forTest(
                "Base format scoring script.", "format-a", 1L,
                "thread_story", "witness_chain", "voice-a", List.of(2.0), "meta-a");
        PublishFingerprint exact = PublishFingerprint.forTest(
                "Exact substyle comparison.", "format-b", 2L,
                "thread_story", "witness_chain", "voice-b", List.of(3.0), "meta-b");
        PublishFingerprint sameFormat = PublishFingerprint.forTest(
                "Different substyle comparison.", "format-c", 3L,
                "thread_story", "timeline_updates", "voice-c", List.of(4.0), "meta-c");
        PublishFingerprint sameFamily = PublishFingerprint.forTest(
                "Related pacing family comparison.", "format-d", 4L,
                "escalating_conversation", "multiple_witnesses", "voice-d", List.of(5.0), "meta-d");
        PublishFingerprint distinct = PublishFingerprint.forTest(
                "Distinct format comparison.", "format-e", 5L,
                "best_answers", "ranked_answers", "voice-e", List.of(6.0), "meta-e");
        PublishFingerprint legacy = fp(
                "Legacy history comparison.", "format-f", 6L,
                "thread_story", "voice-f", List.of(7.0), "meta-f");

        require(Math.abs(PrePublishAuditor.formatSimilarity(base, exact) - 1.0) < 0.001,
                "same format and substyle should score 1.0");
        require(Math.abs(PrePublishAuditor.formatSimilarity(base, sameFormat) - 0.65) < 0.001,
                "same format with a different substyle should score 0.65");
        require(Math.abs(PrePublishAuditor.formatSimilarity(base, legacy) - 0.65) < 0.001,
                "legacy history without a substyle should not claim an exact substyle match");
        require(Math.abs(PrePublishAuditor.formatSimilarity(base, sameFamily) - 0.25) < 0.001,
                "different formats in the same pacing family should score 0.25");
        require(PrePublishAuditor.formatSimilarity(base, distinct) == 0.0,
                "different formats and pacing families should score 0.0");
    }

    private static void testHistoryRoundTripAndCorruptionFailsClosed() throws Exception {
        Path dir = Files.createTempDirectory("threadgens-p2-history-");
        Path historyFile = dir.resolve("publish.jsonl");
        try {
            PublishAuditHistory history = new PublishAuditHistory(historyFile, 5);
            PublishFingerprint original = PublishFingerprint.forTest(
                    "History round trip script.", "artifact-x", 0xabcdefL, 0x12345678L,
                    "best_answers", "editor_picks", "voice-x", List.of(1.2, 2.3), "meta-x");
            history.record(original, "PASS", 17);
            List<PublishAuditHistory.Entry> loaded = history.load();
            require(loaded.size() == 1, "history should round-trip one record");
            require(loaded.get(0).fingerprint().script.equals(original.script), "history script mismatch");
            require(loaded.get(0).fingerprint().identityHashes.equals(original.identityHashes),
                    "identity fingerprint history mismatch");
            require("editor_picks".equals(loaded.get(0).fingerprint().formatVariant),
                    "format variant history mismatch");

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

    private static void testSchemaOneHistoryRemainsReadable() throws Exception {
        Path dir = Files.createTempDirectory("threadgens-p2-schema1-");
        Path historyFile = dir.resolve("publish.jsonl");
        try {
            String old = "{\"schema\":\"1\",\"created\":\"2026-01-01T00:00:00Z\","
                    + "\"platform\":\"reddit\",\"format\":\"confession\","
                    + "\"script_b64\":\"b2xkIHNjcmlwdA\",\"script_hash\":\"hash\","
                    + "\"artifact_hash\":\"artifact\",\"visuals\":\"1\","
                    + "\"voice_b64\":\"dm9pY2U\",\"tts\":\"kokoro\","
                    + "\"pacing\":\"2.000\",\"total_duration\":\"2.0\","
                    + "\"metadata_hash\":\"\",\"status\":\"PASS\",\"risk\":\"1\"}\n";
            Files.writeString(historyFile, old, StandardCharsets.UTF_8);
            List<PublishAuditHistory.Entry> loaded = new PublishAuditHistory(historyFile, 5).load();
            require(loaded.size() == 1, "schema-1 publish history should remain readable");
            require(loaded.get(0).fingerprint().identityHashes.isEmpty(),
                    "schema-1 rows should load with no identity fingerprint");
        } finally {
            deleteTree(dir);
        }
    }

    private static void testSchemaTwoHistoryRemainsReadable() throws Exception {
        Path dir = Files.createTempDirectory("threadgens-p2-schema2-");
        Path historyFile = dir.resolve("publish.jsonl");
        try {
            String old = "{\"schema\":\"2\",\"created\":\"2026-08-22T00:00:00Z\","
                    + "\"platform\":\"reddit\",\"format\":\"debate\","
                    + "\"script_b64\":\"b2xkIHNjcmlwdA\",\"script_hash\":\"hash\","
                    + "\"artifact_hash\":\"artifact\",\"visuals\":\"1\",\"identities\":\"2\","
                    + "\"voice_b64\":\"dm9pY2U\",\"tts\":\"kokoro\","
                    + "\"pacing\":\"2.000\",\"total_duration\":\"2.0\","
                    + "\"metadata_hash\":\"\",\"status\":\"PASS\",\"risk\":\"1\"}\n";
            Files.writeString(historyFile, old, StandardCharsets.UTF_8);
            List<PublishAuditHistory.Entry> loaded = new PublishAuditHistory(historyFile, 5).load();
            require(loaded.size() == 1, "schema-2 publish history should remain readable");
            require("unknown".equals(loaded.get(0).fingerprint().formatVariant),
                    "schema-2 rows should load with an unknown substyle");
            require(loaded.get(0).fingerprint().identityHashes.size() == 1,
                    "schema-2 identity fingerprints should remain intact");
        } finally {
            deleteTree(dir);
        }
    }

    private static void testConcurrentHistoryTransactionSerializes() throws Exception {
        Path dir = Files.createTempDirectory("threadgens-p2-concurrency-");
        Path historyFile = dir.resolve("publish.jsonl");
        try {
            PublishAuditHistory history = new PublishAuditHistory(historyFile, 20);
            PublishFingerprint candidate = PublishFingerprint.forTest(
                    "Concurrent duplicate candidate.", "concurrent-artifact", 0x1234L, 0x5678L,
                    "confession", "voice-a", List.of(2.0, 3.0), "meta-a");
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger approvals = new AtomicInteger();
            AtomicReference<Throwable> failure = new AtomicReference<>();

            Runnable transaction = () -> {
                try {
                    start.await();
                    try (PublishAuditHistory.LockHandle ignored = history.lockExclusive()) {
                        if (history.load().isEmpty()) {
                            Thread.sleep(80);
                            history.record(candidate, "PASS", 1);
                            approvals.incrementAndGet();
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            };

            Thread first = new Thread(transaction, "p2-history-lock-1");
            Thread second = new Thread(transaction, "p2-history-lock-2");
            first.start();
            second.start();
            start.countDown();
            first.join(5000);
            second.join(5000);

            require(!first.isAlive() && !second.isAlive(), "concurrent history transactions must not deadlock");
            require(failure.get() == null, "concurrent history transaction failed: " + failure.get());
            require(approvals.get() == 1, "only one concurrent candidate may observe an empty history and approve");
            require(history.load().size() == 1, "concurrent history transaction must record exactly one row");
        } finally {
            deleteTree(dir);
        }
    }

    private static void testLongGenerationHistoryFormatResolutionIsStackSafe() throws Exception {
        Path dir = Files.createTempDirectory("threadgens-p2-long-generation-history-");
        Path historyFile = dir.resolve("generation.jsonl");
        try {
            String script = ("long-history-token ".repeat(12000)).trim();
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(script.getBytes(StandardCharsets.UTF_8));
            String row = "{\"created\":\"2026-08-22T00:00:00Z\","
                    + "\"format\":\"confession\",\"variant\":\"regret_reveal\",\"hash\":\"test\","
                    + "\"topic_b64\":\"dG9waWM\",\"script_b64\":\"" + encoded + "\"}\n";
            Files.writeString(historyFile, row, StandardCharsets.UTF_8);

            P2Entrypoint.AuditConfig config = new P2Entrypoint.AuditConfig();
            config.requestedFormat = "auto";
            config.generationHistory = historyFile;
            config.autoGenerateText = true;
            config.postTitle = "Long history regression";
            config.topic = "Long history regression";

            Method method = P2Entrypoint.class.getDeclaredMethod(
                    "resolveActualFormat", P2Entrypoint.AuditConfig.class, String.class);
            method.setAccessible(true);
            String format = (String) method.invoke(null, config, script);
            require("confession".equals(format),
                    "P2 should resolve a long generation-history row without exhausting the JVM stack");

            Method variantMethod = P2Entrypoint.class.getDeclaredMethod(
                    "resolveActualVariant", P2Entrypoint.AuditConfig.class, String.class, String.class);
            variantMethod.setAccessible(true);
            String variant = (String) variantMethod.invoke(null, config, script, format);
            require("regret_reveal".equals(variant),
                    "P2 should recover the exact generated substyle from long history rows");
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
            require(json.contains("\"identity\""), "report should contain identity score");
            require(json.contains("\"candidate_format_variant\""), "report should contain format substyle");
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
