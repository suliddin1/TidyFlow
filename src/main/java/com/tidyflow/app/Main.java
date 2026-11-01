package com.tidyflow.app;

import javafx.application.Application;

/**
 * Application launcher class. Handles headless --self-check and --cleanup-autostart before initializing JavaFX.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        AppArguments parsed = AppArguments.parse(args);
        if (!parsed.isValid()) {
            System.err.println("[TidyFlow] " + parsed.errorMessage().orElse("Invalid command-line arguments"));
            System.exit(2);
            return;
        }

        if (parsed.selfCheck()) {
            int exitCode = SelfCheckRunner.execute(parsed.selfCheckOutFile().orElse(null));
            System.exit(exitCode);
            return;
        }

        if (parsed.cleanupAutostart()) {
            try {
                WindowsRegistryAutostartService.removeRegistryEntry();
                System.exit(0);
            } catch (Exception e) {
                System.err.println("[TidyFlow] Failed to clean up autostart registry entry: " + e.getMessage());
                System.exit(1);
            }
            return;
        }

        Application.launch(TidyFlowApp.class, args);
    }
}
