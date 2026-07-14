package com.ironhack.backend.overcast.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thin Azure OpenAI chat-completions client (plain REST, no SDK). Returns
 * Optional.empty() on ANY problem — no key, timeout, quota, bad response —
 * so callers always fall back to the deterministic path. The AI can only
 * ever produce prose; it has no way to influence a savings figure.
 */
@Component
public class AzureOpenAiClient {

    private static final Logger log = LoggerFactory.getLogger(AzureOpenAiClient.class);
    private static final String API_VERSION = "2024-06-01";

    private final AiProperties props;
    private final RestClient rest;

    public AzureOpenAiClient(AiProperties props) {
        this.props = props;
        this.rest = RestClient.create();
    }

    public boolean configured() {
        return props.configured();
    }

    /** One chat completion; empty on any failure (caller falls back). */
    public Optional<String> complete(String systemPrompt, String userPrompt) {
        if (!props.configured()) return Optional.empty();
        try {
            String url = props.endpoint().replaceAll("/+$", "")
                    + "/openai/deployments/" + props.deployment()
                    + "/chat/completions?api-version=" + props.apiVersionOrDefault(API_VERSION);
            JsonNode response = rest.post()
                    .uri(url)
                    .header("api-key", props.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "messages", List.of(
                                    Map.of("role", "system", "content", systemPrompt),
                                    Map.of("role", "user", "content", userPrompt)),
                            "max_tokens", 400,
                            "temperature", 0.4))
                    .retrieve()
                    .body(JsonNode.class);
            String content = response == null ? null
                    : response.path("choices").path(0).path("message").path("content").asText(null);
            return Optional.ofNullable(content).filter(s -> !s.isBlank());
        } catch (Exception e) {
            log.warn("Azure OpenAI call failed, using deterministic fallback: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
