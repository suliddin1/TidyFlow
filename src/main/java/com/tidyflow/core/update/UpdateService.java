package com.tidyflow.core.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tidyflow.util.AppVersion;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Checks GitHub Releases for newer versions of TidyFlow and downloads/verifies release assets.
 */
public class UpdateService {

    public static final URI DEFAULT_LATEST_RELEASE_URI =
            URI.create("https://api.github.com/repos/suliddin1/TidyFlow/releases/latest");
    public static final String RELEASES_HTML_URL =
            "https://github.com/suliddin1/TidyFlow/releases";

    @FunctionalInterface
    public interface ReleaseFetcher {
        HttpFetchResult fetch(URI uri) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    public interface AssetDownloader {
        Path download(URI uri, Path destinationFile) throws IOException, InterruptedException;
    }

    public record HttpFetchResult(int statusCode, String body) {
    }

    private final String currentVersion;
    private final URI latestReleaseUri;
    private final ReleaseFetcher releaseFetcher;
    private final AssetDownloader assetDownloader;
    private final ObjectMapper mapper = new ObjectMapper();

    public UpdateService() {
        this(AppVersion.load(), DEFAULT_LATEST_RELEASE_URI, createDefaultHttpClient());
    }

    private UpdateService(String currentVersion, URI latestReleaseUri, HttpClient httpClient) {
        this(
                currentVersion,
                latestReleaseUri,
                uri -> defaultFetch(httpClient, uri),
                (uri, dest) -> defaultDownload(httpClient, uri, dest)
        );
    }

    public UpdateService(
            String currentVersion,
            URI latestReleaseUri,
            ReleaseFetcher releaseFetcher,
            AssetDownloader assetDownloader
    ) {
        this.currentVersion = Objects.requireNonNull(currentVersion, "currentVersion must not be null");
        this.latestReleaseUri = Objects.requireNonNull(latestReleaseUri, "latestReleaseUri must not be null");
        this.releaseFetcher = Objects.requireNonNull(releaseFetcher, "releaseFetcher must not be null");
        this.assetDownloader = Objects.requireNonNull(assetDownloader, "assetDownloader must not be null");
    }

    /**
     * Queries the configured GitHub Releases endpoint and compares the latest tag with {@code currentVersion}.
     * Never throws; network or parse errors are returned in {@link UpdateCheckResult#errorMessage()}.
     */
    public UpdateCheckResult checkForUpdates() {
        try {
            HttpFetchResult response = releaseFetcher.fetch(latestReleaseUri);
            if (response.statusCode() == 404) {
                // No published release yet on GitHub
                return UpdateCheckResult.upToDate(currentVersion, currentVersion, RELEASES_HTML_URL);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return UpdateCheckResult.failed(currentVersion, "GitHub API returned HTTP " + response.statusCode());
            }
            return parseReleaseJson(currentVersion, response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return UpdateCheckResult.failed(currentVersion, "Update check interrupted");
        } catch (Exception e) {
            String msg = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage()
                    : e.getClass().getSimpleName();
            return UpdateCheckResult.failed(currentVersion, msg);
        }
    }

    /**
     * Parses a GitHub Releases API JSON payload and compares it against {@code currentVer}.
     */
    public UpdateCheckResult parseReleaseJson(String currentVer, String jsonBody) throws IOException {
        if (jsonBody == null || jsonBody.isBlank()) {
            return UpdateCheckResult.failed(currentVer, "Empty response from update server");
        }
        JsonNode root = mapper.readTree(jsonBody);
        String tagName = textOrDefault(root, "tag_name", "");
        String latestVersion = AppVersion.normalizeVersion(tagName);
        if (latestVersion.isEmpty()) {
            return UpdateCheckResult.failed(currentVer, "Release payload missing tag_name");
        }

        String htmlUrl = textOrDefault(root, "html_url", RELEASES_HTML_URL);
        String body = textOrDefault(root, "body", "");

        List<ReleaseAsset> assets = new ArrayList<>();
        JsonNode assetsNode = root.get("assets");
        if (assetsNode != null && assetsNode.isArray()) {
            for (JsonNode assetNode : assetsNode) {
                String name = textOrDefault(assetNode, "name", "");
                String url = textOrDefault(assetNode, "browser_download_url", "");
                long size = assetNode.has("size") ? assetNode.get("size").asLong(0L) : 0L;
                if (!name.isEmpty() && !url.isEmpty()) {
                    assets.add(new ReleaseAsset(name, url, size));
                }
            }
        }

        boolean newer = AppVersion.isNewerVersion(currentVer, latestVersion);
        if (newer) {
            return UpdateCheckResult.available(currentVer, latestVersion, htmlUrl, body, assets);
        }
        return UpdateCheckResult.upToDate(currentVer, latestVersion, htmlUrl);
    }

    /**
     * Downloads the preferred update package (.exe installer if {@code preferInstaller} is true and present,
     * otherwise portable .zip) into {@code destinationDir} and verifies its SHA-256 hash against SHA256SUMS.txt
     * when available.
     */
    public Path downloadAndVerifyUpdate(
            UpdateCheckResult checkResult,
            Path destinationDir,
            boolean preferInstaller
    ) throws IOException, InterruptedException {
        Objects.requireNonNull(checkResult, "checkResult must not be null");
        Objects.requireNonNull(destinationDir, "destinationDir must not be null");

        Optional<ReleaseAsset> chosenAsset = preferInstaller
                ? checkResult.findInstallerExe().or(checkResult::findPortableZip)
                : checkResult.findPortableZip().or(checkResult::findInstallerExe);

        if (chosenAsset.isEmpty()) {
            throw new IOException("No downloadable installer or portable archive found in release " + checkResult.latestVersion());
        }

        Files.createDirectories(destinationDir);
        ReleaseAsset targetAsset = chosenAsset.get();
        Path downloadedFile = destinationDir.resolve(targetAsset.name());
        assetDownloader.download(URI.create(targetAsset.downloadUrl()), downloadedFile);

        Optional<ReleaseAsset> checksumAsset = checkResult.findChecksumsFile();
        if (checksumAsset.isPresent()) {
            Path sumsFile = destinationDir.resolve("SHA256SUMS-" + checkResult.latestVersion() + ".txt");
            try {
                assetDownloader.download(URI.create(checksumAsset.get().downloadUrl()), sumsFile);
                String sumsContent = Files.readString(sumsFile, StandardCharsets.UTF_8);
                Map<String, String> expectedHashes = parseSha256Sums(sumsContent);
                String expected = expectedHashes.get(targetAsset.name());
                if (expected != null) {
                    String actual = computeSha256Hex(downloadedFile);
                    if (!expected.equalsIgnoreCase(actual)) {
                        Files.deleteIfExists(downloadedFile);
                        throw new IOException("SHA-256 checksum mismatch for " + targetAsset.name()
                                + " (expected " + expected + ", got " + actual + ")");
                    }
                }
            } finally {
                Files.deleteIfExists(sumsFile);
            }
        }

        return downloadedFile;
    }

    public static Map<String, String> parseSha256Sums(String content) {
        Map<String, String> map = new HashMap<>();
        if (content == null || content.isBlank()) {
            return map;
        }
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+", 2);
            if (parts.length == 2 && parts[0].length() == 64) {
                String fileName = parts[1].trim();
                if (fileName.startsWith("*")) {
                    fileName = fileName.substring(1).trim();
                }
                map.put(fileName, parts[0].toLowerCase(Locale.ROOT));
            }
        }
        return map;
    }

    public static String computeSha256Hex(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch ( IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to compute SHA-256 checksum", e);
        }
    }

    private static String textOrDefault(JsonNode node, String field, String fallback) {
        if (node == null || !node.hasNonNull(field)) {
            return fallback;
        }
        String text = node.get(field).asText("");
        return text == null || text.isBlank() ? fallback : text.trim();
    }

    private static HttpClient createDefaultHttpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    private static HttpFetchResult defaultFetch(HttpClient client, URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "TidyFlow-Updater/" + AppVersion.load())
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new HttpFetchResult(response.statusCode(), response.body());
    }

    private static Path defaultDownload(HttpClient client, URI uri, Path destinationFile)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(120))
                .header("User-Agent", "TidyFlow-Updater/" + AppVersion.load())
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Download failed with HTTP " + response.statusCode() + " for " + uri);
        }
        Path tempFile = destinationFile.resolveSibling(destinationFile.getFileName().toString() + ".part");
        try (InputStream body = response.body()) {
            Files.copy(body, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(tempFile, destinationFile, StandardCopyOption.REPLACE_EXISTING);
        return destinationFile;
    }
}
