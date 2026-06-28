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

    public static TextFileReader fromFile(Path path) throws IOException {
        TextFileReader reader = new TextFileReader();
        reader.readTextFile(path);
        return reader;
    }

    public void readTextFile(Path path) throws IOException {
        lines.clear();
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
            return "UnknownUser";
        }
        return lines.get(random.nextInt(lines.size()));
    }

    public List<String> getLines() {
        return new ArrayList<>(lines);
    }

    public int getSize() {
        return lines.size();
    }
}
