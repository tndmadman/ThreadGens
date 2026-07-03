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

        Path direct = Path.of(imageName);
        if (direct.isAbsolute() && Files.exists(direct)) {
            return direct;
        }

        Path cached = CACHE_ROOT.resolve(imageName).normalize();
        if (Files.exists(cached)) {
            return cached;
        }

        Path legacy = LEGACY_ASSET_ROOT.resolve(imageName).normalize();
        if (Files.exists(legacy)) {
            return legacy;
        }

        return cached;
    }
}
