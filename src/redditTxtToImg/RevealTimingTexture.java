package redditTxtToImg;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Encodes smooth reveal timing into a single RGBA image consumed by FFmpeg.
 *
 * For pixels inside each narration word rectangle:
 *   R = start frame low byte
 *   G = start frame high byte
 *   B = reveal duration in frames (1..255)
 *   A = horizontal position through the word (0..255)
 *
 * Pixels outside narration boxes are initialized with start frame 65535, so
 * they never enter the reveal mask during a normal clip. This lets FFmpeg use
 * one constant-size expression per output pixel instead of an expression whose
 * cost grows with the number of words.
 */
final class RevealTimingTexture {
    private RevealTimingTexture() {
    }

    static Path generate(
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
        if (outputWidth <= 0 || outputHeight <= 0) {
            throw new IOException("Reveal timing texture dimensions must be positive.");
        }
        int safeFps = Math.max(1, fps);
        BufferedImage texture = new BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_ARGB);

        // startFrame = 65535, duration = 255, horizontal fraction = 255.
        // No realistic narration clip reaches frame 65535, so non-text pixels
        // remain permanently hidden from the reveal mask.
        int outside = (255 << 24) | (255 << 16) | (255 << 8) | 255;
        int[] pixels = new int[outputWidth * outputHeight];
        java.util.Arrays.fill(pixels, outside);

        double scaleX = outputWidth / (double) Math.max(1, layout.sourceWidth());
        double scaleY = outputHeight / (double) Math.max(1, layout.sourceHeight());

        for (int i = 0; i < layout.words().size(); i++) {
            TimedVisualStateRenderer.WordBox box = layout.words().get(i);
            NarrationTiming.Word word = timing.get(i);

            int left = clamp((int) Math.floor(box.left() * scaleX), 0, outputWidth - 1);
            int right = clamp((int) Math.ceil(box.right() * scaleX), left, outputWidth - 1);
            int top = clamp((int) Math.floor(box.top() * scaleY), 0, outputHeight - 1);
            int bottom = clamp((int) Math.ceil(box.bottom() * scaleY), top, outputHeight - 1);

            // ceil() means the first visible frame is never earlier than the
            // model timestamp. At video frame rate this is the strongest sync
            // guarantee the encoded output can physically provide.
            int startFrame = clamp((int) Math.ceil(word.startSeconds() * safeFps - 1.0e-9), 0, 65534);
            int endFrame = clamp((int) Math.ceil(word.endSeconds() * safeFps - 1.0e-9), startFrame + 1, 65535);
            int durationFrames = clamp(endFrame - startFrame, 1, 255);
            int red = startFrame & 0xff;
            int green = (startFrame >>> 8) & 0xff;
            int blue = durationFrames;
            int span = Math.max(1, right - left);

            for (int y = top; y <= bottom; y++) {
                int row = y * outputWidth;
                for (int x = left; x <= right; x++) {
                    int fraction = clamp((int) Math.round(255.0 * (x - left) / span), 0, 255);
                    pixels[row + x] = (fraction << 24) | (red << 16) | (green << 8) | blue;
                }
            }
        }

        texture.setRGB(0, 0, outputWidth, outputHeight, pixels, 0, outputWidth);
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        if (!ImageIO.write(texture, "png", output.toFile())) {
            throw new IOException("No PNG writer was available for reveal timing texture: " + output);
        }
        return output;
    }

    static String ffmpegMaskExpression() {
        String start = "(r(X,Y)+256*g(X,Y))";
        String duration = "max(1,b(X,Y))";
        String progress = "255*(N-" + start + ")/" + duration;
        String mask = "if(gte(N," + start + "),"
                + "if(gte(N," + start + "+" + duration + "),255,"
                + "if(lte(alpha(X,Y)," + progress + "),255,0)),0)";
        return mask;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
