package redditTxtToImg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Fast regression tests for the P0/P1/P2 integration boundaries. */
public final class IntegratedPipelineSmokeTest {
    private IntegratedPipelineSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        testCorruptIdentityHistoryFailsClosed();
        testP2OptionsDoNotLeakIntoP1();
        testStableP1ProvenanceSignature();
        System.out.println("Integrated pipeline smoke tests passed.");
    }

    private static void testCorruptIdentityHistoryFailsClosed() throws Exception {
        Path dir = Files.createTempDirectory("threadgens-integrated-identity-");
        try {
            Path historyFile = dir.resolve("identity.jsonl");
            Files.writeString(historyFile, "{not valid identity history}\n", StandardCharsets.UTF_8);
            IdentityHistory history = new IdentityHistory(historyFile, 20, true);
            boolean failed = false;
            try {
                history.selectAndRecord(
                        List.of("alpha", "bravo"),
                        List.of("a.png", "b.png"),
                        List.of(),
                        1,
                        "corrupt-test");
            } catch (IOException expected) {
                failed = true;
            }
            require(failed, "corrupt P1 identity history must fail closed");
        } finally {
            deleteTree(dir);
        }
    }

    private static void testP2OptionsDoNotLeakIntoP1() throws Exception {
        String[] original = {
                "data/comments.txt", "output",
                "--captions", "sentence",
                "--voice-selection", "series",
                "--publish-history", "data/test-publish.jsonl",
                "--publish-audit-mode", "warn",
                "--audit-report", "output/video/test-audit.json"
        };
        P2Entrypoint.AuditConfig config = P2Entrypoint.AuditConfig.fromArgs(original);
        String joined = String.join("|", config.stripP2Options(original));
        require(joined.contains("--captions|sentence"), "P1 caption option must survive P2 stripping");
        require(joined.contains("--voice-selection|series"), "P1 voice option must survive P2 stripping");
        require(!joined.contains("--publish-history"), "P2 history option must not leak into P1");
        require(!joined.contains("--publish-audit-mode"), "P2 mode option must not leak into P1");
        require(!joined.contains("--audit-report"), "P2 report option must not leak into P1");
    }

    private static void testStableP1ProvenanceSignature() throws Exception {
        Path dir = Files.createTempDirectory("threadgens-integrated-provenance-");
        try {
            P2Entrypoint.AuditConfig config = new P2Entrypoint.AuditConfig();
            config.outputPrefix = "integrated";
            config.outputDirectory = dir.resolve("images");
            config.videoDirectory = dir.resolve("video");
            config.metadataDirectory = dir.resolve("metadata");
            config.finalVideoName = "final.mp4";
            Files.createDirectories(config.metadataDirectory);
            Path manifest = config.metadataDirectory.resolve("integrated-provenance.json");

            Files.writeString(manifest, manifest("2026-01-01T00:00:00Z", "aaa", "word"), StandardCharsets.UTF_8);
            String first = config.metadataSignature(new String[]{"--captions", "word", "--tts-delivery", "calm"});

            Files.writeString(manifest, manifest("2026-08-21T22:00:00Z", "bbb", "word"), StandardCharsets.UTF_8);
            String sameStableSettings = config.metadataSignature(
                    new String[]{"--captions", "word", "--tts-delivery", "calm"});
            require(first.equals(sameStableSettings),
                    "volatile provenance timestamps/artifact hashes must not change the P2 metadata signature");

            Files.writeString(manifest, manifest("2026-08-21T22:00:00Z", "ccc", "sentence"), StandardCharsets.UTF_8);
            String changedCaption = config.metadataSignature(
                    new String[]{"--captions", "sentence", "--tts-delivery", "calm"});
            require(!first.equals(changedCaption),
                    "meaningful P1 caption configuration changes must change the P2 metadata signature");
        } finally {
            deleteTree(dir);
        }
    }

    private static String manifest(String generatedAt, String artifactHash, String captions) {
        return "{\n"
                + "  \"schema\": \"urn:threadgens:provenance:v1\",\n"
                + "  \"generatedAt\": \"" + generatedAt + "\",\n"
                + "  \"disclosure\": \"AI-assisted fictional content\",\n"
                + "  \"content\": {\"origin\": \"ai\", \"platform\": \"reddit\", \"format\": \"confession\", \"llmModel\": \"llama3.1:8b\"},\n"
                + "  \"narration\": {\"engine\": \"kokoro\", \"voiceSelection\": \"series\", \"voiceSeries\": \"af_heart,af_bella\", \"delivery\": \"calm\", \"speed\": 0.920, \"language\": \"a\", \"sentencePauseMs\": 280},\n"
                + "  \"visuals\": {\"imageMode\": \"none\", \"imageCheckpoint\": \"\", \"captions\": \"" + captions + "\", \"captionTiming\": \"estimated from measured narration duration\", \"dynamicSceneChanges\": true},\n"
                + "  \"artifacts\": [{\"sha256\": \"" + artifactHash + "\"}]\n"
                + "}\n";
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
