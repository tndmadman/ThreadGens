package redditTxtToImg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Persistent history of videos that passed or were explicitly warned through P2. */
final class PublishAuditHistory {
    private static final Pattern FIELD =
            Pattern.compile("\"([a-zA-Z0-9_]+)\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"");

    record Entry(PublishFingerprint fingerprint, String status, int risk) {
    }

    private final Path file;
    private final int limit;

    PublishAuditHistory(Path file, int limit) {
        this.file = file == null ? Path.of("data", "publish_history.jsonl") : file;
        this.limit = Math.max(1, limit);
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
            } catch (RuntimeException e) {
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
                + q("voice_b64", enc(fp.voice)) + ","
                + q("tts", fp.ttsEngine) + ","
                + q("pacing", fp.pacingCsv()) + ","
                + q("total_duration", String.valueOf(fp.totalDuration)) + ","
                + q("metadata_hash", fp.metadataHash) + ","
                + q("status", status == null ? "PASS" : status) + ","
                + q("risk", String.valueOf(Math.max(0, Math.min(100, risk))))
                + "}";
    }

    private static Entry parse(String line) {
        Map<String, String> values = new HashMap<>();
        Matcher matcher = FIELD.matcher(line);
        while (matcher.find()) values.put(matcher.group(1), unescape(matcher.group(2)));
        require(values, "schema", "created", "platform", "format", "script_b64", "script_hash",
                "artifact_hash", "visuals", "voice_b64", "tts", "pacing", "total_duration",
                "metadata_hash", "status", "risk");
        if (Integer.parseInt(values.get("schema")) != PublishFingerprint.SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported schema " + values.get("schema"));
        }
        String script = dec(values.get("script_b64"));
        String voice = dec(values.get("voice_b64"));
        List<Long> visuals = parseVisuals(values.get("visuals"));
        List<Double> pacing = parseDoubles(values.get("pacing"));
        PublishFingerprint fp = new PublishFingerprint(
                values.get("created"), values.get("platform"), values.get("format"), script,
                values.get("script_hash"), values.get("artifact_hash"), visuals, voice,
                values.get("tts"), pacing, Double.parseDouble(values.get("total_duration")),
                values.get("metadata_hash"));
        return new Entry(fp, values.get("status"), Integer.parseInt(values.get("risk")));
    }

    private static List<Long> parseVisuals(String csv) {
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

    private static void require(Map<String, String> values, String... keys) {
        for (String key : keys) {
            if (!values.containsKey(key)) throw new IllegalArgumentException("missing field: " + key);
        }
    }

    private static String q(String key, String value) {
        return "\"" + key + "\":\"" + escape(value == null ? "" : value) + "\"";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder();
        boolean escaped = false;
        for (char c : value.toCharArray()) {
            if (escaped) {
                out.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                out.append(c);
            }
        }
        if (escaped) out.append('\\');
        return out.toString();
    }

    private static String enc(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String dec(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
