package redditTxtToImg;

import java.nio.file.Files;
import java.nio.file.Path;

final class ProfileImages {
    static final Path CACHE_ROOT = Path.of("output", "cache", "pfp");
    static final Path LEGACY_ASSET_ROOT = Path.of("assets", "pfp");

    private ProfileImages() {
    }

    static Path resolve(String imageName) {
        if (imageName == null || imageName.isBlank()) {
            return null;
        }

        Path direct = Path.of(imageName).normalize();
        if (Files.isRegularFile(direct)) {
            return direct;
        }

        Path cached = CACHE_ROOT.resolve(imageName).normalize();
        if (Files.isRegularFile(cached)) {
            return cached;
        }

        Path legacy = LEGACY_ASSET_ROOT.resolve(imageName).normalize();
        if (Files.isRegularFile(legacy)) {
            return legacy;
        }

        return null;
    }
}
