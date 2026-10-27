package com.tidyflow.app;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Windows implementation of AutostartService that writes/removes the TidyFlow entry
 * in HKCU\Software\Microsoft\Windows\CurrentVersion\Run using reg.exe via ProcessBuilder.
 * Only active when running as a packaged app (jpackage.app-path property present).
 */
public class WindowsRegistryAutostartService implements AutostartService {

    public static final String RUN_REGISTRY_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    public static final String VALUE_NAME = "TidyFlow";
    public static final String JPACKAGE_APP_PATH_PROPERTY = "jpackage.app-path";

    private final Supplier<String> appPathSupplier;

    public WindowsRegistryAutostartService() {
        this(() -> System.getProperty(JPACKAGE_APP_PATH_PROPERTY));
    }

    public WindowsRegistryAutostartService(Supplier<String> appPathSupplier) {
        this.appPathSupplier = Objects.requireNonNull(appPathSupplier, "appPathSupplier must not be null");
    }

    @Override
    public boolean isAvailable() {
        String appPath = appPathSupplier.get();
        return appPath != null && !appPath.isBlank();
    }

    @Override
    public void setEnabled(boolean enabled) throws IOException {
        if (!isAvailable()) {
            return;
        }
        String appPath = appPathSupplier.get().trim();
        List<String> command = enabled ? toWindowsProcessCommand(buildEnableCommand(appPath)) : buildDisableCommand();

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        try {
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("reg.exe timed out while updating Windows startup entry");
            }
            // Note: when deleting a value that does not exist, reg.exe exits with 1, which is benign when disabling.
            if (enabled && process.exitValue() != 0) {
                throw new IOException("reg.exe exited with code " + process.exitValue());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while updating Windows startup entry", e);
        }
    }

    /**
     * Pure helper that formats the HKCU Run value data as the quoted launcher path followed by --background.
     */
    public static String formatRegistryValueData(String appPath) {
        if (appPath == null || appPath.isBlank()) {
            throw new IllegalArgumentException("appPath must not be blank");
        }
        String trimmed = appPath.trim();
        String quoted = (trimmed.startsWith("\"") && trimmed.endsWith("\""))
                ? trimmed
                : "\"" + trimmed + "\"";
        return quoted + " --background";
    }

    /**
     * Pure command builder for adding the HKCU Run value with proper quoting for paths containing spaces.
     */
    public static List<String> buildEnableCommand(String appPath) {
        String valueData = formatRegistryValueData(appPath);

        return List.of(
                "reg.exe",
                "add",
                RUN_REGISTRY_KEY,
                "/v",
                VALUE_NAME,
                "/t",
                "REG_SZ",
                "/d",
                valueData,
                "/f"
        );
    }

    /**
     * Escapes embedded double quotes for Windows ProcessBuilder (allowAmbiguousCommands=true by default)
     * so reg.exe receives literal quotes around launcher paths containing spaces.
     */
    static List<String> toWindowsProcessCommand(List<String> command) {
        boolean strictMode = "false".equalsIgnoreCase(System.getProperty("jdk.lang.Process.allowAmbiguousCommands"));
        if (strictMode) {
            return command;
        }
        return command.stream()
                .map(arg -> arg.contains("\"") ? arg.replace("\"", "\\\"") : arg)
                .toList();
    }

    /**
     * Pure command builder for removing the HKCU Run value.
     */
    public static List<String> buildDisableCommand() {
        return List.of(
                "reg.exe",
                "delete",
                RUN_REGISTRY_KEY,
                "/v",
                VALUE_NAME,
                "/f"
        );
    }
}
