package com.ironhack.backend.overcast.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Azure OpenAI wiring, server-side ONLY — the key never leaves the backend.
 * All-blank (the default) puts the whole app in deterministic-fallback mode.
 */
@ConfigurationProperties(prefix = "overcast.ai")
public record AiProperties(String endpoint, String apiKey, String deployment, String apiVersion) {

    public boolean configured() {
        return notBlank(endpoint) && notBlank(apiKey) && notBlank(deployment);
    }

    /** Blank = the client's default GA api-version. */
    public String apiVersionOrDefault(String fallback) {
        return notBlank(apiVersion) ? apiVersion : fallback;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
