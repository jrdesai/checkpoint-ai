package io.github.jrdesai.checkpoint_ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI configuration.
 * Builds a ready-to-use ChatClient bean from the
 * auto-configured ChatClient.Builder.
 */
@Configuration
public class AiConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("""
                        You are a senior software architect analysing
                        Java codebases. Be precise, factual, and concise.
                        Base all analysis strictly on the provided context.
                        Never fabricate metrics or invent code details.
                        """)
                .build();
    }
}