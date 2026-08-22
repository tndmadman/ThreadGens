package redditTxtToImg;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/** Canonical viewer-facing fingerprint used by the P2 pre-publish audit. */
final class PublishFingerprint {
    static final int SCHEMA_VERSION = 1;

    record CaptureInput(
            String platform,
            String format,
            String script,
            List<Path> artifactPaths,
            List<Path> imagePaths,
            List<Path> audioPaths,
            String voice,
            String ttsEngine,
            String metadataSignature,
            String videoCommand
    ) {
    }

    final String created;
    final String platform;
    final String format;
    final String script;
    final String scriptHash;
    final String artifactHash;
    final List<Long> visualHashes;
    final String voice;
    final String ttsEngine;
    final List<Double> segmentDurations;
    final double totalDuration;
    final String metadataHash;

    PublishFingerprint(
            String created,
            String platform,
            String format,
            String script,
            String scriptHash,
            String artifactHash,
            List<Long> visualHashes,
            String voice,
            String ttsEngine,
            List<Double> segmentDurations,
            double totalDuration,
            String metadataHash
    ) {
        this.created = clean(created, Instant.now().toString());
        this.platform = clean(platform, "unknown");
        this.format = clean(format, "unknown");
        this.script = script == null ? "" : script.trim();
        this.scriptHash = clean(scriptHash, sha256(this.script.getBytes(StandardCharsets.UTF_8)));
        this.artifactHash = clean(artifactHash, "");
        this.visualHashes = visualHashes == null ? List.of() : List.copyOf(visualHashes);
        this.voice = clean(voice, "unknown");
        this.ttsEngine = clean(ttsEngine, "unknown");
        this.segmentDurations = segmentDurations == null ? List.of() : List.copyOf(segmentDurations);
        this.totalDuration = Math.max(0.0, totalDuration);
        this.metadataHash = clean(metadataHash, "");
    }

    static PublishFingerprint capture(CaptureInput input) throws IOException, InterruptedException {
        if (input == null) {
            throw new IOException("Publish fingerprint input was null.");
        }
        String script = input.script() == null ? "" : input.script().trim();
        List<Path> artifacts = existing(input.artifactPaths());
        if (artifacts.isEmpty()) {
            throw new IOException("P2 publish audit requires at least one generated video artifact.");
        }

        List<Long> visuals = new ArrayList<>();
        for (Path image : existing(input.imagePaths())) {
            visuals.add(dHash(image));
        }

        List<Double> durations = new ArrayList<>();
        DynamicVideoGenerator media = new DynamicVideoGenerator(input.videoCommand(), 90, 30);
        for (Path audio : existing(input.audioPaths())) {
            durations.add(media.probeDurationSeconds(audio));
        }
        double total = 0.0;
        for (double value : durations) {
            total += Math.max(0.0, value);
        }
        if (total <= 0.0) {
            for (Path artifact : artifacts) {
                total += Math.max(0.0, media.probeDurationSeconds(artifact));
            }
        }

        String metadataSignature = input.metadataSignature() == null ? "" : input.metadataSignature().trim();
        String metadataHash = metadataSignature.isBlank()
                ? ""
                : sha256(metadataSignature.getBytes(StandardCharsets.UTF_8));

        return new PublishFingerprint(
                Instant.now().toString(),
                input.platform(),
                input.format(),
                script,
                sha256(script.getBytes(StandardCharsets.UTF_8)),
                combinedFileHash(artifacts),
                visuals,
                input.voice(),
                input.ttsEngine(),
                durations,
                total,
                metadataHash
        );
    }

    static PublishFingerprint forTest(
            String script,
            String artifactHash,
            long visualHash,
            String format,
            String voice,
            List<Double> durations,
            String metadataHash
    ) {
        double total = durations == null ? 0.0 : durations.stream().mapToDouble(Double::doubleValue).sum();
        return new PublishFingerprint(
                Instant.now().toString(), "reddit", format, script,
                sha256((script == null ? "" : script).getBytes(StandardCharsets.UTF_8)),
                artifactHash, List.of(visualHash), voice, "kokoro", durations, total, metadataHash);
    }

    String visualHashCsv() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < visualHashes.size(); i++) {
            if (i > 0) out.append(',');
            out.append(Long.toUnsignedString(visualHashes.get(i), 16));
        }
        return out.toString();
    }

    String pacingCsv() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segmentDurations.size(); i++) {
            if (i > 0) out.append(',');
            out.append(String.format(Locale.US, "%.3f", segmentDurations.get(i)));
        }
        return out.toString();
    }

    private static List<Path> existing(List<Path> paths) {
        if (paths == null) return List.of();
        List<Path> result = new ArrayList<>();
        for (Path path : paths) {
            if (path != null && Files.isRegularFile(path)) result.add(path);
        }
        return result;
    }

    private static String combinedFileHash(List<Path> paths) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path path : paths) {
                digest.update(path.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(path));
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static long dHash(Path imagePath) throws IOException {
        BufferedImage source = ImageIO.read(imagePath.toFile());
        if (source == null) {
            throw new IOException("Could not decode image for perceptual hash: " + imagePath);
        }
        BufferedImage scaled = new BufferedImage(9, 8, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, 9, 8, null);
        } finally {
            graphics.dispose();
        }
        long hash = 0L;
        int bit = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int left = scaled.getRaster().getSample(x, y, 0);
                int right = scaled.getRaster().getSample(x + 1, y, 0);
                if (left > right) hash |= (1L << bit);
                bit++;
            }
        }
        return hash;
    }

    static double visualSimilarity(List<Long> a, List<Long> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0.0;
        double sum = 0.0;
        int compared = 0;
        int count = Math.min(a.size(), b.size());
        for (int i = 0; i < count; i++) {
            int distance = Long.bitCount(a.get(i) ^ b.get(i));
            sum += 1.0 - (distance / 64.0);
            compared++;
        }
        return compared == 0 ? 0.0 : sum / compared;
    }

    static String sha256(byte[] bytes) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }

    private static String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
