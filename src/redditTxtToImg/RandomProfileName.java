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
    private static final Path RENDER_PROFILE_ROOT = ProfileImages.CACHE_ROOT;
    private static final int MAX_SCAN_DEPTH = 6;
    private static final String DEFAULT_AI_PROFILE_PREFIX = "tg_ai_profile_";

    private final List<String> profileImageNames = new ArrayList<>();
    private final List<String> aiProfileImageNames = new ArrayList<>();
    private final Random random = new Random();
    private boolean hasFirstRandomProfileName = false;
    private String firstRandomProfileName = "";

    public RandomProfileName(Path profileDirectory) {
        Set<String> seen = new HashSet<>();

        try {
            Files.createDirectories(RENDER_PROFILE_ROOT);
        } catch (IOException e) {
            System.err.println("Could not create profile image render directory: " + RENDER_PROFILE_ROOT);
        }

        scanProfileDirectory(profileDirectory, seen);

        if (profileImageNames.isEmpty()) {
            createFallbackProfileImages(seen);
        }

        if (!aiProfileImageNames.isEmpty()) {
            System.out.println("AI profile pool enabled: " + aiProfileImageNames.size()
                    + " valid profile(s); all slides will use AI profiles.");
        }
    }

    public String getRandomProfileName() {
        List<String> preferredPool = aiProfileImageNames.isEmpty()
                ? profileImageNames
                : aiProfileImageNames;
        if (preferredPool.isEmpty()) {
            return "";
        }

        if (!hasFirstRandomProfileName) {
            String selected = chooseRandom(preferredPool);
            hasFirstRandomProfileName = true;
            firstRandomProfileName = selected;
            String profileType = aiProfileImageNames.contains(selected) ? "AI" : "fallback";
            System.out.println("Original post profile selected: " + selected + " [" + profileType + "]");
            return selected;
        }

        if (preferredPool.size() == 1) {
            return preferredPool.get(0);
        }

        for (int attempt = 0; attempt < 25; attempt++) {
            String candidate = chooseRandom(preferredPool);
            if (!sameProfileName(candidate, firstRandomProfileName)) {
                return candidate;
            }
        }

        for (String candidate : preferredPool) {
            if (!sameProfileName(candidate, firstRandomProfileName)) {
                return candidate;
            }
        }
        return preferredPool.get(0);
    }

    List<String> profileImageNames() {
        return List.copyOf(profileImageNames);
    }

    List<String> aiProfileImageNames() {
        return List.copyOf(aiProfileImageNames);
    }

    private String chooseRandom(List<String> profileNames) {
        return profileNames.get(random.nextInt(profileNames.size()));
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
        if (!isLoadableImage(sourcePath)) {
            System.err.println("Skipping invalid profile image: " + sourcePath);
            return;
        }

        try {
            String renderName = makeRenderableProfileName(sourcePath);
            if (renderName == null || renderName.isBlank()) {
                return;
            }
            if (seen.add(renderName)) {
                profileImageNames.add(renderName);
                if (isAiProfileSource(sourcePath)) {
                    aiProfileImageNames.add(renderName);
                }
            }
        } catch (IOException e) {
            System.err.println("Could not prepare profile image: " + sourcePath + " (" + e.getMessage() + ")");
        }
    }

    private String makeRenderableProfileName(Path sourcePath) throws IOException {
        Path source = sourcePath.toAbsolutePath().normalize();
        Path renderRoot = RENDER_PROFILE_ROOT.toAbsolutePath().normalize();
        Path legacyRoot = ProfileImages.LEGACY_ASSET_ROOT.toAbsolutePath().normalize();

        if (source.startsWith(legacyRoot)) {
            return toForwardSlashes(legacyRoot.relativize(source).toString());
        }

        if (source.startsWith(renderRoot)) {
            return toRendererRelativeName(renderRoot.relativize(source).toString());
        }

        Path importDir = renderRoot.resolve("imported_profiles");
        Files.createDirectories(importDir);

        String filename = source.getFileName().toString();
        String safeName = safeFilename(filename);
        String uniqueSuffix = Integer.toHexString(source.toString().hashCode());
        String importedName = addSuffixBeforeExtension(safeName, "_" + uniqueSuffix);
        Path target = importDir.resolve(importedName);

        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);

        return toRendererRelativeName(renderRoot.relativize(target).toString());
    }

    private void createFallbackProfileImages(Set<String> seen) {
        Path fallbackDir = RENDER_PROFILE_ROOT.resolve("generated_fallback");
        try {
            Files.createDirectories(fallbackDir);
            for (int i = 1; i <= 24; i++) {
                String filename = String.format(Locale.ROOT, "reply_profile_%03d.png", i);
                Path output = fallbackDir.resolve(filename);
                if (!Files.exists(output) || !isLoadableImage(output)) {
                    writeFallbackAvatar(output, i);
                }
                String renderName = toRendererRelativeName(RENDER_PROFILE_ROOT.relativize(output).toString());
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

    private static boolean isLoadableImage(Path path) {
        try {
            return ImageIO.read(path.toFile()) != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isAiProfileSource(Path sourcePath) {
        if (sourcePath == null || sourcePath.getFileName() == null) {
            return false;
        }
        String originalFilename = sourcePath.getFileName().toString();
        String filename = originalFilename.toLowerCase(Locale.ROOT);
        if (filename.startsWith(DEFAULT_AI_PROFILE_PREFIX)) {
            return true;
        }
        for (Path part : sourcePath) {
            if ("ai".equalsIgnoreCase(part.toString())) {
                return true;
            }
        }
        return !filename.startsWith("tg_profile_");
    }

    private static boolean sameProfileName(String left, String right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }

    private static String toRendererRelativeName(String cacheRelativeName) {
        Path rendererRelativePath = Path.of("..", "..", RENDER_PROFILE_ROOT.toString(), cacheRelativeName);
        return toForwardSlashes(rendererRelativePath.toString());
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
