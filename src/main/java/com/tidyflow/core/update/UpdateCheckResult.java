package com.tidyflow.core.update;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of checking for a newer TidyFlow release.
 */
public record UpdateCheckResult(
        boolean updateAvailable,
        String currentVersion,
        String latestVersion,
        String releaseHtmlUrl,
        String releaseNotes,
        List<ReleaseAsset> assets,
        String errorMessage
) {
    public UpdateCheckResult {
        Objects.requireNonNull(currentVersion, "currentVersion must not be null");
        assets = assets == null ? List.of() : List.copyOf(assets);
    }

    public static UpdateCheckResult available(
            String currentVersion,
            String latestVersion,
            String releaseHtmlUrl,
            String releaseNotes,
            List<ReleaseAsset> assets
    ) {
        return new UpdateCheckResult(
                true,
                currentVersion,
                latestVersion,
                releaseHtmlUrl,
                releaseNotes,
                assets,
                null
        );
    }

    public static UpdateCheckResult upToDate(String currentVersion, String latestVersion, String releaseHtmlUrl) {
        return new UpdateCheckResult(
                false,
                currentVersion,
                latestVersion,
                releaseHtmlUrl,
                "",
                List.of(),
                null
        );
    }

    public static UpdateCheckResult failed(String currentVersion, String errorMessage) {
        return new UpdateCheckResult(
                false,
                currentVersion,
                currentVersion,
                null,
                "",
                List.of(),
                errorMessage == null || errorMessage.isBlank() ? "Unknown error" : errorMessage
        );
    }

    public boolean isSuccess() {
        return errorMessage == null;
    }

    public Optional<ReleaseAsset> findInstallerExe() {
        return assets.stream()
                .filter(a -> a.name().toLowerCase(Locale.ROOT).endsWith(".exe"))
                .findFirst();
    }

    public Optional<ReleaseAsset> findPortableZip() {
        return assets.stream()
                .filter(a -> a.name().toLowerCase(Locale.ROOT).endsWith("-portable.zip")
                        || a.name().toLowerCase(Locale.ROOT).endsWith(".zip"))
                .findFirst();
    }

    public Optional<ReleaseAsset> findChecksumsFile() {
        return assets.stream()
                .filter(a -> a.name().equalsIgnoreCase("SHA256SUMS.txt"))
                .findFirst();
    }
}
