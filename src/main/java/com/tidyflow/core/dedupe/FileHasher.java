package com.tidyflow.core.dedupe;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * Computes partial (quick 4 KB head + 4 KB tail via positioned FileChannel reads)
 * and full streaming SHA-256 hashes (reusing a 1 MB per-thread buffer).
 */
public final class FileHasher {

    public static final int QUICK_HASH_CHUNK_BYTES = 4 * 1024;
    public static final long SMALL_FILE_DIRECT_FULL_HASH_BYTES = 64L * 1024L;
    public static final int FULL_HASH_BUFFER_BYTES = 1024 * 1024; // 1 MB (winner in Measurement 4)

    private static final HexFormat HEX = HexFormat.of();
    private static final ThreadLocal<byte[]> FULL_HASH_BUFFER =
            ThreadLocal.withInitial(() -> new byte[FULL_HASH_BUFFER_BYTES]);
    private static final ThreadLocal<ByteBuffer> QUICK_HASH_BUFFER =
            ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(QUICK_HASH_CHUNK_BYTES));

    private FileHasher() {
    }

    /**
     * Computes SHA-256 of the first 4 KB and the last 4 KB of the file using a single FileChannel
     * with positioned reads.
     */
    public static String computeQuickHash(Path path, long sizeBytes, BooleanSupplier isCancelled) throws IOException {
        checkCancelled(isCancelled);
        MessageDigest digest = newSha256();
        ByteBuffer buffer = QUICK_HASH_BUFFER.get();

        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            buffer.clear();
            readPositionedUpTo(channel, buffer, 0L);
            buffer.flip();
            digest.update(buffer);

            checkCancelled(isCancelled);

            if (sizeBytes > QUICK_HASH_CHUNK_BYTES) {
                long tailStart = Math.max(QUICK_HASH_CHUNK_BYTES, sizeBytes - QUICK_HASH_CHUNK_BYTES);
                buffer.clear();
                readPositionedUpTo(channel, buffer, tailStart);
                buffer.flip();
                digest.update(buffer);
            }
        }
        return HEX.formatHex(digest.digest());
    }

    /**
     * Computes the full SHA-256 of a file using a thread-local 1 MB buffer, checking for cancellation
     * between buffer reads.
     */
    public static String computeFullSha256(Path path, BooleanSupplier isCancelled) throws IOException {
        return computeFullSha256(path, isCancelled, null);
    }

    /**
     * Computes the full SHA-256 of a file using a thread-local 1 MB buffer, checking for cancellation
     * and reporting incremental bytes read between buffer reads.
     */
    public static String computeFullSha256(
            Path path,
            BooleanSupplier isCancelled,
            LongConsumer bytesReadCallback
    ) throws IOException {
        checkCancelled(isCancelled);
        MessageDigest digest = newSha256();
        byte[] buffer = FULL_HASH_BUFFER.get();

        try (InputStream in = Files.newInputStream(path, StandardOpenOption.READ)) {
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                checkCancelled(isCancelled);
                digest.update(buffer, 0, bytesRead);
                if (bytesReadCallback != null && bytesRead > 0) {
                    bytesReadCallback.accept(bytesRead);
                }
            }
        }
        checkCancelled(isCancelled);
        return HEX.formatHex(digest.digest());
    }

    private static void readPositionedUpTo(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long offset = position;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, offset);
            if (read <= 0) {
                break;
            }
            offset += read;
        }
    }

    private static void checkCancelled(BooleanSupplier isCancelled) {
        if ((isCancelled != null && isCancelled.getAsBoolean()) || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Hashing cancelled");
        }
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
