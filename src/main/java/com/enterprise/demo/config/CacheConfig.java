package com.enterprise.demo.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Enables Spring's cache AOP proxy and provides a NoOpCacheManager fallback so that
 * @WebMvcTest slices (which do not load CacheAutoConfiguration) can start successfully.
 * In a full application context Caffeine's CaffeineCacheManager takes precedence via
 * @ConditionalOnMissingBean, making the fallback a no-op.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    @ConditionalOnMissingBean
    public CacheManager fallbackCacheManager() {
        return new NoOpCacheManager();
    }
}
