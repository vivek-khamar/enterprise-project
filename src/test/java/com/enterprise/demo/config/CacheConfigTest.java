package com.enterprise.demo.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Policy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = CacheConfig.class)
class CacheConfigTest {

    @Autowired
    private CacheManager cacheManager;

    @Test
    void cacheManager_isCaffeineCacheManager() {
        assertThat(cacheManager).isInstanceOf(CaffeineCacheManager.class);
    }

    @Test
    void transactionCategoriesCache_exists_withTwentyFourHourTtl() {
        org.springframework.cache.Cache springCache =
                cacheManager.getCache("transaction-categories");
        assertThat(springCache).isNotNull();

        @SuppressWarnings("unchecked")
        Cache<Object, Object> native_ =
                (Cache<Object, Object>) springCache.getNativeCache();

        Optional<Policy.FixedExpiration<Object, Object>> expiry =
                native_.policy().expireAfterWrite();

        assertThat(expiry).isPresent();
        assertThat(expiry.get().getExpiresAfter(TimeUnit.HOURS)).isEqualTo(24L);
    }

    @Test
    void dynamicCaches_useTenSecondTtlFromDefaultSpec() {
        // "users" is not pre-registered — CaffeineCacheManager creates it on-demand using
        // the default spec from application.yml (maximumSize=5000,expireAfterWrite=10s).
        @SuppressWarnings("unchecked")
        Cache<Object, Object> native_ =
                (Cache<Object, Object>) cacheManager.getCache("users").getNativeCache();

        Optional<Policy.FixedExpiration<Object, Object>> expiry =
                native_.policy().expireAfterWrite();

        assertThat(expiry).isPresent();
        assertThat(expiry.get().getExpiresAfter(TimeUnit.SECONDS)).isEqualTo(10L);
    }
}
