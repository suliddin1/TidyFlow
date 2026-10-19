package com.tidyflow.app;

import java.io.IOException;

/**
 * Abstraction for configuring whether TidyFlow starts automatically when Windows signs in.
 */
public interface AutostartService {

    boolean isAvailable();

    void setEnabled(boolean enabled) throws IOException;
}
