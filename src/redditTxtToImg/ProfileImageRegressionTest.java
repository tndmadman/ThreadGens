package redditTxtToImg;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import javax.imageio.ImageIO;

public final class ProfileImageRegressionTest {
    private ProfileImageRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        Path testRoot = Path.of("output", "profile-regression-test");
        deleteTree(testRoot);
        Files.createDirectories(testRoot);

        Path procedural = testRoot.resolve("tg_profile_0001.png");
        Path ai = testRoot.resolve("custom_face_0001.png");
        Path invalidAi = testRoot.resolve("tg_ai_profile_broken.png");
        Path staleCacheAi = ProfileImages.CACHE_ROOT.resolve("imported_profiles")
                .resolve("tg_ai_profile_stale.png");

        writeSolidPng(procedural, Color.GREEN);
        writeSolidPng(ai, Color.RED);
        Files.writeString(invalidAi, "not an image");
        Files.createDirectories(staleCacheAi.getParent());
        writeSolidPng(staleCacheAi, Color.MAGENTA);

        RandomProfileName selector = new RandomProfileName(testRoot);
        String first = selector.getRandomProfileName();
        String second = selector.getRandomProfileName();

        assertTrue(first.contains("custom_face_0001"),
                "Original post did not select the valid AI profile: " + first);
        assertEquals(first, second,
                "A single AI profile should be reused instead of falling back to a procedural avatar.");
        assertPixel(first, Color.RED, "Selected AI profile did not resolve to the source image.");

        writeSolidPng(ai, Color.BLUE);
        RandomProfileName refreshedSelector = new RandomProfileName(testRoot);
        String refreshed = refreshedSelector.getRandomProfileName();
        assertTrue(refreshed.contains("custom_face_0001"),
                "Refreshed selection lost the AI profile: " + refreshed);
        assertPixel(refreshed, Color.BLUE,
                "Imported profile cache was not refreshed after the source image changed.");

        Path assetFixture = ProfileImages.LEGACY_ASSET_ROOT.resolve("profile-regression-fixture");
        deleteTree(assetFixture);
        Path assetProcedural = assetFixture.resolve("tg_profile_0001.png");
        Path assetAi = assetFixture.resolve("tg_ai_profile_0001.png");
        writeSolidPng(assetProcedural, Color.GREEN);
        writeSolidPng(assetAi, Color.ORANGE);

        RandomProfileName assetSelector = new RandomProfileName(assetFixture);
        String assetSelected = assetSelector.getRandomProfileName();
        assertTrue(assetSelected.endsWith("profile-regression-fixture/tg_ai_profile_0001.png"),
                "Default assets path did not stay renderer-relative: " + assetSelected);
        assertPixel(assetSelected, Color.ORANGE,
                "Default assets AI profile did not resolve correctly.");

        deleteTree(testRoot);
        deleteTree(assetFixture);
        Files.deleteIfExists(staleCacheAi);
        System.out.println("Profile image regression test passed.");
    }

    private static void assertPixel(String imageName, Color expected, String message) throws IOException {
        Path resolved = ProfileImages.resolve(imageName);
        assertTrue(resolved != null, "Profile path did not resolve: " + imageName);
        BufferedImage image = ImageIO.read(resolved.toFile());
        assertTrue(image != null, "Resolved profile was not decodable: " + resolved);
        Color actual = new Color(image.getRGB(0, 0));
        assertEquals(expected.getRGB(), actual.getRGB(), message + " Resolved path: " + resolved);
    }

    private static void writeSolidPng(Path path, Color color) throws IOException {
        Files.createDirectories(path.getParent());
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        ImageIO.write(image, "png", path.toFile());
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw e;
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + " Expected=" + expected + " actual=" + actual);
        }
    }
}
