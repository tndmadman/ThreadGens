package redditTxtToImg;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class ProcessRunner {
    private ProcessRunner() {
    }

    static String runAndCapture(List<String> commandParts, String label, int timeoutSeconds)
            throws IOException, InterruptedException {
        return runAndCapture(commandParts, label, timeoutSeconds, null);
    }

    static String runAndCapture(List<String> commandParts, String label, int timeoutSeconds, String stdinText)
            throws IOException, InterruptedException {
        int effectiveTimeout = timeoutSeconds <= 0 ? 120 : timeoutSeconds;
        ProcessBuilder processBuilder = new ProcessBuilder(commandParts);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StringBuilder readerError = new StringBuilder();
        Thread outputThread = new Thread(() -> {
            try {
                process.getInputStream().transferTo(output);
            } catch (IOException e) {
                readerError.append(e.getMessage());
            }
        }, "threadgens-process-output");
        outputThread.setDaemon(true);
        outputThread.start();

        if (stdinText != null) {
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(stdinText.getBytes(StandardCharsets.UTF_8));
            }
        }

        boolean finished = process.waitFor(effectiveTimeout, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            outputThread.join(1000);
            throw new IOException(label + " timed out after " + effectiveTimeout + " seconds. Last output: "
                    + output.toString(StandardCharsets.UTF_8));
        }

        outputThread.join(1000);
        String capturedOutput = output.toString(StandardCharsets.UTF_8);
        if (readerError.length() > 0) {
            capturedOutput = capturedOutput + System.lineSeparator() + "Output reader warning: " + readerError;
        }
        if (process.exitValue() != 0) {
            throw new IOException(label + " failed with exit code " + process.exitValue() + ": " + capturedOutput);
        }
        return capturedOutput;
    }
}
