package com.enterprise.demo.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Declares a CaffeineCacheManager that is used in both the full application context
 * and @WebMvcTest slices (Caffeine is a pure library — no external infrastructure needed).
 * Because this bean satisfies @ConditionalOnMissingBean(CacheManager.class), Spring Boot's
 * Caffeine auto-configuration is suppressed and this bean is the sole CacheManager.
 *
 * "transaction-categories" gets a dedicated 24-hour TTL so that identical
 * (merchant, description) pairs are not re-sent to the Anthropic API on every request.
 * All other caches use the default spec from application.yml (spring.cache.caffeine.spec).
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Value("${spring.cache.caffeine.spec:maximumSize=5000,expireAfterWrite=10s}")
    private String defaultCaffeineSpec;

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCacheSpecification(defaultCaffeineSpec);
        manager.registerCustomCache(
                "transaction-categories",
                Caffeine.newBuilder()
                        .maximumSize(10_000)
                        .expireAfterWrite(24, TimeUnit.HOURS)
                        .<Object, Object>build()
        );
        return manager;
    }
}
