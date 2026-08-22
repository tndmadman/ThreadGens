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
import java.util.concurrent.TimeUnit;

/**
 * Produces continuously moving clips from format-specific presentation frames.
 * This replaces the old one-static-PNG-per-narration behavior while keeping the
 * existing H.264/AAC/yuv420p delivery settings.
 */
final class DynamicVideoGenerator {
    private static final double END_PAUSE_SECONDS = 0.55;
    private static final double FADE_IN_SECONDS = 0.08;
    private static final double FADE_OUT_SECONDS = 0.14;

    private final String ffmpegCommand;
    private final int timeoutSeconds;
    private final int fps;

    DynamicVideoGenerator(String ffmpegCommand, int timeoutSeconds, int fps) {
        this.ffmpegCommand = ffmpegCommand == null || ffmpegCommand.isBlank()
                ? "ffmpeg"
                : ffmpegCommand;
        this.timeoutSeconds = timeoutSeconds <= 0 ? 180 : timeoutSeconds;
        this.fps = fps <= 0 ? 30 : fps;
    }

    Path renderClip(
            Path presentationFrame,
            Path audioFile,
            Path outputFile,
            int width,
            int height,
            ContentFormat format,
            int index
    ) throws IOException, InterruptedException {
        if (!Files.exists(presentationFrame)) {
            throw new IOException("Dynamic presentation frame not found: " + presentationFrame);
        }
        if (!Files.exists(audioFile)) {
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
        double fadeOutStart = Math.max(0.0, audioDuration - FADE_OUT_SECONDS);

        int safeWidth = even(Math.max(64, width));
        int safeHeight = even(Math.max(64, height));
        String videoFilter = motionFilter(safeWidth, safeHeight, format, index);
        String audioFilter = "afade=t=in:st=0:d=" + formatSeconds(FADE_IN_SECONDS)
                + ",afade=t=out:st=" + formatSeconds(fadeOutStart)
                + ":d=" + formatSeconds(FADE_OUT_SECONDS)
                + ",apad=pad_dur=" + formatSeconds(END_PAUSE_SECONDS);

        List<String> command = new ArrayList<>();
        command.add(ffmpegCommand);
        command.add("-y");
        command.add("-loop");
        command.add("1");
        command.add("-framerate");
        command.add(String.valueOf(fps));
        command.add("-i");
        command.add(presentationFrame.toString());
        command.add("-i");
        command.add(audioFile.toString());
        command.add("-t");
        command.add(formatSeconds(outputDuration));
        command.add("-vf");
        command.add(videoFilter);
        command.add("-af");
        command.add(audioFilter);
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
        command.add(outputFile.toString());

        run(command, "dynamic video render");
        if (!Files.exists(outputFile) || Files.size(outputFile) <= 0) {
            throw new IOException("Dynamic video render did not create a valid file: " + outputFile);
        }
        return outputFile;
    }

    Path combineClips(List<Path> clips, Path outputFile) throws IOException, InterruptedException {
        if (clips == null || clips.isEmpty()) {
            throw new IOException("No dynamic clips were supplied for final video.");
        }
        VideoGenerator combiner = new VideoGenerator(ffmpegCommand, timeoutSeconds);
        return combiner.combineClips(clips, outputFile);
    }

    double probeDurationSeconds(Path mediaFile) throws IOException, InterruptedException {
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

    private String motionFilter(int width, int height, ContentFormat format, int index) {
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

        String x;
        String y;
        switch (format) {
            case THREAD_STORY -> {
                x = decimal(centerX) + "+" + decimal(Math.min(22.0, availableX * 0.28)) + "*sin(n/42)";
                y = decimal(centerY) + "+" + decimal(Math.min(34.0, availableY * 0.30)) + "*cos(n/58)";
            }
            case CONFESSION -> {
                x = decimal(centerX) + "+" + decimal(Math.min(10.0, availableX * 0.18)) + "*sin(n/75)";
                y = decimal(centerY) + "+" + decimal(Math.min(62.0, availableY * 0.42)) + "*sin(n/82)";
            }
            case DEBATE -> {
                double direction = index % 2 == 0 ? 1.0 : -1.0;
                x = decimal(centerX) + (direction >= 0 ? "+" : "-")
                        + decimal(Math.min(38.0, availableX * 0.38)) + "*sin(n/50)";
                y = decimal(centerY) + "+" + decimal(Math.min(18.0, availableY * 0.20)) + "*cos(n/68)";
            }
            case BEST_ANSWERS -> {
                x = decimal(centerX) + "+" + decimal(Math.min(18.0, availableX * 0.24)) + "*sin(n/64)";
                y = decimal(centerY) + "-" + decimal(Math.min(42.0, availableY * 0.32)) + "*sin(n/86)";
            }
            case ESCALATING_CONVERSATION -> {
                x = decimal(centerX) + "+" + decimal(Math.min(14.0, availableX * 0.22)) + "*cos(n/57)";
                y = decimal(centerY) + "+" + decimal(Math.min(54.0, availableY * 0.40)) + "*sin(n/61)";
            }
            default -> throw new IllegalStateException("Unhandled format: " + format);
        }

        return "scale=" + scaledWidth + ":" + scaledHeight
                + ",crop=" + width + ":" + height + ":x=" + x + ":y=" + y
                + ",fps=" + fps
                + ",format=yuv420p";
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
}
