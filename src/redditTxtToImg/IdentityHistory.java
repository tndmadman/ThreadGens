package redditTxtToImg;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Persists synthetic identity use so names and avatars rotate across runs. */
final class IdentityHistory {
    private static final Map<Path, Object> JVM_LOCKS = new ConcurrentHashMap<>();

    record Identity(String name, String profileImage) {
    }

    private final Path historyFile;
    private final int historyLimit;
    private final boolean enabled;

    IdentityHistory(Path historyFile, int historyLimit, boolean enabled) {
        this.historyFile = historyFile == null ? Path.of("data", "identity_history.jsonl") : historyFile;
        this.historyLimit = Math.max(1, historyLimit);
        this.enabled = enabled;
    }

    List<Identity> selectAndRecord(
            List<String> names,
            List<String> profileImages,
            List<String> aiProfileImages,
            int count,
            String runKey
    ) throws IOException {
        int requested = Math.max(0, count);
        if (requested == 0) {
            return List.of();
        }
        if (!enabled) {
            return select(names, profileImages, aiProfileImages, requested, List.of());
        }

        if (historyFile.getParent() != null) {
            Files.createDirectories(historyFile.getParent());
        }
        Path lockFile = historyFile.resolveSibling(historyFile.getFileName() + ".lock");
        Path lockKey = lockFile.toAbsolutePath().normalize();
        synchronized (JVM_LOCKS.computeIfAbsent(lockKey, ignored -> new Object())) {
            return selectAndRecordLocked(
                    names, profileImages, aiProfileImages, requested, runKey, lockFile);
        }
    }

    private List<Identity> selectAndRecordLocked(
            List<String> names,
            List<String> profileImages,
            List<String> aiProfileImages,
            int requested,
            String runKey,
            Path lockFile
    ) throws IOException {
        try (FileChannel channel = FileChannel.open(
                lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            if (!lock.isValid()) {
                throw new IOException("Could not acquire identity history lock: " + lockFile);
            }
            List<Entry> history = readHistory();
            List<Identity> selected = select(names, profileImages, aiProfileImages, requested, history);
            appendAndTrim(history, selected, runKey);
            return selected;
        }
    }

    private List<Identity> select(
            List<String> names,
            List<String> profileImages,
            List<String> aiProfileImages,
            int count,
            List<Entry> history
    ) {
        List<String> namePool = cleanPool(names);
        List<String> imagePool = cleanPool(profileImages);
        List<String> aiPool = cleanPool(aiProfileImages);
        if (namePool.isEmpty()) {
            for (int i = 1; i <= Math.max(24, count); i++) {
                namePool.add("fictional_user_" + i);
            }
        }
        if (imagePool.isEmpty()) {
            imagePool.add("");
        }

        Map<String, Integer> nameRecency = recency(history, true);
        Map<String, Integer> imageRecency = recency(history, false);
        Map<String, Integer> usedNames = new HashMap<>();
        Map<String, Integer> usedImages = new HashMap<>();
        List<Identity> result = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            List<String> preferredImages = aiPool.isEmpty() ? imagePool : aiPool;
            String name = chooseLeastRecent(namePool, nameRecency, usedNames);
            String image = chooseLeastRecent(preferredImages, imageRecency, usedImages);
            usedNames.merge(normalize(name), 1, Integer::sum);
            usedImages.merge(normalize(image), 1, Integer::sum);
            result.add(new Identity(name, image));
        }
        return List.copyOf(result);
    }

    private static String chooseLeastRecent(
            List<String> candidates,
            Map<String, Integer> recency,
            Map<String, Integer> usedThisRun
    ) {
        return candidates.stream()
                .min(Comparator
                        .comparingInt((String value) -> usedThisRun.getOrDefault(normalize(value), 0))
                        .thenComparingInt(value -> recency.getOrDefault(normalize(value), -1))
                        .thenComparing(String::compareToIgnoreCase))
                .orElse("");
    }

    private static Map<String, Integer> recency(List<Entry> history, boolean names) {
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < history.size(); i++) {
            Entry entry = history.get(i);
            result.put(normalize(names ? entry.name() : entry.image()), i);
        }
        return result;
    }

    private List<Entry> readHistory() throws IOException {
        if (!Files.exists(historyFile)) {
            return new ArrayList<>();
        }
        List<Entry> result = new ArrayList<>();
        List<String> lines = Files.readAllLines(historyFile, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            try {
                String name = JsonText.extractString(line, "name");
                String image = JsonText.extractString(line, "image");
                String run = JsonText.extractString(line, "run");
                String timestamp = JsonText.extractString(line, "timestamp");
                if (name == null && image == null) {
                    throw new IOException("history row has neither name nor image");
                }
                result.add(new Entry(name == null ? "" : name, image == null ? "" : image,
                        run == null ? "" : run, timestamp == null ? "" : timestamp));
            } catch (IOException | RuntimeException e) {
                throw new IOException("Identity history is malformed at line " + (i + 1)
                        + " in " + historyFile
                        + ". P1 fails closed instead of forgetting recent identities.", e);
            }
        }
        if (result.size() > historyLimit) {
            return new ArrayList<>(result.subList(result.size() - historyLimit, result.size()));
        }
        return result;
    }

    private void appendAndTrim(List<Entry> history, List<Identity> selected, String runKey) throws IOException {
        String run = runKey == null || runKey.isBlank() ? UUID.randomUUID().toString() : runKey.trim();
        String timestamp = Instant.now().toString();
        for (Identity identity : selected) {
            history.add(new Entry(identity.name(), identity.profileImage(), run, timestamp));
        }
        int from = Math.max(0, history.size() - historyLimit);
        List<String> lines = new ArrayList<>();
        for (Entry entry : history.subList(from, history.size())) {
            lines.add("{\"timestamp\":" + JsonText.quote(entry.timestamp())
                    + ",\"run\":" + JsonText.quote(entry.run())
                    + ",\"name\":" + JsonText.quote(entry.name())
                    + ",\"image\":" + JsonText.quote(entry.image()) + "}");
        }
        Path temp = historyFile.resolveSibling(historyFile.getFileName() + ".tmp");
        Files.write(temp, lines, StandardCharsets.UTF_8);
        try {
            Files.move(temp, historyFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, historyFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<String> cleanPool(List<String> input) {
        Set<String> result = new LinkedHashSet<>();
        if (input != null) {
            for (String value : input) {
                if (value != null && !value.isBlank()) {
                    result.add(value.trim());
                }
            }
        }
        return new ArrayList<>(result);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record Entry(String name, String image, String run, String timestamp) {
    }
}
