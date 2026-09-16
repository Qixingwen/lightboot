package cn.nextdev.lightboot.logging.request;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 轻量级 CIDR 匹配器（IPv4 / IPv6），用于判断请求来源 IP 是否命中受信代理网段。
 *
 * <p><b>为什么不复用 Spring Security 的 {@code org.springframework.security.web.util.matcher.IpAddressMatcher}：</b>
 * 该类位于 spring-security-web，并非 logging 模块现有依赖（spring-core / spring-web 均不含它）。
 * 为避免仅为单个匹配器引入整个 spring-security 依赖，本模块自行实现了这一轻量工具。
 *
 * <p>支持的 CIDR 形式：
 * <ul>
 *   <li>IPv4 点分十进制 + 前缀：{@code 10.0.0.0/8}、{@code 192.168.1.0/24}</li>
 *   <li>IPv4 单地址（无前缀）：{@code 10.0.0.1}（视为 /32）</li>
 *   <li>IPv6 + 前缀：{@code 2001:db8::/32}</li>
 *   <li>IPv6 单地址（无前缀）：{@code ::1}（视为 /128）</li>
 * </ul>
 *
 * <p>匹配基于 {@link InetAddress} 的原始字节做按位前缀比较，避免字符串归一化差异。
 * 非法 CIDR 抛出 {@link IllegalArgumentException}，由调用方跳过。
 */
final class IpCidrMatcher {

    private IpCidrMatcher() {
    }

    /**
     * 判断 {@code ip} 是否落在 {@code cidr} 网段内。
     *
     * @param cidr CIDR 表达式（如 {@code 10.0.0.0/8}）或单地址（如 {@code 10.0.0.1}）
     * @param ip   待判定的 IP 地址
     * @return 命中返回 true
     * @throws IllegalArgumentException 当 cidr/ip 非法，或两者地址族不一致
     */
    static boolean matches(String cidr, String ip) {
        if (cidr == null || ip == null) {
            throw new IllegalArgumentException("cidr and ip must not be null");
        }
        String[] parts = cidr.split("/", 2);
        String networkPart = parts[0].trim();
        boolean hasPrefix = parts.length == 2;
        int prefixLength;
        if (hasPrefix) {
            try {
                prefixLength = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid prefix length: " + cidr);
            }
        } else {
            // 占位，稍后按地址族设为 maxPrefix（无前缀 = 单地址匹配）
            prefixLength = -1;
        }

        byte[] networkBytes = toAddress(networkPart);
        byte[] ipBytes = toAddress(ip.trim());

        boolean isV6 = networkBytes.length == 16;
        int maxPrefix = isV6 ? 128 : 32;
        if (!hasPrefix) {
            // 无前缀 → 单地址匹配（IPv4=/32, IPv6=/128）
            prefixLength = maxPrefix;
        } else if (prefixLength < 0 || prefixLength > maxPrefix) {
            // 显式拒绝越界前缀（含负值，如 "/-1"）；不与"无前缀"哨兵混淆
            throw new IllegalArgumentException("prefix length out of range: " + cidr);
        }
        if (networkBytes.length != ipBytes.length) {
            throw new IllegalArgumentException("address family mismatch");
        }
        return prefixMatches(networkBytes, ipBytes, prefixLength);
    }

    private static byte[] toAddress(String host) {
        try {
            InetAddress addr = parseLiteral(host);
            // 避免 IPv4 映射 IPv6 地址造成字节长度不一致，统一取实际地址字节
            return addr.getAddress();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid ip: " + host, e);
        }
    }

    /**
     * 解析 IP 字面量（IPv4 点分十进制 / IPv6 文本），<b>不触发 DNS 查询</b>。
     *
     * <p>原先用 {@link InetAddress#getByName(String)}：该方法对<b>主机名</b>会发起网络 DNS 解析，
     * 而本匹配器运行在请求日志的热路径上，不应有任何网络副作用；且语义上 matcher 只应接受 IP 字面量。
     * 故改为：先做格式预判（IPv4 仅数字与点、IPv6 含冒号），再调用 {@code getByName}——
     * 对纯数字点分串与 IPv6 文本，{@code getByName} 仅做字面量解析、不发 DNS；对主机名形态直接拒绝。
     *
     * @throws IllegalArgumentException 当 {@code host} 不是合法 IP 字面量（含主机名形态）
     */
    private static InetAddress parseLiteral(String host) {
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("empty ip literal");
        }
        boolean looksIpv6 = host.indexOf(':') >= 0;
        boolean looksIpv4 = !looksIpv6 && host.chars().allMatch(c -> (c >= '0' && c <= '9') || c == '.');
        if (!looksIpv4 && !looksIpv6) {
            // 非字面量形态（如主机名 "proxy.local"）：拒绝，绝不触发 DNS
            throw new IllegalArgumentException("not an ip literal: " + host);
        }
        try {
            // getByName 对纯数字点分串 / IPv6 文本只做字面量解析，不发起 DNS
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("invalid ip literal: " + host, e);
        }
    }

    /**
     * 按前缀位数比较两个地址字节。前缀内的位必须完全一致。
     */
    private static boolean prefixMatches(byte[] network, byte[] ip, int prefixBits) {
        int fullBytes = prefixBits / 8;
        int leftoverBits = prefixBits % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (network[i] != ip[i]) {
                return false;
            }
        }
        if (leftoverBits > 0 && fullBytes < network.length) {
            int mask = 0xFF << (8 - leftoverBits);
            return (network[fullBytes] & mask) == (ip[fullBytes] & mask);
        }
        return true;
    }
}
