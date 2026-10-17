package com.tidyflow.core.dedupe;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Computes partial (quick 4 KB head + 4 KB tail) and full streaming SHA-256 hashes.
 */
public final class FileHasher {

    public static final int QUICK_HASH_CHUNK_BYTES = 4 * 1024;
    public static final int FULL_HASH_BUFFER_BYTES = 64 * 1024;

    private static final HexFormat HEX = HexFormat.of();

    private FileHasher() {
    }

    /**
     * Computes SHA-256 of the first 4 KB and the last 4 KB of the file.
     */
    public static String computeQuickHash(Path path, long sizeBytes, BooleanSupplier isCancelled) throws IOException {
        checkCancelled(isCancelled);
        MessageDigest digest = newSha256();

        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(QUICK_HASH_CHUNK_BYTES);
            readFullyUpTo(channel, buffer);
            buffer.flip();
            digest.update(buffer);

            checkCancelled(isCancelled);

            if (sizeBytes > QUICK_HASH_CHUNK_BYTES) {
                long tailStart = Math.max(QUICK_HASH_CHUNK_BYTES, sizeBytes - QUICK_HASH_CHUNK_BYTES);
                channel.position(tailStart);
                buffer.clear();
                readFullyUpTo(channel, buffer);
                buffer.flip();
                digest.update(buffer);
            }
        }
        return HEX.formatHex(digest.digest());
    }

    /**
     * Computes the full SHA-256 of a file using a 64 KB streaming buffer, checking for cancellation while reading.
     */
    public static String computeFullSha256(Path path, BooleanSupplier isCancelled) throws IOException {
        checkCancelled(isCancelled);
        MessageDigest digest = newSha256();
        byte[] buffer = new byte[FULL_HASH_BUFFER_BYTES];

        try (InputStream in = Files.newInputStream(path, StandardOpenOption.READ)) {
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                checkCancelled(isCancelled);
                digest.update(buffer, 0, bytesRead);
            }
        }
        return HEX.formatHex(digest.digest());
    }

    private static void readFullyUpTo(SeekableByteChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read == -1) {
                break;
            }
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
