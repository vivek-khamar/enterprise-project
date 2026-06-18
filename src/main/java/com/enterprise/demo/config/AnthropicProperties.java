package com.enterprise.demo.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "anthropic")
@Getter
@Setter
public class AnthropicProperties {

    /** Anthropic API key — set via ANTHROPIC_API_KEY environment variable. */
    private String apiKey = "";

    /** Anthropic API base URL. */
    private String baseUrl = "https://api.anthropic.com";

    /** Claude model to use for KYC document analysis. */
    private String model = "claude-sonnet-4-6";
}
