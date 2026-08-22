package redditTxtToImg;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Renders true multi-state narrated clips and stitches them with format-specific
 * transitions. Delivery stays conventional H.264/AAC/yuv420p/+faststart.
 */
final class DynamicVideoGenerator {
    private static final double END_PAUSE_SECONDS = 0.55;
    private static final double FADE_IN_SECONDS = 0.08;
    private static final double FADE_OUT_SECONDS = 0.14;

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
        return renderClip(
                states, audioFile, null, outputFile, width, height, format, itemIndex, Map.of());
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
                    .append(motionFilter(safeWidth, safeHeight, format, itemIndex, i))
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

        double fadeOutStart = Math.max(0.0, audioDuration - FADE_OUT_SECONDS);
        String audioFilter = "afade=t=in:st=0:d=" + formatSeconds(FADE_IN_SECONDS)
                + ",afade=t=out:st=" + formatSeconds(fadeOutStart)
                + ":d=" + formatSeconds(FADE_OUT_SECONDS)
                + ",apad=pad_dur=" + formatSeconds(END_PAUSE_SECONDS);

        command.add("-filter_complex");
        command.add(filter.toString());
        command.add("-map");
        command.add("[vout]");
        command.add("-map");
        command.add(audioInputIndex + ":a");
        command.add("-af");
        command.add(audioFilter);
        command.add("-t");
        command.add(formatSeconds(outputDuration));
        addEncodingArgs(command);
        addMetadata(command, metadata);
        command.add(outputFile.toString());

        try {
            run(command, "timed-state dynamic video render");
        } finally {
            if (filterCaptionFile != null && !filterCaptionFile.equals(captionFile)) {
                Files.deleteIfExists(filterCaptionFile);
            }
        }
        verifyNonEmpty(outputFile, "Dynamic video render");
        return outputFile;
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
        double duration = probeDurationSeconds(audioFile);
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
        if (metadata == null || metadata.isEmpty()) {
            return combineClipsInternal(clips, outputFile, format);
        }
        if (outputFile.getParent() != null) {
            Files.createDirectories(outputFile.getParent());
        }
        Path temporary = outputFile.resolveSibling(outputFile.getFileName() + ".unmarked.mp4");
        Files.deleteIfExists(temporary);
        try {
            combineClipsInternal(clips, temporary, format);
            remuxWithMetadata(temporary, outputFile, metadata);
            verifyNonEmpty(outputFile, "Final metadata remux");
        } finally {
            Files.deleteIfExists(temporary);
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
        verifyNonEmpty(outputFile, "Final dynamic stitch");
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
        run(command, "single dynamic video finalize");
        verifyNonEmpty(outputFile, "Single dynamic video finalize");
    }

    private String motionFilter(int width, int height, ContentFormat format, int itemIndex, int stateIndex) {
        double scaleFactor = switch (format) {
            case THREAD_STORY -> 1.075;
            case CONFESSION -> 1.105;
            case DEBATE -> 1.085;
            case BEST_ANSWERS -> 1.095;
            case ESCALATING_CONVERSATION -> 1.09;
        };
        int scaledWidth = even((int) Math.ceil(width * scaleFactor));
        int scaledHeight = even((int) Math.ceil(height * scaleFactor));
        int availableX = Math.max(2, scaledWidth - width);
        int availableY = Math.max(2, scaledHeight - height);
        double centerX = availableX / 2.0;
        double centerY = availableY / 2.0;
        double phase = (itemIndex * 0.77) + (stateIndex * 1.31);

        String x;
        String y;
        switch (format) {
            case THREAD_STORY -> {
                x = decimal(centerX) + "+" + decimal(Math.min(22.0, availableX * 0.28))
                        + "*sin(n/42+" + decimal(phase) + ")";
                y = decimal(centerY) + "+" + decimal(Math.min(34.0, availableY * 0.30))
                        + "*cos(n/58+" + decimal(phase) + ")";
            }
            case CONFESSION -> {
                x = decimal(centerX) + "+" + decimal(Math.min(10.0, availableX * 0.18))
                        + "*sin(n/75+" + decimal(phase) + ")";
                y = decimal(centerY) + "+" + decimal(Math.min(62.0, availableY * 0.42))
                        + "*sin(n/82+" + decimal(phase) + ")";
            }
            case DEBATE -> {
                double direction = (itemIndex + stateIndex) % 2 == 0 ? 1.0 : -1.0;
                x = decimal(centerX) + (direction >= 0 ? "+" : "-")
                        + decimal(Math.min(38.0, availableX * 0.38))
                        + "*sin(n/50+" + decimal(phase) + ")";
                y = decimal(centerY) + "+" + decimal(Math.min(18.0, availableY * 0.20))
                        + "*cos(n/68+" + decimal(phase) + ")";
            }
            case BEST_ANSWERS -> {
                x = decimal(centerX) + "+" + decimal(Math.min(18.0, availableX * 0.24))
                        + "*sin(n/64+" + decimal(phase) + ")";
                y = decimal(centerY) + "-" + decimal(Math.min(42.0, availableY * 0.32))
                        + "*sin(n/86+" + decimal(phase) + ")";
            }
            case ESCALATING_CONVERSATION -> {
                x = decimal(centerX) + "+" + decimal(Math.min(14.0, availableX * 0.22))
                        + "*cos(n/57+" + decimal(phase) + ")";
                y = decimal(centerY) + "+" + decimal(Math.min(54.0, availableY * 0.40))
                        + "*sin(n/61+" + decimal(phase) + ")";
            }
            default -> throw new IllegalStateException("Unhandled format: " + format);
        }

        return "scale=" + scaledWidth + ":" + scaledHeight
                + ",crop=" + width + ":" + height + ":x=" + x + ":y=" + y
                + ",fps=" + fps
                + ",format=yuv420p";
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

    private void remuxWithMetadata(Path input, Path output, Map<String, String> metadata)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(ffmpegCommand);
        command.add("-y");
        command.add("-i");
        command.add(input.toString());
        command.add("-map");
        command.add("0");
        command.add("-c");
        command.add("copy");
        command.add("-movflags");
        command.add("+faststart");
        addMetadata(command, metadata);
        command.add(output.toString());
        run(command, "provenance metadata remux");
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

    private static String decimal(double value) {
        return String.format(Locale.US, "%.3f", value);
    }

    private record TransitionProfile(double durationSeconds) {
    }
}
