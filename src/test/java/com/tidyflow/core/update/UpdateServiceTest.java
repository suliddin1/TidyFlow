package com.tidyflow.core.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateServiceTest {

    private static final String SAMPLE_NEWER_RELEASE_JSON = """
            {
              "tag_name": "v1.1.0",
              "html_url": "https://github.com/suliddin1/TidyFlow/releases/tag/v1.1.0",
              "body": "Bug fixes and improvements",
              "assets": [
                {
                  "name": "TidyFlow-1.1.0.exe",
                  "browser_download_url": "https://example.com/TidyFlow-1.1.0.exe",
                  "size": 52428800
                },
                {
                  "name": "TidyFlow-1.1.0-portable.zip",
                  "browser_download_url": "https://example.com/TidyFlow-1.1.0-portable.zip",
                  "size": 58720256
                },
                {
                  "name": "SHA256SUMS.txt",
                  "browser_download_url": "https://example.com/SHA256SUMS.txt",
                  "size": 192
                }
              ]
            }
            """;

    @Test
    void detectsNewerReleaseAndParsesAssets() {
        UpdateService service = new UpdateService(
                "1.0.0",
                UpdateService.DEFAULT_LATEST_RELEASE_URI,
                uri -> new UpdateService.HttpFetchResult(200, SAMPLE_NEWER_RELEASE_JSON),
                (uri, dest) -> dest
        );

        UpdateCheckResult result = service.checkForUpdates();

        assertTrue(result.isSuccess());
        assertTrue(result.updateAvailable());
        assertEquals("1.0.0", result.currentVersion());
        assertEquals("1.1.0", result.latestVersion());
        assertTrue(result.findInstallerExe().isPresent());
        assertTrue(result.findPortableZip().isPresent());
        assertTrue(result.findChecksumsFile().isPresent());
    }

    @Test
    void reportsUpToDateWhenVersionMatchesOrIsOlder() {
        UpdateService service = new UpdateService(
                "1.1.0",
                UpdateService.DEFAULT_LATEST_RELEASE_URI,
                uri -> new UpdateService.HttpFetchResult(200, SAMPLE_NEWER_RELEASE_JSON),
                (uri, dest) -> dest
        );

        UpdateCheckResult result = service.checkForUpdates();

        assertTrue(result.isSuccess());
        assertFalse(result.updateAvailable());
        assertEquals("1.1.0", result.latestVersion());
    }

    @Test
    void handlesHttp404AsUpToDateAndNetworkFailureGracefully() {
        UpdateService notPublishedYet = new UpdateService(
                "1.0.0",
                UpdateService.DEFAULT_LATEST_RELEASE_URI,
                uri -> new UpdateService.HttpFetchResult(404, "{}"),
                (uri, dest) -> dest
        );
        UpdateCheckResult res404 = notPublishedYet.checkForUpdates();
        assertTrue(res404.isSuccess());
        assertFalse(res404.updateAvailable());

        UpdateService offline = new UpdateService(
                "1.0.0",
                UpdateService.DEFAULT_LATEST_RELEASE_URI,
                uri -> {
                    throw new IOException("Network unreachable");
                },
                (uri, dest) -> dest
        );
        UpdateCheckResult resErr = offline.checkForUpdates();
        assertFalse(resErr.isSuccess());
        assertFalse(resErr.updateAvailable());
        assertTrue(resErr.errorMessage().contains("Network unreachable"));
    }

    @Test
    void downloadsAndVerifiesSha256Checksum(@TempDir Path tempDir) throws Exception {
        byte[] fakeInstallerBytes = "fake-installer-binary-payload".getBytes(StandardCharsets.UTF_8);
        Path staging = tempDir.resolve("sample.bin");
        Files.write(staging, fakeInstallerBytes);
        String validHash = UpdateService.computeSha256Hex(staging);

        UpdateService service = new UpdateService(
                "1.0.0",
                UpdateService.DEFAULT_LATEST_RELEASE_URI,
                uri -> new UpdateService.HttpFetchResult(200, SAMPLE_NEWER_RELEASE_JSON),
                (URI uri, Path dest) -> {
                    if (uri.toString().endsWith("SHA256SUMS.txt")) {
                        Files.writeString(dest, validHash + "  TidyFlow-1.1.0.exe\n", StandardCharsets.UTF_8);
                    } else {
                        Files.write(dest, fakeInstallerBytes);
                    }
                    return dest;
                }
        );

        UpdateCheckResult checkResult = service.checkForUpdates();
        Path downloaded = service.downloadAndVerifyUpdate(checkResult, tempDir.resolve("downloads"), true);

        assertTrue(Files.exists(downloaded));
        assertEquals("TidyFlow-1.1.0.exe", downloaded.getFileName().toString());
    }

    @Test
    void deletesDownloadedFileAndThrowsOnSha256Mismatch(@TempDir Path tempDir) {
        String wrongHash = "0".repeat(64);
        UpdateService service = new UpdateService(
                "1.0.0",
                UpdateService.DEFAULT_LATEST_RELEASE_URI,
                uri -> new UpdateService.HttpFetchResult(200, SAMPLE_NEWER_RELEASE_JSON),
                (URI uri, Path dest) -> {
                    if (uri.toString().endsWith("SHA256SUMS.txt")) {
                        Files.writeString(dest, wrongHash + "  TidyFlow-1.1.0.exe\n", StandardCharsets.UTF_8);
                    } else {
                        Files.writeString(dest, "corrupted-payload", StandardCharsets.UTF_8);
                    }
                    return dest;
                }
        );

        UpdateCheckResult checkResult = service.checkForUpdates();
        Path downloadDir = tempDir.resolve("downloads");

        assertThrows(IOException.class, () ->
                service.downloadAndVerifyUpdate(checkResult, downloadDir, true));
        assertFalse(Files.exists(downloadDir.resolve("TidyFlow-1.1.0.exe")));
    }
}
