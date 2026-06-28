package redditTxtToImg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Stream;

public class RandomProfileName {
    private final List<String> profileImageNames = new ArrayList<>();
    private final Random random = new Random();

    public RandomProfileName(Path profileDirectory) {
        if (!Files.isDirectory(profileDirectory)) {
            return;
        }

        try (Stream<Path> files = Files.list(profileDirectory)) {
            files.filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(RandomProfileName::isSupportedImage)
                    .sorted()
                    .forEach(profileImageNames::add);
        } catch (IOException e) {
            System.err.println("Could not read profile image directory: " + profileDirectory);
        }
    }

    public String getRandomProfileName() {
        if (profileImageNames.isEmpty()) {
            return "";
        }
        return profileImageNames.get(random.nextInt(profileImageNames.size()));
    }

    private static boolean isSupportedImage(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif");
    }
}
