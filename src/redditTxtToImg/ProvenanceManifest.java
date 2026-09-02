package redditTxtToImg;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes machine-readable provenance and user-facing disclosure metadata. */
final class ProvenanceManifest {
    static final String SCHEMA = "urn:threadgens:provenance:v1";
    static final String GENERATOR_VERSION = "0.7.0-p0-p1-p2-integrated";

    private ProvenanceManifest() {
    }

    static Map<String, String> videoMetadata(
            P0Runner.RunConfig config,
            ContentFormat format,
            String segmentLabel
    ) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("title", "ThreadGens " + format.label() + " " + segmentLabel);
        metadata.put("artist", "ThreadGens");
        metadata.put("comment", config.disclosureText);
        metadata.put("description", config.disclosureText);
        metadata.put("encoded_by", "ThreadGens P1 provenance pipeline");
        return metadata;
    }

    static Path write(
            P0Runner.RunConfig config,
            ContentFormat format,
            int artifactCount,
            NoveltyGuard.Result noveltyResult
    ) throws IOException {
        return write(config, format, null, artifactCount, noveltyResult);
    }

    static Path write(
            P0Runner.RunConfig config,
            ContentFormat format,
            ContentVariant variant,
            int artifactCount,
            NoveltyGuard.Result noveltyResult
    ) throws IOException {
        Files.createDirectories(config.metadataDirectory);
        Path manifest = config.metadataDirectory.resolve(config.outputPrefix + "-provenance.json");
        String timestamp = Instant.now().toString();

        List<Artifact> artifacts = collectArtifacts(config, artifactCount);
        List<VoiceUse> voiceUses = collectVoiceUses(config, artifactCount);
        StringBuilder json = new StringBuilder();
        json.append("{\n")
                .append("  \"schema\": ").append(JsonText.quote(SCHEMA)).append(",\n")
                .append("  \"generatedAt\": ").append(JsonText.quote(timestamp)).append(",\n")
                .append("  \"generator\": {\"name\": \"ThreadGens\", \"version\": ")
                .append(JsonText.quote(GENERATOR_VERSION)).append("},\n")
                .append("  \"disclosure\": ").append(JsonText.quote(config.disclosureText)).append(",\n")
                .append("  \"content\": {\n")
                .append("    \"origin\": ").append(JsonText.quote(config.contentOrigin)).append(",\n")
                .append("    \"platform\": ").append(JsonText.quote(config.platform)).append(",\n")
                .append("    \"format\": ").append(JsonText.quote(format.id())).append(",\n")
                .append("    \"formatVariant\": ").append(JsonText.quote(
                        variant == null ? "unknown" : variant.id())).append(",\n")
                .append("    \"pacingFamily\": ").append(JsonText.quote(
                        variant == null ? "unknown" : variant.pacingFamily())).append(",\n")
                .append("    \"renderStyle\": ").append(JsonText.quote(config.renderStyle)).append(",\n")
                .append("    \"pacingProfile\": ").append(JsonText.quote(config.pacingProfile)).append(",\n")
                .append("    \"title\": ").append(JsonText.quote(config.postTitle)).append(",\n")
                .append("    \"topic\": ").append(JsonText.quote(config.topic)).append(",\n")
                .append("    \"scriptSha256\": ").append(JsonText.quote(
                        Files.isRegularFile(config.commentsFile) ? sha256(config.commentsFile) : "")).append(",\n")
                .append("    \"llmModel\": ").append(JsonText.quote(config.llmModel)).append(",\n")
                .append("    \"noveltyEnabled\": ").append(config.noveltyEnabled).append(",\n")
                .append("    \"noveltyScore\": ")
                .append(noveltyResult == null ? "null" : noveltyResult.noveltyScore()).append("\n")
                .append("  },\n")
                .append("  \"narration\": {\n")
                .append("    \"engine\": ").append(JsonText.quote(config.ttsEngine)).append(",\n")
                .append("    \"voiceSelection\": ").append(JsonText.quote(config.voiceSelection)).append(",\n")
                .append("    \"voiceSeries\": ").append(JsonText.quote(portableVoiceSeries(config.voiceSeries))).append(",\n")
                .append("    \"delivery\": ").append(JsonText.quote(config.ttsDelivery)).append(",\n")
                .append("    \"speed\": ").append(decimal(config.ttsSpeed)).append(",\n")
                .append("    \"language\": ").append(JsonText.quote(config.ttsLanguage)).append(",\n")
                .append("    \"sentencePauseMs\": ").append(config.ttsSentencePauseMs).append(",\n")
                .append("    \"segments\": ").append(voiceUsesJson(voiceUses)).append("\n")
                .append("  },\n")
                .append("  \"visuals\": {\n")
                .append("    \"imageMode\": ").append(JsonText.quote(config.imageMode)).append(",\n")
                .append("    \"imageCheckpoint\": ").append(JsonText.quote(
                        "none".equalsIgnoreCase(config.imageMode) ? "" : config.imageCheckpoint)).append(",\n")
                .append("    \"captions\": ").append(JsonText.quote(
                        config.createVideo ? config.captionMode : "not-rendered")).append(",\n")
                .append("    \"captionTiming\": ").append(JsonText.quote(
                        config.createVideo ? "estimated from measured narration duration" : "not-rendered"))
                .append(",\n")
                .append("    \"dynamicSceneChanges\": ")
                .append(config.createVideo && config.renderedSceneCounts.stream().anyMatch(count -> count > 1))
                .append(",\n")
                .append("    \"sceneCounts\": ").append(integerListJson(config.renderedSceneCounts)).append("\n")
                .append("  },\n")
                .append("  \"identities\": {\n")
                .append("    \"synthetic\": true,\n")
                .append("    \"historyEnabled\": ").append(config.identityHistoryEnabled).append(",\n")
                .append("    \"historyFile\": ").append(JsonText.quote(portablePath(config.identityHistoryFile))).append("\n")
                .append("  },\n")
                .append("  \"integrity\": {\"syntheticEngagementHidden\": ")
                .append(config.integritySanitize)
                .append(", \"artifactHashAlgorithm\": \"SHA-256\", \"cryptographicallySigned\": false},\n")
                .append("  \"artifacts\": ").append(artifactsJson(artifacts)).append("\n")
                .append("}\n");

        Files.writeString(manifest, json.toString(), StandardCharsets.UTF_8);
        Path finalVideo = config.videoDirectory.resolve(config.finalVideoName);
        if (Files.exists(finalVideo)) {
            Path adjacent = finalVideo.resolveSibling(finalVideo.getFileName() + ".provenance.json");
            Files.copy(manifest, adjacent, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return manifest;
    }

    private static List<Artifact> collectArtifacts(P0Runner.RunConfig config, int artifactCount) throws IOException {
        List<Artifact> artifacts = new ArrayList<>();
        for (int i = 0; i < artifactCount; i++) {
            addArtifact(artifacts, "image", config.imagePath(i));
            addArtifact(artifacts, "audio", config.audioPath(i));
            addArtifact(artifacts, "narration", config.audioTextPath(i));
            addArtifact(artifacts, "voice-metadata", config.voiceMetadataPath(i));
            addArtifact(artifacts, "video-segment", config.videoPath(i));
        }
        addArtifact(artifacts, "video-final", config.videoDirectory.resolve(config.finalVideoName));
        return artifacts;
    }

    private static void addArtifact(List<Artifact> artifacts, String type, Path path) throws IOException {
        if (path != null && Files.isRegularFile(path)) {
            artifacts.add(new Artifact(type, portablePath(path), Files.size(path), sha256(path)));
        }
    }

    private static List<VoiceUse> collectVoiceUses(P0Runner.RunConfig config, int count) throws IOException {
        List<VoiceUse> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Path sidecar = config.voiceMetadataPath(i);
            if (!Files.exists(sidecar)) {
                continue;
            }
            try {
                String json = Files.readString(sidecar, StandardCharsets.UTF_8);
                String engine = JsonText.extractString(json, "engine");
                String voice = JsonText.extractString(json, "voice");
                result.add(new VoiceUse(
                        i,
                        engine == null ? config.ttsEngine : engine,
                        voice == null ? "" : voice));
            } catch (IOException e) {
                result.add(new VoiceUse(i, config.ttsEngine, "unreadable-sidecar"));
            }
        }
        return result;
    }

    private static String artifactsJson(List<Artifact> artifacts) {
        StringBuilder result = new StringBuilder("[\n");
        for (int i = 0; i < artifacts.size(); i++) {
            Artifact artifact = artifacts.get(i);
            result.append("    {\"type\": ").append(JsonText.quote(artifact.type()))
                    .append(", \"path\": ").append(JsonText.quote(artifact.path()))
                    .append(", \"bytes\": ").append(artifact.bytes())
                    .append(", \"sha256\": ").append(JsonText.quote(artifact.sha256())).append('}');
            if (i + 1 < artifacts.size()) {
                result.append(',');
            }
            result.append('\n');
        }
        return result.append("  ]").toString();
    }

    private static String voiceUsesJson(List<VoiceUse> voices) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < voices.size(); i++) {
            VoiceUse voice = voices.get(i);
            if (i > 0) {
                result.append(',');
            }
            result.append("{\"index\":").append(voice.index())
                    .append(",\"engine\":").append(JsonText.quote(voice.engine()))
                    .append(",\"voice\":").append(JsonText.quote(voice.voice())).append('}');
        }
        return result.append(']').toString();
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }

    private static String decimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static String integerListJson(List<Integer> values) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                result.append(',');
            }
            result.append(values.get(i));
        }
        return result.append(']').toString();
    }

    private static String portablePath(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path display = absolute.startsWith(workingDirectory)
                ? workingDirectory.relativize(absolute)
                : path.getFileName();
        return display == null ? "" : display.toString().replace('\\', '/');
    }

    private static String portableVoiceSeries(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        List<String> labels = new ArrayList<>();
        for (String item : value.split("[,;]")) {
            String label = item.trim();
            if (label.isBlank()) {
                continue;
            }
            boolean looksLikePath = label.contains("/") || label.contains("\\")
                    || label.toLowerCase(java.util.Locale.ROOT).endsWith(".onnx");
            if (looksLikePath) {
                try {
                    label = portablePath(Path.of(label));
                } catch (RuntimeException ignored) {
                    // Keep an invalid label visible so configuration errors remain diagnosable.
                }
            }
            labels.add(label);
        }
        return String.join(",", labels);
    }

    private record Artifact(String type, String path, long bytes, String sha256) {
    }

    private record VoiceUse(int index, String engine, String voice) {
    }
}
