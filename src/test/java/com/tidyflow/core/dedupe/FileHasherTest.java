package com.tidyflow.core.dedupe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class FileHasherTest {

    @Test
    void knownContentGivesExpectedSha256(@TempDir Path tempDir) throws Exception {
        Path file = Files.writeString(tempDir.resolve("hello.txt"), "hello world", StandardCharsets.UTF_8);

        String sha256 = FileHasher.computeFullSha256(file, () -> false);

        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", sha256);
    }

    @Test
    void largeFileIsStreamedCorrectly(@TempDir Path tempDir) throws Exception {
        // 200 KB (> 3 x 64 KB buffer)
        byte[] content = new byte[200 * 1024];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) ((i * 31) & 0xFF);
        }
        Path largeFile = Files.write(tempDir.resolve("large.bin"), content);

        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        String actual = FileHasher.computeFullSha256(largeFile, () -> false);

        assertEquals(expected, actual);
    }

    @Test
    void quickHashMatchesWhenFirstAndLast4KbMatch(@TempDir Path tempDir) throws Exception {
        byte[] a = new byte[16 * 1024];
        byte[] b = new byte[16 * 1024];
        for (int i = 0; i < a.length; i++) {
            a[i] = (byte) (i & 0x7F);
            b[i] = (byte) (i & 0x7F);
        }
        // Modify middle byte (outside first 4 KB and last 4 KB)
        b[8000] = (byte) 0xFF;

        Path fileA = Files.write(tempDir.resolve("a.bin"), a);
        Path fileB = Files.write(tempDir.resolve("b.bin"), b);

        String quickA = FileHasher.computeQuickHash(fileA, a.length, () -> false);
        String quickB = FileHasher.computeQuickHash(fileB, b.length, () -> false);
        assertEquals(quickA, quickB);

        String fullA = FileHasher.computeFullSha256(fileA, () -> false);
        String fullB = FileHasher.computeFullSha256(fileB, () -> false);
        assertNotEquals(fullA, fullB);
    }
}
