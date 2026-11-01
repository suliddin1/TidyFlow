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
 * Computes partial (4 KB head + 4 KB tail via positioned FileChannel reads) and full streaming
 * SHA-256 hashes, reusing one MessageDigest and one buffer per worker thread.
 * For files of 8 KB or less, head + tail covers the entire file, so {@link #computeQuickHash}
 * reads the whole file in a single read and returns its full SHA-256.
 */
public final class FileHasher {

    public static final int QUICK_HASH_CHUNK_BYTES = 4 * 1024;
    public static final long SMALL_FILE_SINGLE_READ_BYTES = 2L * QUICK_HASH_CHUNK_BYTES; // 8 KB
    public static final int FULL_HASH_BUFFER_BYTES = 1024 * 1024; // 1 MB

    private static final HexFormat HEX = HexFormat.of();
    private static final ThreadLocal<MessageDigest> THREAD_LOCAL_SHA256 =
            ThreadLocal.withInitial(FileHasher::createSha256);
    private static final ThreadLocal<ByteBuffer> QUICK_HASH_BUFFER =
            ThreadLocal.withInitial(() -> ByteBuffer.allocate((int) SMALL_FILE_SINGLE_READ_BYTES));
    private static final ThreadLocal<byte[]> FULL_HASH_BUFFER =
            ThreadLocal.withInitial(() -> new byte[FULL_HASH_BUFFER_BYTES]);

    private FileHasher() {
    }

    /**
     * Computes SHA-256 of the file's content if {@code sizeBytes <= 8 KB} (in a single read),
     * or SHA-256 of the first 4 KB + last 4 KB if {@code sizeBytes > 8 KB} using one FileChannel
     * with positioned reads.
     */
    public static String computeQuickHash(Path path, long sizeBytes, BooleanSupplier isCancelled) throws IOException {
        checkCancelled(isCancelled);
        MessageDigest digest = resetSha256();
        ByteBuffer buffer = QUICK_HASH_BUFFER.get();

        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            if (sizeBytes <= SMALL_FILE_SINGLE_READ_BYTES) {
                int targetBytes = (int) Math.max(0L, sizeBytes);
                buffer.clear();
                buffer.limit(targetBytes);
                readPositionedUpTo(channel, buffer, 0L);
                digest.update(buffer.array(), 0, buffer.position());
            } else {
                buffer.clear();
                buffer.limit(QUICK_HASH_CHUNK_BYTES);
                readPositionedUpTo(channel, buffer, 0L);
                digest.update(buffer.array(), 0, buffer.position());

                checkCancelled(isCancelled);

                long tailStart = Math.max(QUICK_HASH_CHUNK_BYTES, sizeBytes - QUICK_HASH_CHUNK_BYTES);
                buffer.clear();
                buffer.limit(QUICK_HASH_CHUNK_BYTES);
                readPositionedUpTo(channel, buffer, tailStart);
                digest.update(buffer.array(), 0, buffer.position());
            }
        }
        return HEX.formatHex(digest.digest());
    }

    /**
     * Computes the full SHA-256 of a file using a thread-local 1 MB buffer and MessageDigest,
     * checking for cancellation between buffer reads.
     */
    public static String computeFullSha256(Path path, BooleanSupplier isCancelled) throws IOException {
        return computeFullSha256(path, isCancelled, null);
    }

    /**
     * Computes the full SHA-256 of a file using a thread-local 1 MB buffer and MessageDigest,
     * checking for cancellation and reporting incremental bytes read between buffer reads.
     */
    public static String computeFullSha256(
            Path path,
            BooleanSupplier isCancelled,
            LongConsumer bytesReadCallback
    ) throws IOException {
        checkCancelled(isCancelled);
        MessageDigest digest = resetSha256();
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

    private static MessageDigest resetSha256() {
        MessageDigest digest = THREAD_LOCAL_SHA256.get();
        digest.reset();
        return digest;
    }

    private static MessageDigest createSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
