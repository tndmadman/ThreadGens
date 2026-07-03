package redditTxtToImg;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class TextFileReader {
    private final List<String> lines = new ArrayList<>();
    private boolean hasFirstRandomEntry = false;
    private String firstRandomEntry = "";
    private int fallbackUserCounter = 1;

    public static TextFileReader fromFile(Path path) throws IOException {
        TextFileReader reader = new TextFileReader();
        reader.readTextFile(path);
        return reader;
    }

    public void readTextFile(Path path) throws IOException {
        lines.clear();
        hasFirstRandomEntry = false;
        firstRandomEntry = "";
        fallbackUserCounter = 1;
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    lines.add(line.trim());
                }
            }
        }
    }

    public String getEntry(int i) {
        return lines.get(i);
    }

    public String getRandomEntry(Random random) {
        if (lines.isEmpty()) {
            if (!hasFirstRandomEntry) {
                hasFirstRandomEntry = true;
                firstRandomEntry = "UnknownUser";
                return firstRandomEntry;
            }
            return nextFallbackUser();
        }

        String selected = lines.get(random.nextInt(lines.size()));
        if (!hasFirstRandomEntry) {
            hasFirstRandomEntry = true;
            firstRandomEntry = selected;
            return selected;
        }

        if (!sameText(selected, firstRandomEntry)) {
            return selected;
        }

        for (int attempt = 0; attempt < 25; attempt++) {
            String candidate = lines.get(random.nextInt(lines.size()));
            if (!sameText(candidate, firstRandomEntry)) {
                return candidate;
            }
        }

        return nextFallbackUser();
    }

    public List<String> getLines() {
        return new ArrayList<>(lines);
    }

    public int getSize() {
        return lines.size();
    }

    private String nextFallbackUser() {
        String fallback;
        do {
            fallback = "reply_user_" + fallbackUserCounter++;
        } while (sameText(fallback, firstRandomEntry));
        return fallback;
    }

    private static boolean sameText(String left, String right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }
}
