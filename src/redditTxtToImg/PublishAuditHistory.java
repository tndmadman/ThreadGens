package redditTxtToImg;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Persistent history of videos that passed or were explicitly warned through P2. */
final class PublishAuditHistory {
    private static final ConcurrentHashMap<Path, ReentrantLock> PROCESS_LOCKS = new ConcurrentHashMap<>();

    record Entry(PublishFingerprint fingerprint, String status, int risk) {
    }

    static final class LockHandle implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock fileLock;
        private final ReentrantLock processLock;
        private boolean closed;

        private LockHandle(FileChannel channel, FileLock fileLock, ReentrantLock processLock) {
            this.channel = channel;
            this.fileLock = fileLock;
            this.processLock = processLock;
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            IOException failure = null;
            try {
                if (fileLock != null && fileLock.isValid()) fileLock.release();
            } catch (IOException e) {
                failure = e;
            }
            try {
                if (channel != null && channel.isOpen()) channel.close();
            } catch (IOException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            } finally {
                processLock.unlock();
            }
            if (failure != null) throw failure;
        }
    }

    private final Path file;
    private final int limit;

    PublishAuditHistory(Path file, int limit) {
        this.file = file == null ? Path.of("data", "publish_history.jsonl") : file;
        this.limit = Math.max(1, limit);
    }

    LockHandle lockExclusive() throws IOException {
        Path key = file.toAbsolutePath().normalize();
        ReentrantLock processLock = PROCESS_LOCKS.computeIfAbsent(key, ignored -> new ReentrantLock(true));
        processLock.lock();
        FileChannel channel = null;
        try {
            Path lockFile = key.resolveSibling(key.getFileName().toString() + ".lock");
            if (lockFile.getParent() != null) Files.createDirectories(lockFile.getParent());
            channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.lock();
            return new LockHandle(channel, lock, processLock);
        } catch (IOException | RuntimeException e) {
            if (channel != null) {
                try { channel.close(); } catch (IOException closeFailure) { e.addSuppressed(closeFailure); }
            }
            processLock.unlock();
            throw e;
        }
    }

    List<Entry> load() throws IOException {
        if (!Files.exists(file)) return List.of();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isBlank()) continue;
            try {
                entries.add(parse(line));
            } catch (IOException | RuntimeException e) {
                throw new IOException("Publish audit history is malformed at line " + (i + 1)
                        + " in " + file + ". P2 fails closed instead of treating corrupt history as empty.", e);
            }
        }
        int start = Math.max(0, entries.size() - limit);
        return List.copyOf(entries.subList(start, entries.size()));
    }

    void record(PublishFingerprint fingerprint, String status, int risk) throws IOException {
        if (fingerprint == null) return;
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, serialize(fingerprint, status, risk) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        compact();
    }

    Path file() {
        return file;
    }

    private void compact() throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.size() <= limit) return;
        int start = Math.max(0, lines.size() - limit);
        Files.write(file, lines.subList(start, lines.size()), StandardCharsets.UTF_8);
    }

    static String serialize(PublishFingerprint fp, String status, int risk) {
        return "{"
                + q("schema", String.valueOf(PublishFingerprint.SCHEMA_VERSION)) + ","
                + q("created", fp.created) + ","
                + q("platform", fp.platform) + ","
                + q("format", fp.format) + ","
                + q("script_b64", enc(fp.script)) + ","
                + q("script_hash", fp.scriptHash) + ","
                + q("artifact_hash", fp.artifactHash) + ","
                + q("visuals", fp.visualHashCsv()) + ","
                + q("identities", fp.identityHashCsv()) + ","
                + q("voice_b64", enc(fp.voice)) + ","
                + q("tts", fp.ttsEngine) + ","
                + q("pacing", fp.pacingCsv()) + ","
                + q("total_duration", String.valueOf(fp.totalDuration)) + ","
                + q("metadata_hash", fp.metadataHash) + ","
                + q("status", status == null ? "PASS" : status) + ","
                + q("risk", String.valueOf(Math.max(0, Math.min(100, risk))))
                + "}";
    }

    private static Entry parse(String line) throws IOException {
        int schema = Integer.parseInt(required(line, "schema"));
        if (schema < 1 || schema > PublishFingerprint.SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported schema " + schema);
        }

        String identitiesValue = JsonText.extractString(line, "identities");
        if (schema >= 2 && identitiesValue == null) {
            throw new IllegalArgumentException("missing field: identities");
        }

        String script = dec(required(line, "script_b64"));
        String voice = dec(required(line, "voice_b64"));
        List<Long> visuals = parseHashes(required(line, "visuals"));
        List<Long> identities = schema >= 2 ? parseHashes(identitiesValue) : List.of();
        List<Double> pacing = parseDoubles(required(line, "pacing"));
        PublishFingerprint fp = new PublishFingerprint(
                required(line, "created"), required(line, "platform"), required(line, "format"), script,
                required(line, "script_hash"), required(line, "artifact_hash"), visuals, identities, voice,
                required(line, "tts"), pacing, Double.parseDouble(required(line, "total_duration")),
                required(line, "metadata_hash"));
        return new Entry(fp, required(line, "status"), Integer.parseInt(required(line, "risk")));
    }

    private static String required(String line, String key) throws IOException {
        String value = JsonText.extractString(line, key);
        if (value == null) throw new IllegalArgumentException("missing field: " + key);
        return value;
    }

    private static List<Long> parseHashes(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<Long> values = new ArrayList<>();
        for (String token : csv.split(",")) values.add(Long.parseUnsignedLong(token, 16));
        return List.copyOf(values);
    }

    private static List<Double> parseDoubles(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<Double> values = new ArrayList<>();
        for (String token : csv.split(",")) values.add(Double.parseDouble(token));
        return List.copyOf(values);
    }

    private static String q(String key, String value) {
        return "\"" + key + "\":\"" + escape(value == null ? "" : value) + "\"";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String enc(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String dec(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
