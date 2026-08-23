package redditTxtToImg;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferUShort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Encodes the narration reveal schedule into one cropped 16-bit grayscale PNG.
 *
 * Every pixel stores the absolute video frame on which that pixel becomes
 * visible. Pixels outside narration word rectangles use 65535 and therefore
 * stay hidden for any realistic clip. The texture is cropped to the union of
 * all word rectangles so FFmpeg evaluates the reveal mask only where narration
 * text actually exists instead of across the full 1080x1920 canvas.
 */
final class RevealTimingTexture {
    static final int NEVER_FRAME = 0xffff;

    record Region(int x, int y, int width, int height) {
        Region {
            if (x < 0 || y < 0 || width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Invalid reveal timing texture region.");
            }
        }
    }

    record Asset(Path path, Region region) {
    }

    private RevealTimingTexture() {
    }

    static Asset generate(
            Path output,
            TimedVisualStateRenderer.RevealLayout layout,
            List<NarrationTiming.Word> timing,
            int outputWidth,
            int outputHeight,
            int fps
    ) throws IOException {
        if (layout == null || timing == null || layout.words().size() != timing.size()) {
            throw new IOException("Reveal timing texture requires an exact word/timing mapping.");
        }
        if (layout.words().isEmpty()) {
            throw new IOException("Reveal timing texture requires at least one word rectangle.");
        }
        if (outputWidth <= 0 || outputHeight <= 0) {
            throw new IOException("Reveal timing texture dimensions must be positive.");
        }

        int safeFps = Math.max(1, fps);
        double scaleX = outputWidth / (double) Math.max(1, layout.sourceWidth());
        double scaleY = outputHeight / (double) Math.max(1, layout.sourceHeight());
        Region region = regionFor(layout, outputWidth, outputHeight, scaleX, scaleY);

        BufferedImage texture = new BufferedImage(
                region.width(), region.height(), BufferedImage.TYPE_USHORT_GRAY);
        short[] samples = ((DataBufferUShort) texture.getRaster().getDataBuffer()).getData();
        Arrays.fill(samples, (short) NEVER_FRAME);

        for (int i = 0; i < layout.words().size(); i++) {
            TimedVisualStateRenderer.WordBox box = layout.words().get(i);
            NarrationTiming.Word word = timing.get(i);

            int absoluteLeft = clamp((int) Math.floor(box.left() * scaleX), 0, outputWidth - 1);
            int absoluteRight = clamp((int) Math.ceil(box.right() * scaleX), absoluteLeft, outputWidth - 1);
            int absoluteTop = clamp((int) Math.floor(box.top() * scaleY), 0, outputHeight - 1);
            int absoluteBottom = clamp((int) Math.ceil(box.bottom() * scaleY), absoluteTop, outputHeight - 1);

            int left = absoluteLeft - region.x();
            int right = absoluteRight - region.x();
            int top = absoluteTop - region.y();
            int bottom = absoluteBottom - region.y();

            // ceil() prevents any part of a word from appearing before Kokoro's
            // model timestamp at the encoded video's frame rate.
            int startFrame = clamp(
                    (int) Math.ceil(Math.max(0.0, word.startSeconds()) * safeFps - 1.0e-9),
                    0, NEVER_FRAME - 1);
            int endFrame = clamp(
                    (int) Math.ceil(Math.max(word.startSeconds(), word.endSeconds()) * safeFps - 1.0e-9),
                    startFrame + 1, NEVER_FRAME - 1);
            int durationFrames = Math.max(1, endFrame - startFrame);
            int span = Math.max(1, right - left);

            for (int x = left; x <= right; x++) {
                double fraction = (x - left) / (double) span;
                int revealFrame = clamp(
                        (int) Math.ceil(startFrame + durationFrames * fraction - 1.0e-9),
                        startFrame, endFrame);
                for (int y = top; y <= bottom; y++) {
                    int offset = y * region.width() + x;
                    int existing = samples[offset] & 0xffff;
                    // Padded word boxes can touch. In an overlap, exposing the
                    // pixel at the earlier schedule avoids re-hiding old text.
                    if (revealFrame < existing) {
                        samples[offset] = (short) revealFrame;
                    }
                }
            }
        }

        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        if (!ImageIO.write(texture, "png", output.toFile())) {
            throw new IOException("No PNG writer was available for reveal timing texture: " + output);
        }
        return new Asset(output, region);
    }

    /** One constant-cost expression, independent of narration word count. */
    static String ffmpegMaskExpression() {
        return "if(gte(N,lum(X,Y)),65535,0)";
    }

    private static Region regionFor(
            TimedVisualStateRenderer.RevealLayout layout,
            int outputWidth,
            int outputHeight,
            double scaleX,
            double scaleY
    ) {
        int left = outputWidth - 1;
        int top = outputHeight - 1;
        int right = 0;
        int bottom = 0;
        for (TimedVisualStateRenderer.WordBox box : layout.words()) {
            left = Math.min(left, clamp((int) Math.floor(box.left() * scaleX), 0, outputWidth - 1));
            right = Math.max(right, clamp((int) Math.ceil(box.right() * scaleX), 0, outputWidth - 1));
            top = Math.min(top, clamp((int) Math.floor(box.top() * scaleY), 0, outputHeight - 1));
            bottom = Math.max(bottom, clamp((int) Math.ceil(box.bottom() * scaleY), 0, outputHeight - 1));
        }

        // A tiny safety margin protects antialiased glyph edges while keeping
        // the expensive per-pixel mask region tightly cropped.
        left = clamp(left - 2, 0, outputWidth - 1);
        top = clamp(top - 2, 0, outputHeight - 1);
        right = clamp(right + 2, left, outputWidth - 1);
        bottom = clamp(bottom + 2, top, outputHeight - 1);
        return new Region(left, top, right - left + 1, bottom - top + 1);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
