package com.tidyflow.app;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Pure parser and startup policy helper for TidyFlow command-line arguments.
 */
public final class AppArguments {

    private final boolean valid;
    private final boolean background;
    private final boolean selfCheck;
    private final Path selfCheckOutFile;
    private final String errorMessage;

    private AppArguments(
            boolean valid,
            boolean background,
            boolean selfCheck,
            Path selfCheckOutFile,
            String errorMessage
    ) {
        this.valid = valid;
        this.background = background;
        this.selfCheck = selfCheck;
        this.selfCheckOutFile = selfCheckOutFile;
        this.errorMessage = errorMessage;
    }

    public static AppArguments parse(String... args) {
        if (args == null || args.length == 0) {
            return new AppArguments(true, false, false, null, null);
        }

        boolean background = false;
        boolean selfCheck = false;
        Path outFile = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null || arg.isBlank()) {
                return invalid("Blank command-line argument at index " + i);
            }

            switch (arg) {
                case "--background" -> background = true;
                case "--self-check" -> selfCheck = true;
                case "--out" -> {
                    if (i + 1 >= args.length) {
                        return invalid("Missing file path after --out");
                    }
                    String next = args[i + 1];
                    if (next == null || next.isBlank() || next.startsWith("--")) {
                        return invalid("Missing file path after --out");
                    }
                    try {
                        outFile = Path.of(next);
                    } catch (InvalidPathException e) {
                        return invalid("Invalid file path after --out: " + next);
                    }
                    i++;
                }
                default -> {
                    return invalid("Unknown argument: " + arg);
                }
            }
        }

        if (outFile != null && !selfCheck) {
            return invalid("--out can only be used together with --self-check");
        }
        if (background && selfCheck) {
            return invalid("--background and --self-check cannot be used together");
        }

        return new AppArguments(true, background, selfCheck, outFile, null);
    }

    /**
     * Pure decision function for whether the main window should stay hidden at startup.
     * Hidden startup only happens when --background was passed, the system tray is supported,
     * and the user's minimizeToTray setting is enabled.
     */
    public static boolean shouldStartHidden(boolean backgroundRequested, boolean traySupported, boolean minimizeToTray) {
        return backgroundRequested && traySupported && minimizeToTray;
    }

    private static AppArguments invalid(String message) {
        return new AppArguments(false, false, false, null, message);
    }

    public boolean isValid() {
        return valid;
    }

    public boolean background() {
        return background;
    }

    public boolean selfCheck() {
        return selfCheck;
    }

    public Optional<Path> selfCheckOutFile() {
        return Optional.ofNullable(selfCheckOutFile);
    }

    public Optional<String> errorMessage() {
        return Optional.ofNullable(errorMessage);
    }
}
