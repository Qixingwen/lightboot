package cn.nextdev.lightboot.redis.lock.redisson.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RedissonAutoConfiguration} 地址构建逻辑单测。
 *
 * <p>只覆盖 {@code toRedissonAddress} 的 scheme 选择（含 TLS），不触发真实 {@code Redisson.create}。
 * 修复背景：原先单机模式地址恒为 {@code redis://}，从不读取 {@code spring.data.redis.ssl}，
 * 设 {@code ssl=true} 时仍用明文连接；集群/哨兵虽保留 {@code rediss://} 但与单机行为不一致。
 */
class RedissonAutoConfigurationTest {

    @Test
    void singleAddress_isRedisScheme_whenSslFalse() {
        assertThat(RedissonAutoConfiguration.toRedissonAddress("10.0.0.1", 6379, false))
                .isEqualTo("redis://10.0.0.1:6379");
    }

    @Test
    void singleAddress_isRedissScheme_whenSslTrue() {
        assertThat(RedissonAutoConfiguration.toRedissonAddress("redis.example.com", 6380, true))
                .isEqualTo("rediss://redis.example.com:6380");
    }

    @Test
    void nodeAddress_keepsExplicitScheme() {
        // 节点已显式带 scheme 时原样保留（显式优先于 ssl 开关）
        assertThat(RedissonAutoConfiguration.toRedissonAddress("rediss://host1:6379", true))
                .isEqualTo("rediss://host1:6379");
        assertThat(RedissonAutoConfiguration.toRedissonAddress("redis://host2:6379", true))
                .isEqualTo("redis://host2:6379");
    }

    @Test
    void nodeAddress_appliesSslWhenNoExplicitScheme() {
        assertThat(RedissonAutoConfiguration.toRedissonAddress("host1:6379", true))
                .isEqualTo("rediss://host1:6379");
        assertThat(RedissonAutoConfiguration.toRedissonAddress("host1:6379", false))
                .isEqualTo("redis://host1:6379");
    }

    @Test
    void nodeAddress_trimsWhitespace() {
        assertThat(RedissonAutoConfiguration.toRedissonAddress("  host1:6379  ", false))
                .isEqualTo("redis://host1:6379");
    }
}
