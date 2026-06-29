package redditTxtToImg;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

public class RandomProfileName {
    private static final Path RENDER_PROFILE_ROOT = Path.of("assets", "pfp");
    private static final int MAX_SCAN_DEPTH = 6;

    private final List<String> profileImageNames = new ArrayList<>();
    private final Random random = new Random();

    public RandomProfileName(Path profileDirectory) {
        Set<String> seen = new HashSet<>();

        try {
            Files.createDirectories(RENDER_PROFILE_ROOT);
        } catch (IOException e) {
            System.err.println("Could not create profile image render directory: " + RENDER_PROFILE_ROOT);
        }

        scanProfileDirectory(profileDirectory, seen);

        if (!samePath(profileDirectory, RENDER_PROFILE_ROOT)) {
            scanProfileDirectory(RENDER_PROFILE_ROOT, seen);
        }

        if (profileImageNames.isEmpty()) {
            createFallbackProfileImages(seen);
        }
    }

    public String getRandomProfileName() {
        if (profileImageNames.isEmpty()) {
            return "";
        }
        return profileImageNames.get(random.nextInt(profileImageNames.size()));
    }

    private void scanProfileDirectory(Path profileDirectory, Set<String> seen) {
        if (profileDirectory == null || !Files.isDirectory(profileDirectory)) {
            return;
        }

        try (Stream<Path> files = Files.walk(profileDirectory, MAX_SCAN_DEPTH)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> isSupportedImage(path.getFileName().toString()))
                    .sorted()
                    .forEach(path -> addProfileImage(path, seen));
        } catch (IOException e) {
            System.err.println("Could not read profile image directory: " + profileDirectory);
        }
    }

    private void addProfileImage(Path sourcePath, Set<String> seen) {
        try {
            String renderName = makeRenderableProfileName(sourcePath);
            if (renderName == null || renderName.isBlank()) {
                return;
            }
            if (seen.add(renderName)) {
                profileImageNames.add(renderName);
            }
        } catch (IOException e) {
            System.err.println("Could not prepare profile image: " + sourcePath);
        }
    }

    private String makeRenderableProfileName(Path sourcePath) throws IOException {
        Path source = sourcePath.toAbsolutePath().normalize();
        Path renderRoot = RENDER_PROFILE_ROOT.toAbsolutePath().normalize();

        if (source.startsWith(renderRoot)) {
            return toForwardSlashes(renderRoot.relativize(source).toString());
        }

        Path importDir = RENDER_PROFILE_ROOT.resolve("imported_profiles");
        Files.createDirectories(importDir);

        String filename = source.getFileName().toString();
        String safeName = safeFilename(filename);
        String uniqueSuffix = Integer.toHexString(source.toString().hashCode());
        String importedName = addSuffixBeforeExtension(safeName, "_" + uniqueSuffix);
        Path target = importDir.resolve(importedName);

        if (!Files.exists(target)) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }

        return toForwardSlashes(RENDER_PROFILE_ROOT.toAbsolutePath().normalize().relativize(target.toAbsolutePath().normalize()).toString());
    }

    private void createFallbackProfileImages(Set<String> seen) {
        Path fallbackDir = RENDER_PROFILE_ROOT.resolve("generated_fallback");
        try {
            Files.createDirectories(fallbackDir);
            for (int i = 1; i <= 24; i++) {
                String filename = String.format(Locale.ROOT, "reply_profile_%03d.png", i);
                Path output = fallbackDir.resolve(filename);
                if (!Files.exists(output)) {
                    writeFallbackAvatar(output, i);
                }
                String renderName = toForwardSlashes(RENDER_PROFILE_ROOT.relativize(output).toString());
                if (seen.add(renderName)) {
                    profileImageNames.add(renderName);
                }
            }
        } catch (IOException e) {
            System.err.println("Could not create fallback profile images: " + e.getMessage());
        }
    }

    private void writeFallbackAvatar(Path output, int index) throws IOException {
        int size = 256;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Color a = Color.getHSBColor((index * 0.071f) % 1.0f, 0.42f, 0.80f);
        Color b = Color.getHSBColor((index * 0.071f + 0.18f) % 1.0f, 0.50f, 0.50f);
        g.setPaint(new GradientPaint(0, 0, a, size, size, b));
        g.fillRect(0, 0, size, size);

        g.setColor(new Color(255, 255, 255, 55));
        g.fillOval(26, 18, 204, 204);

        Color skin = new Color(225, 190, 160);
        g.setColor(skin);
        g.fillOval(78, 52, 100, 100);
        g.fillRoundRect(58, 145, 140, 90, 70, 70);

        g.setColor(new Color(60, 45, 38));
        g.fillArc(72, 40, 112, 80, 0, 180);

        g.setColor(new Color(30, 30, 30));
        g.fillOval(105, 92, 8, 8);
        g.fillOval(142, 92, 8, 8);
        g.drawArc(110, 110, 36, 20, 200, 140);

        g.setColor(new Color(255, 255, 255, 200));
        g.setFont(new Font("Arial", Font.BOLD, 34));
        String label = String.valueOf((char) ('a' + ((index - 1) % 26)));
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(label, (size - metrics.stringWidth(label)) / 2, 224);

        g.dispose();
        ImageIO.write(image, "png", output.toFile());
    }

    private static boolean isSupportedImage(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif");
    }

    private static boolean samePath(Path a, Path b) {
        if (a == null || b == null) {
            return false;
        }
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

    private static String toForwardSlashes(String value) {
        return value.replace('\\', '/');
    }

    private static String safeFilename(String value) {
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() ? "profile.png" : cleaned;
    }

    private static String addSuffixBeforeExtension(String filename, String suffix) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot >= filename.length() - 1) {
            return filename + suffix;
        }
        return filename.substring(0, dot) + suffix + filename.substring(dot);
    }
}
