package cn.nextdev.lightboot.logging.request;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IpCidrMatcher 独立用例：覆盖 IPv4/IPv6 前缀匹配、单地址匹配、边界（/24、/32、/0）
 * 以及非法输入的处理（约定为抛出 {@link IllegalArgumentException}）。
 *
 * <p>RequestLogFilterTest 仅间接覆盖了 IPv4 /8 的命中/未命中，这里补齐 CIDR 按位比较
 * 的各类边界，防止回归（CIDR 位匹配极易出错）。
 */
class IpCidrMatcherTest {

    // ==================== IPv4 前缀匹配 ====================

    /**
     * /8：网段内命中。
     */
    @Test
    void matches_ipv4PrefixHit() {
        assertThat(IpCidrMatcher.matches("10.0.0.0/8", "10.0.0.1")).isTrue();
        assertThat(IpCidrMatcher.matches("10.0.0.0/8", "10.255.255.255")).isTrue();
    }

    /**
     * /8：网段外未命中。
     */
    @Test
    void matches_ipv4PrefixMiss() {
        assertThat(IpCidrMatcher.matches("10.0.0.0/8", "8.8.8.8")).isFalse();
        assertThat(IpCidrMatcher.matches("10.0.0.0/8", "11.0.0.1")).isFalse();
    }

    // ==================== IPv4 单地址匹配（无 /） ====================

    /**
     * 无前缀 → 视为 /32：仅精确匹配。
     */
    @Test
    void matches_ipv4ExactMatch() {
        assertThat(IpCidrMatcher.matches("1.2.3.4", "1.2.3.4")).isTrue();
        assertThat(IpCidrMatcher.matches("1.2.3.4", "1.2.3.5")).isFalse();
    }

    // ==================== IPv4 边界 /24 ====================

    /**
     * /24：网段首地址与末地址均命中。
     */
    @Test
    void matches_ipv4Slash24_includesBlockBounds() {
        assertThat(IpCidrMatcher.matches("192.168.1.0/24", "192.168.1.0")).isTrue();
        assertThat(IpCidrMatcher.matches("192.168.1.0/24", "192.168.1.255")).isTrue();
        assertThat(IpCidrMatcher.matches("192.168.1.0/24", "192.168.1.128")).isTrue();
    }

    /**
     * /24：相邻下一网段不命中。
     */
    @Test
    void matches_ipv4Slash24_excludesAdjacentNetwork() {
        assertThat(IpCidrMatcher.matches("192.168.1.0/24", "192.168.2.0")).isFalse();
        assertThat(IpCidrMatcher.matches("192.168.1.0/24", "192.168.2.1")).isFalse();
        assertThat(IpCidrMatcher.matches("192.168.1.0/24", "192.168.0.255")).isFalse();
    }

    /**
     * /24：按位掩码在边界字节处正确（前 24 位决定网段，最后 8 位自由）。
     * 10.0.0.x/24 的边界字节用掩码过滤后应与网络地址一致。
     */
    @Test
    void matches_ipv4Slash24_bitmaskAtBoundary() {
        assertThat(IpCidrMatcher.matches("10.0.0.0/24", "10.0.0.1")).isTrue();
        assertThat(IpCidrMatcher.matches("10.0.0.0/24", "10.0.1.0")).isFalse();
    }

    // ==================== IPv4 /0 与 /32 ====================

    /**
     * /0：匹配任意 IPv4（前 0 位参与比较）。
     */
    @Test
    void matches_ipv4Slash0_matchesAll() {
        assertThat(IpCidrMatcher.matches("0.0.0.0/0", "8.8.8.8")).isTrue();
        assertThat(IpCidrMatcher.matches("0.0.0.0/0", "1.2.3.4")).isTrue();
        assertThat(IpCidrMatcher.matches("0.0.0.0/0", "255.255.255.255")).isTrue();
    }

    /**
     * /32：仅精确匹配，相邻地址不命中。
     */
    @Test
    void matches_ipv4Slash32_onlyExact() {
        assertThat(IpCidrMatcher.matches("10.0.0.5/32", "10.0.0.5")).isTrue();
        assertThat(IpCidrMatcher.matches("10.0.0.5/32", "10.0.0.6")).isFalse();
    }

    // ==================== IPv6 ====================

    /**
     * IPv6 /32：网段内命中。
     */
    @Test
    void matches_ipv6PrefixHit() {
        assertThat(IpCidrMatcher.matches("2001:db8::/32", "2001:db8::1")).isTrue();
        assertThat(IpCidrMatcher.matches("2001:db8::/32", "2001:db8:abcd:1234::ff")).isTrue();
    }

    /**
     * IPv6 /32：网段外未命中。
     */
    @Test
    void matches_ipv6PrefixMiss() {
        assertThat(IpCidrMatcher.matches("2001:db8::/32", "2001:db9::1")).isFalse();
    }

    /**
     * IPv6 单地址（无 /）→ 视为 /128。
     */
    @Test
    void matches_ipv6ExactMatch() {
        assertThat(IpCidrMatcher.matches("::1", "::1")).isTrue();
        assertThat(IpCidrMatcher.matches("::1", "::2")).isFalse();
    }

    // ==================== 地址族不一致 ====================

    /**
     * IPv4 CIDR 与 IPv6 地址（或反之）地址族不一致时抛 IllegalArgumentException。
     */
    @Test
    void matches_addressFamilyMismatch_throws() {
        assertThatThrownBy(() -> IpCidrMatcher.matches("10.0.0.0/8", "::1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IpCidrMatcher.matches("2001:db8::/32", "10.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== 非法输入 ====================

    /**
     * null cidr 或 null ip 抛 IllegalArgumentException。
     */
    @Test
    void matches_nullArgs_throw() {
        assertThatThrownBy(() -> IpCidrMatcher.matches(null, "10.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IpCidrMatcher.matches("10.0.0.0/8", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 非法 IP 字符串抛 IllegalArgumentException。
     */
    @Test
    void matches_invalidIp_throws() {
        assertThatThrownBy(() -> IpCidrMatcher.matches("not-an-ip", "10.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IpCidrMatcher.matches("10.0.0.0/8", "not-an-ip"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 前缀长度超过最大值（IPv4 > 32）抛 IllegalArgumentException。
     */
    @Test
    void matches_prefixTooLarge_throws() {
        assertThatThrownBy(() -> IpCidrMatcher.matches("10.0.0.0/99", "10.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IpCidrMatcher.matches("10.0.0.0/33", "10.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IpCidrMatcher.matches("2001:db8::/129", "2001:db8::1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 前缀长度无法解析为整数时抛 IllegalArgumentException。
     */
    @Test
    void matches_prefixNotNumeric_throws() {
        assertThatThrownBy(() -> IpCidrMatcher.matches("10.0.0.0/abc", "10.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 负前缀长度（如 "/-1"）必须显式拒绝，而不是被静默当作单地址（/32）处理。
     * 修复前实现将 {@code -1} 复用为"无前缀"哨兵，导致非法负前缀被错误接受为 /32。
     */
    @Test
    void matches_negativePrefix_throws() {
        assertThatThrownBy(() -> IpCidrMatcher.matches("10.0.0.0/-1", "10.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IpCidrMatcher.matches("2001:db8::/-5", "2001:db8::1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 主机名形态的输入（如 {@code "proxy.local"}）作为 cidr 或 ip 时应被拒绝，
     * 且<b>绝不触发 DNS 查询</b>（matcher 运行在请求日志热路径，不应有网络副作用）。
     */
    @Test
    void matches_hostnameForm_throwsWithoutDns() {
        assertThatThrownBy(() -> IpCidrMatcher.matches("proxy.local", "10.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IpCidrMatcher.matches("10.0.0.0/8", "proxy.local"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
