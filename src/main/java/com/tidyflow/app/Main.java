package com.tidyflow.app;

import javafx.application.Application;

/**
 * Application launcher class. Handles headless --self-check before initializing JavaFX.
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

        Application.launch(TidyFlowApp.class, args);
    }
}
