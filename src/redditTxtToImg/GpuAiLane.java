package redditTxtToImg;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Cross-process GPU coordination for ThreadGens AI workloads.
 *
 * Qwen narration takes a shared lease so independent workers can still
 * micro-batch together. ComfyUI OP-image generation takes an exclusive lease
 * so it can own the GPU without another ThreadGens CUDA workload starting.
 */
final class GpuAiLane implements AutoCloseable {
    private static final Path LOCK_PATH = Path.of("output", "runtime", "gpu_ai_lane.lock");

    private final FileChannel channel;
    private final FileLock lock;

    private GpuAiLane(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static GpuAiLane acquireShared() throws IOException {
        return acquire(true);
    }

    static GpuAiLane acquireExclusive() throws IOException {
        return acquire(false);
    }

    private static GpuAiLane acquire(boolean shared) throws IOException {
        Files.createDirectories(LOCK_PATH.getParent());
        FileChannel channel = FileChannel.open(
                LOCK_PATH,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.lock(0L, Long.MAX_VALUE, shared);
            return new GpuAiLane(channel, lock);
        } catch (IOException | RuntimeException e) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    static Path lockPath() {
        return LOCK_PATH;
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        } catch (IOException e) {
            failure = e;
        }
        try {
            channel.close();
        } catch (IOException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
