package redditTxtToImg;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Renders narration-timed social frames and stitches them into conventional
 * H.264/AAC/yuv420p/+faststart MP4s.
 *
 * Social frames remain spatially locked. When smooth reveal assets are present,
 * the original rasterized word pixels are exposed continuously at video frame
 * rate using the TTS timing sidecar. The final stitched video then receives one
 * faint seeded Perlin texture plus subtle temporal grain pass.
 */
final class DynamicVideoGenerator {
    private static final double END_PAUSE_SECONDS = 0.55;
    private static final double FADE_IN_SECONDS = 0.08;
    private static final double FADE_OUT_SECONDS = 0.14;
    private static final int FINAL_GRAIN_STRENGTH = 1;
    private static final double FINAL_PERLIN_OPACITY = 0.025;
    private static final SecureRandom FINAL_TEXTURE_RANDOM = new SecureRandom();

    private final String ffmpegCommand;
    private final int timeoutSeconds;
    private final int fps;

    DynamicVideoGenerator(String ffmpegCommand, int timeoutSeconds, int fps) {
        this.ffmpegCommand = ffmpegCommand == null || ffmpegCommand.isBlank() ? "ffmpeg" : ffmpegCommand;
        this.timeoutSeconds = timeoutSeconds <= 0 ? 180 : timeoutSeconds;
        this.fps = fps <= 0 ? 30 : fps;
    }

    Path renderClip(
            List<TimedVisualStateRenderer.RenderedState> states,
            Path audioFile,
            Path outputFile,
            int width,
            int height,
            ContentFormat format,
            int itemIndex
    ) throws IOException, InterruptedException {
        return renderClip(states, audioFile, null, outputFile, width, height, format, itemIndex, Map.of());
    }

    Path renderClip(
            List<TimedVisualStateRenderer.RenderedState> states,
            Path audioFile,
            Path captionFile,
            Path outputFile,
            int width,
            int height,
            ContentFormat format,
            int itemIndex,
            Map<String, String> metadata
    ) throws IOException, InterruptedException {
        if (states == null || states.isEmpty()) {
            throw new IOException("No timed visual states were supplied for dynamic clip rendering.");
        }
        for (TimedVisualStateRenderer.RenderedState state : states) {
            if (state == null || state.imagePath() == null || !Files.exists(state.imagePath())) {
                throw new IOException("Timed visual state image is missing: "
                        + (state == null ? "null" : state.imagePath()));
            }
        }
        if (audioFile == null || !Files.exists(audioFile)) {
            throw new IOException("Audio file not found for dynamic clip: " + audioFile);
        }
        if (outputFile.getParent() != null) {
            Files.createDirectories(outputFile.getParent());
        }

        double audioDuration = probeDurationSeconds(audioFile);
        if (audioDuration <= 0.01) {
            throw new IOException("Could not determine positive audio duration for: " + audioFile);
        }

        if (states.size() == 1 && TimedVisualStateRenderer.hasSmoothRevealAssets(states.get(0).imagePath())) {
            return renderSmoothRevealClip(
                    states.get(0).imagePath(),
                    audioFile,
                    captionFile,
                    outputFile,
                    width,
                    height,
                    audioDuration,
                    metadata);
        }

        return renderLegacyStateClip(states, audioFile, captionFile, outputFile, width, height, audioDuration, metadata);
    }

    private Path renderSmoothRevealClip(
            Path fullFrame,
            Path audioFile,
            Path captionFile,
            Path outputFile,
            int width,
            int height,
            double audioDuration,
            Map<String, String> metadata
    ) throws IOException, InterruptedException {
        TimedVisualStateRenderer.RevealLayout layout = TimedVisualStateRenderer.readLayout(fullFrame);
        if (layout.words().isEmpty()) {
            throw new IOException("Smooth reveal layout contains no word rectangles: " + fullFrame);
        }

        List<NarrationTiming.Word> measured = NarrationTiming.load(audioFile);
        List<NarrationTiming.Word> timing = NarrationTiming.fitToCount(
                measured,
                layout.narration(),
                audioDuration,
                layout.words().size());
        boolean exactTiming = !measured.isEmpty() && measured.size() == layout.words().size();
        System.out.println("Narration reveal timing: "
                + (exactTiming ? "exact Kokoro model timestamps" : "measured-duration fallback")
                + " [words=" + layout.words().size() + ", fps=" + fps + "]");

        int safeWidth = even(Math.max(64, width));
        int safeHeight = even(Math.max(64, height));
        double outputDuration = audioDuration + END_PAUSE_SECONDS;
        Path cleanBase = TimedVisualStateRenderer.basePath(fullFrame);
        Path filterCaptionFile = prepareCaptionAlias(captionFile);

        List<String> command = new ArrayList<>();
        command.add(ffmpegCommand);
        command.add("-y");
        addLoopedImageInput(command, fullFrame, outputDuration);
        addLoopedImageInput(command, cleanBase, outputDuration);
        command.add("-i");
        command.add(audioFile.toString());

        String frameFilter = lockedFrameFilter(safeWidth, safeHeight, "rgba");
        String maskExpression = buildSmoothRevealMask(layout, timing, safeWidth, safeHeight);
        StringBuilder filter = new StringBuilder();
        filter.append("[0:v]").append(frameFilter).append(",setpts=PTS-STARTPTS[full];")
                .append("[1:v]").append(frameFilter).append(",setpts=PTS-STARTPTS,split=2[base][masksrc];")
                .append("[masksrc]format=gray,geq=lum='").append(maskExpression).append("'[mask];")
                .append("[full][mask]alphamerge[revealed];")
                .append("[base][revealed]overlay=0:0:shortest=1[vbase];");
        if (filterCaptionFile != null) {
            filter.append("[vbase]ass=filename='")
                    .append(escapeFilterPath(filterCaptionFile))
                    .append("'[vout]");
        } else {
            filter.append("[vbase]format=yuv420p[vout]");
        }

        command.add("-filter_complex");
        command.add(filter.toString());
        command.add("-map");
        command.add("[vout]");
        command.add("-map");
        command.add("2:a");
        command.add("-af");
        command.add(audioFilter(audioDuration));
        command.add("-t");
        command.add(formatSeconds(outputDuration));
        addEncodingArgs(command);
        addMetadata(command, metadata);
        command.add(outputFile.toString());

        try {
            run(command, "smooth narration-synced reveal render");
        } finally {
            if (filterCaptionFile != null && !filterCaptionFile.equals(captionFile)) {
                Files.deleteIfExists(filterCaptionFile);
            }
        }
        verifyNonEmpty(outputFile, "Smooth narration-synced reveal render");
        return outputFile;
    }

    /**
     * Builds one frame-evaluated grayscale mask. Every completed word remains
     * visible, while the currently spoken word exposes from left to right using
     * its exact start/end timestamps. Hidden words contribute no pixels at all.
     */
    static String buildSmoothRevealMask(
            TimedVisualStateRenderer.RevealLayout layout,
            List<NarrationTiming.Word> timing,
            int outputWidth,
            int outputHeight
    ) {
        int count = Math.min(layout.words().size(), timing.size());
        if (count <= 0) {
            return "0";
        }
        double scaleX = outputWidth / (double) Math.max(1, layout.sourceWidth());
        double scaleY = outputHeight / (double) Math.max(1, layout.sourceHeight());
        StringBuilder expression = new StringBuilder("clip(");
        for (int i = 0; i < count; i++) {
            TimedVisualStateRenderer.WordBox box = layout.words().get(i);
            NarrationTiming.Word word = timing.get(i);
            int left = Math.max(0, (int) Math.floor(box.left() * scaleX));
            int right = Math.min(outputWidth - 1, (int) Math.ceil(box.right() * scaleX));
            int top = Math.max(0, (int) Math.floor(box.top() * scaleY));
            int bottom = Math.min(outputHeight - 1, (int) Math.ceil(box.bottom() * scaleY));
            int pixelWidth = Math.max(1, right - left + 1);
            double start = Math.max(0.0, word.startSeconds());
            double duration = Math.max(0.005, word.endSeconds() - word.startSeconds());

            if (i > 0) {
                expression.append('+');
            }
            String time = "(N/" + Math.max(1, 30) + ")";
            // The actual fps is substituted below to keep this helper directly
            // testable without shell quoting or filter parsing.
            time = time.replace("/30", "/" + Math.max(1, CURRENT_MASK_FPS.get()));
            String progress = "clip((" + time + "-" + formatMask(start) + ")/"
                    + formatMask(duration) + ",0,1)";
            String edge = "(" + (left - 1) + "+" + pixelWidth + "*" + progress + ")";
            expression.append("255*gte(").append(time).append(',').append(formatMask(start)).append(')')
                    .append("*between(Y,").append(top).append(',').append(bottom).append(')')
                    .append("*between(X,").append(left).append(',').append(edge).append(')');
        }
        expression.append(",0,255)");
        return expression.toString();
    }

    // buildSmoothRevealMask is static for focused regression testing. This
    // thread-local lets the instance render path inject its configured fps.
    private static final ThreadLocal<Integer> CURRENT_MASK_FPS = ThreadLocal.withInitial(() -> 30);

    private String buildSmoothRevealMask(
            TimedVisualStateRenderer.RevealLayout layout,
            List<NarrationTiming.Word> timing,
            int outputWidth,
            int outputHeight
    ) {
        CURRENT_MASK_FPS.set(fps);
        try {
            return buildSmoothRevealMask(layout, timing, outputWidth, outputHeight);
        } finally {
            CURRENT_MASK_FPS.remove();
        }
    }

    private Path renderLegacyStateClip(
            List<TimedVisualStateRenderer.RenderedState> states,
            Path audioFile,
            Path captionFile,
            Path outputFile,
            int width,
            int height,
            double audioDuration,
            Map<String, String> metadata
    ) throws IOException, InterruptedException {
        double outputDuration = audioDuration + END_PAUSE_SECONDS;
        List<Double> stateDurations = allocateStateDurations(states, audioDuration, END_PAUSE_SECONDS);
        Path filterCaptionFile = prepareCaptionAlias(captionFile);
        int safeWidth = even(Math.max(64, width));
        int safeHeight = even(Math.max(64, height));

        List<String> command = new ArrayList<>();
        command.add(ffmpegCommand);
        command.add("-y");
        for (int i = 0; i < states.size(); i++) {
            command.add("-loop");
            command.add("1");
            command.add("-framerate");
            command.add(String.valueOf(fps));
            command.add("-t");
            command.add(formatSeconds(stateDurations.get(i)));
            command.add("-i");
            command.add(states.get(i).imagePath().toString());
        }
        int audioInputIndex = states.size();
        command.add("-i");
        command.add(audioFile.toString());

        StringBuilder filter = new StringBuilder();
        for (int i = 0; i < states.size(); i++) {
            filter.append('[').append(i).append(":v]")
                    .append(staticFrameFilter(safeWidth, safeHeight))
                    .append(",trim=duration=").append(formatSeconds(stateDurations.get(i)))
                    .append(",setpts=PTS-STARTPTS[v").append(i).append("];");
        }
        for (int i = 0; i < states.size(); i++) {
            filter.append("[v").append(i).append(']');
        }
        filter.append("concat=n=").append(states.size()).append(":v=1:a=0[vbase];");
        if (filterCaptionFile != null) {
            filter.append("[vbase]ass=filename='")
                    .append(escapeFilterPath(filterCaptionFile))
                    .append("'[vout]");
        } else {
            filter.append("[vbase]null[vout]");
        }

        command.add("-filter_complex");
        command.add(filter.toString());
        command.add("-map");
        command.add("[vout]");
        command.add("-map");
        command.add(audioInputIndex + ":a");
        command.add("-af");
        command.add(audioFilter(audioDuration));
        command.add("-t");
        command.add(formatSeconds(outputDuration));
        addEncodingArgs(command);
        addMetadata(command, metadata);
        command.add(outputFile.toString());

        try {
            run(command, "timed-state static video render");
        } finally {
            if (filterCaptionFile != null && !filterCaptionFile.equals(captionFile)) {
                Files.deleteIfExists(filterCaptionFile);
            }
        }
        verifyNonEmpty(outputFile, "Static video render");
        return outputFile;
    }

    private void addLoopedImageInput(List<String> command, Path image, double duration) {
        command.add("-loop");
        command.add("1");
        command.add("-framerate");
        command.add(String.valueOf(fps));
        command.add("-t");
        command.add(formatSeconds(duration));
        command.add("-i");
        command.add(image.toString());
    }

    private String audioFilter(double audioDuration) {
        double fadeOutStart = Math.max(0.0, audioDuration - FADE_OUT_SECONDS);
        return "afade=t=in:st=0:d=" + formatSeconds(FADE_IN_SECONDS)
                + ",afade=t=out:st=" + formatSeconds(fadeOutStart)
                + ":d=" + formatSeconds(FADE_OUT_SECONDS)
                + ",apad=pad_dur=" + formatSeconds(END_PAUSE_SECONDS);
    }

    /** Compatibility overload used by focused tests/tools. */
    Path renderClip(
            Path presentationFrame,
            Path audioFile,
            Path outputFile,
            int width,
            int height,
            ContentFormat format,
            int index
    ) throws IOException, InterruptedException {
        List<TimedVisualStateRenderer.RenderedState> states = List.of(
                new TimedVisualStateRenderer.RenderedState(presentationFrame, 1.0, 0, 1));
        return renderClip(states, audioFile, outputFile, width, height, format, index);
    }

    Path combineClips(List<Path> clips, Path outputFile, ContentFormat format)
            throws IOException, InterruptedException {
        return combineClips(clips, outputFile, format, Map.of());
    }

    Path combineClips(
            List<Path> clips,
            Path outputFile,
            ContentFormat format,
            Map<String, String> metadata
    ) throws IOException, InterruptedException {
        if (outputFile.getParent() != null) {
            Files.createDirectories(outputFile.getParent());
        }
        Path stitched = outputFile.resolveSibling(outputFile.getFileName() + ".stitched.mp4");
        Files.deleteIfExists(stitched);
        try {
            combineClipsInternal(clips, stitched, format);
            applyFinalTextureAndMetadata(stitched, outputFile, metadata);
            verifyNonEmpty(outputFile, "Final texture/metadata render");
        } finally {
            Files.deleteIfExists(stitched);
        }
        return outputFile;
    }

    private Path combineClipsInternal(List<Path> clips, Path outputFile, ContentFormat format)
            throws IOException, InterruptedException {
        List<Path> existing = new ArrayList<>();
        if (clips != null) {
            for (Path clip : clips) {
                if (clip != null && Files.exists(clip)) {
                    existing.add(clip);
                }
            }
        }
        if (existing.isEmpty()) {
            throw new IOException("No dynamic clips were supplied for final video.");
        }
        if (outputFile.getParent() != null) {
            Files.createDirectories(outputFile.getParent());
        }
        if (existing.size() == 1) {
            reencodeSingleClip(existing.get(0), outputFile);
            return outputFile;
        }

        List<Double> durations = new ArrayList<>();
        TransitionProfile profile = transitionProfile(format);
        for (Path clip : existing) {
            double duration = probeDurationSeconds(clip);
            durations.add(duration);
            if (duration <= profile.durationSeconds() + 0.12) {
                combineWithConcatFilter(existing, outputFile);
                return outputFile;
            }
        }
        combineWithFormatTransitions(existing, durations, outputFile, format, profile);
        verifyNonEmpty(outputFile, "Final static stitch");
        return outputFile;
    }

    /** Compatibility overload defaults to the restrained thread transition. */
    Path combineClips(List<Path> clips, Path outputFile) throws IOException, InterruptedException {
        return combineClips(clips, outputFile, ContentFormat.THREAD_STORY);
    }

    double probeDurationSeconds(Path mediaFile) throws IOException, InterruptedException {
        if (mediaFile == null || !Files.exists(mediaFile)) {
            throw new IOException("Cannot probe missing media file: " + mediaFile);
        }
        List<String> command = List.of(
                resolveFfprobeCommand(),
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                mediaFile.toString()
        );
        String output = runAndCollect(command, "media duration probe", Math.min(timeoutSeconds, 45));
        String firstLine = output.lines().map(String::trim).filter(line -> !line.isBlank())
                .findFirst().orElse("");
        try {
            return Double.parseDouble(firstLine);
        } catch (NumberFormatException e) {
            throw new IOException("ffprobe returned an invalid duration for " + mediaFile + ": " + firstLine);
        }
    }

    private int[] probeVideoDimensions(Path mediaFile) throws IOException, InterruptedException {
        List<String> command = List.of(
                resolveFfprobeCommand(),
                "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height",
                "-of", "csv=s=x:p=0",
                mediaFile.toString()
        );
        String output = runAndCollect(command, "video dimension probe", Math.min(timeoutSeconds, 45));
        String firstLine = output.lines().map(String::trim).filter(line -> !line.isBlank())
                .findFirst().orElse("");
        String[] parts = firstLine.toLowerCase(Locale.ROOT).split("x", 2);
        if (parts.length != 2) {
            throw new IOException("ffprobe returned invalid dimensions for " + mediaFile + ": " + firstLine);
        }
        try {
            int width = even(Math.max(64, Integer.parseInt(parts[0].trim())));
            int height = even(Math.max(64, Integer.parseInt(parts[1].trim())));
            return new int[]{width, height};
        } catch (NumberFormatException e) {
            throw new IOException("ffprobe returned invalid dimensions for " + mediaFile + ": " + firstLine);
        }
    }

    private void combineWithFormatTransitions(
            List<Path> clips,
            List<Double> durations,
            Path outputFile,
            ContentFormat format,
            TransitionProfile profile
    ) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(ffmpegCommand);
        command.add("-y");
        for (Path clip : clips) {
            command.add("-i");
            command.add(clip.toString());
        }

        StringBuilder filter = new StringBuilder();
        String previousVideo = "[0:v]";
        String previousAudio = "[0:a]";
        double runningDuration = durations.get(0);
        for (int i = 1; i < clips.size(); i++) {
            boolean last = i == clips.size() - 1;
            String videoOut = last ? "vout" : "v" + i;
            String audioOut = last ? "aout" : "a" + i;
            double transitionDuration = Math.min(profile.durationSeconds(),
                    Math.max(0.08, Math.min(durations.get(i - 1), durations.get(i)) / 4.0));
            double offset = Math.max(0.01, runningDuration - transitionDuration);

            filter.append(previousVideo)
                    .append('[').append(i).append(":v]")
                    .append("xfade=transition=").append(transitionName(format, i))
                    .append(":duration=").append(formatSeconds(transitionDuration))
                    .append(":offset=").append(formatSeconds(offset))
                    .append('[').append(videoOut).append("];" );
            filter.append(previousAudio)
                    .append('[').append(i).append(":a]")
                    .append("acrossfade=d=").append(formatSeconds(transitionDuration))
                    .append(":c1=tri:c2=tri")
                    .append('[').append(audioOut).append("];" );

            previousVideo = "[" + videoOut + "]";
            previousAudio = "[" + audioOut + "]";
            runningDuration = runningDuration + durations.get(i) - transitionDuration;
        }

        command.add("-filter_complex");
        command.add(filter.toString());
        command.add("-map");
        command.add("[vout]");
        command.add("-map");
        command.add("[aout]");
        addEncodingArgs(command);
        command.add(outputFile.toString());
        run(command, "format-specific video stitch");
    }

    private void combineWithConcatFilter(List<Path> clips, Path outputFile)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(ffmpegCommand);
        command.add("-y");
        for (Path clip : clips) {
            command.add("-i");
            command.add(clip.toString());
        }
        StringBuilder filter = new StringBuilder();
        for (int i = 0; i < clips.size(); i++) {
            filter.append('[').append(i).append(":v]")
                    .append('[').append(i).append(":a]");
        }
        filter.append("concat=n=").append(clips.size()).append(":v=1:a=1[vout][aout]");
        command.add("-filter_complex");
        command.add(filter.toString());
        command.add("-map");
        command.add("[vout]");
        command.add("-map");
        command.add("[aout]");
        addEncodingArgs(command);
        command.add(outputFile.toString());
        run(command, "fallback video concat");
    }

    private void reencodeSingleClip(Path clip, Path outputFile) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(ffmpegCommand);
        command.add("-y");
        command.add("-i");
        command.add(clip.toString());
        addEncodingArgs(command);
        command.add(outputFile.toString());
        run(command, "single static video finalize");
        verifyNonEmpty(outputFile, "Single static video finalize");
    }

    /** Keep the full social frame locked to the requested canvas. */
    private String staticFrameFilter(int width, int height) {
        return lockedFrameFilter(width, height, "yuv420p");
    }

    private String lockedFrameFilter(int width, int height, String pixelFormat) {
        return "scale=" + width + ":" + height
                + ":force_original_aspect_ratio=decrease"
                + ",pad=" + width + ":" + height + ":(ow-iw)/2:(oh-ih)/2:black"
                + ",fps=" + fps
                + ",format=" + pixelFormat;
    }

    /**
     * Apply one fresh randomly seeded Perlin texture plus faint temporal grain
     * after the complete video has been stitched.
     */
    private void applyFinalTextureAndMetadata(Path input, Path output, Map<String, String> metadata)
            throws IOException, InterruptedException {
        int seed = FINAL_TEXTURE_RANDOM.nextInt(Integer.MAX_VALUE - 1) + 1;
        int[] dimensions = probeVideoDimensions(input);
        double duration = probeDurationSeconds(input);
        int textureWidth = even(Math.max(64, dimensions[0] / 6));
        int textureHeight = even(Math.max(64, dimensions[1] / 6));
        Path texture = Files.createTempFile("threadgens-perlin-", ".png");

        try {
            PerlinNoiseTexture.generate(texture, textureWidth, textureHeight, seed);
            List<String> command = new ArrayList<>();
            command.add(ffmpegCommand);
            command.add("-y");
            command.add("-i");
            command.add(input.toString());
            command.add("-loop");
            command.add("1");
            command.add("-framerate");
            command.add(String.valueOf(fps));
            command.add("-i");
            command.add(texture.toString());
            command.add("-filter_complex");
            command.add("[1:v]scale=" + dimensions[0] + ":" + dimensions[1]
                    + ":flags=bicubic,format=yuv420p[perlin];"
                    + "[0:v:0][perlin]blend=all_mode=softlight:all_opacity="
                    + String.format(Locale.US, "%.3f", FINAL_PERLIN_OPACITY)
                    + ",noise=alls=" + FINAL_GRAIN_STRENGTH
                    + ":allf=t+a:all_seed=" + seed + "[vout]");
            command.add("-map");
            command.add("[vout]");
            command.add("-map");
            command.add("0:a?");
            command.add("-t");
            command.add(formatSeconds(duration));
            addEncodingArgs(command);
            addMetadata(command, metadata);
            command.add(output.toString());
            run(command, "final seeded Perlin/grain render");
        } finally {
            Files.deleteIfExists(texture);
        }
    }

    private static List<Double> allocateStateDurations(
            List<TimedVisualStateRenderer.RenderedState> states,
            double audioDuration,
            double endPauseDuration
    ) {
        double weightTotal = 0.0;
        for (TimedVisualStateRenderer.RenderedState state : states) {
            weightTotal += Math.max(0.05, state.weight());
        }
        double totalWeight = weightTotal;
        double shortestWeightedDuration = states.stream()
                .mapToDouble(state -> audioDuration * Math.max(0.05, state.weight()) / totalWeight)
                .min()
                .orElse(audioDuration);
        double minimumPerState = shortestWeightedDuration >= 0.10
                ? 0.0
                : Math.min(0.10, Math.max(0.005, audioDuration * 0.45 / states.size()));
        double distributable = Math.max(0.0, audioDuration - (minimumPerState * states.size()));
        List<Double> result = new ArrayList<>();
        double assigned = 0.0;
        for (int i = 0; i < states.size(); i++) {
            double duration;
            if (i == states.size() - 1) {
                duration = Math.max(0.005, audioDuration - assigned) + Math.max(0.0, endPauseDuration);
            } else {
                duration = minimumPerState
                        + distributable * Math.max(0.05, states.get(i).weight()) / weightTotal;
                assigned += duration;
            }
            result.add(duration);
        }
        return result;
    }

    private static void addMetadata(List<String> command, Map<String, String> metadata) {
        if (metadata == null) {
            return;
        }
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            command.add("-metadata");
            command.add(entry.getKey().trim() + "=" + entry.getValue());
        }
    }

    private static String escapeFilterPath(Path path) {
        return path.toAbsolutePath().normalize().toString()
                .replace('\\', '/')
                .replace(":", "\\:")
                .replace("'", "'\\''")
                .replace(",", "\\,")
                .replace(";", "\\;")
                .replace("[", "\\[")
                .replace("]", "\\]");
    }

    private static Path prepareCaptionAlias(Path captionFile) throws IOException {
        if (captionFile == null) {
            return null;
        }
        if (!Files.isRegularFile(captionFile)) {
            throw new IOException("Caption file not found: " + captionFile);
        }
        Path alias = Files.createTempFile("threadgens-caption-", ".ass");
        Files.copy(captionFile, alias, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return alias;
    }

    private static TransitionProfile transitionProfile(ContentFormat format) {
        return switch (format) {
            case THREAD_STORY -> new TransitionProfile(0.28);
            case CONFESSION -> new TransitionProfile(0.45);
            case DEBATE -> new TransitionProfile(0.22);
            case BEST_ANSWERS -> new TransitionProfile(0.24);
            case ESCALATING_CONVERSATION -> new TransitionProfile(0.16);
        };
    }

    private static String transitionName(ContentFormat format, int transitionIndex) {
        return switch (format) {
            case THREAD_STORY -> "fade";
            case CONFESSION -> "fadeblack";
            case DEBATE -> transitionIndex % 2 == 0 ? "slideright" : "slideleft";
            case BEST_ANSWERS -> "wipeup";
            case ESCALATING_CONVERSATION -> "slideup";
        };
    }

    private void addEncodingArgs(List<String> command) {
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("medium");
        command.add("-crf");
        command.add("19");
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("192k");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-movflags");
        command.add("+faststart");
    }

    private String resolveFfprobeCommand() {
        try {
            Path configured = Path.of(ffmpegCommand);
            Path fileName = configured.getFileName();
            if (fileName != null) {
                String lower = fileName.toString().toLowerCase(Locale.ROOT);
                if ("ffmpeg.exe".equals(lower) || "ffmpeg".equals(lower)) {
                    String probeName = lower.endsWith(".exe") ? "ffprobe.exe" : "ffprobe";
                    Path parent = configured.getParent();
                    if (parent != null) {
                        Path sibling = parent.resolve(probeName);
                        if (Files.exists(sibling)) {
                            return sibling.toString();
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return "ffprobe";
    }

    private void run(List<String> command, String label) throws IOException, InterruptedException {
        runAndCollect(command, label, timeoutSeconds);
    }

    private static String runAndCollect(List<String> command, String label, int timeoutSeconds)
            throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();

        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            } catch (IOException e) {
                output.append("Could not read process output: ").append(e.getMessage()).append(System.lineSeparator());
            }
        }, "threadgens-" + label.replace(' ', '-'));
        readerThread.setDaemon(true);
        readerThread.start();

        boolean finished = process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            readerThread.join(1000);
            throw new IOException(label + " timed out after " + timeoutSeconds + " seconds. Output: " + tail(output));
        }
        readerThread.join(1000);
        if (process.exitValue() != 0) {
            throw new IOException(label + " failed with exit code " + process.exitValue() + ". Output: " + tail(output));
        }
        return output.toString();
    }

    private static void verifyNonEmpty(Path path, String label) throws IOException {
        if (!Files.exists(path) || Files.size(path) <= 0) {
            throw new IOException(label + " did not create a valid file: " + path);
        }
    }

    private static String tail(StringBuilder output) {
        String text = output.toString().trim();
        int max = 3500;
        return text.length() <= max ? text : text.substring(text.length() - max);
    }

    private static int even(int value) {
        return value % 2 == 0 ? value : value + 1;
    }

    private static String formatSeconds(double value) {
        return String.format(Locale.US, "%.3f", value);
    }

    private static String formatMask(double value) {
        return String.format(Locale.US, "%.6f", value);
    }

    private record TransitionProfile(double durationSeconds) {
    }
}
