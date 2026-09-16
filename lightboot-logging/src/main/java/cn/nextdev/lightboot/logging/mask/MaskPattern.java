package cn.nextdev.lightboot.logging.mask;

import lombok.Getter;

import java.util.regex.Pattern;

/**
 * 内置脱敏规则。
 *
 * <p>每条规则包含一个正则 Pattern、对应的替换模板，以及一个 {@code bounded} 标志。
 * 规则按枚举声明顺序依次应用，顺序可能影响结果——当前真实依赖顺序的两组：
 * 身份证（{@link #ID_CARD}）必须先于银行卡（{@link #BANK_CARD}），否则卡号规则会先破坏身份证的日期位；
 * {@link #CREDENTIAL_BEARER} / {@link #CREDENTIAL_JSON} 必须先于 {@link #CREDENTIAL}（避免二次匹配，见各自注释）。
 *
 * <p>{@code bounded} 表示该规则的正则在长输入上是否为线性（不会发生灾难性回溯）。
 * {@link DefaultLogMasker} 对超长文本（超过 {@code MAX_MASK_LENGTH}）只应用 {@code bounded=true} 的规则，
 * 跳过无界规则以防 ReDoS——这样大 payload（最可能含批量敏感信息）仍能被便宜地脱敏，
 * 而非像旧实现那样整段跳过。当前所有内置规则均声明为 {@code bounded=true}
 * （部分规则使用 {@code \S+} 等无上界量词，但匹配过程仍为线性，无灾难性回溯）。
 *
 * <p>NAME 规则已移除 — 全文正则匹配中文姓名的误报率过高（日志中的普通中文短句会被误脱敏）。
 * 如需姓名脱敏，建议基于 JSON 字段名进行结构化脱敏，而非全文正则匹配。
 */
@Getter
public enum MaskPattern {

    /**
     * 手机号：138****8888。
     */
    PHONE(Pattern.compile("(?<=\\D|^)(1[3-9]\\d)\\d{4}(\\d{4})(?=\\D|$)"), "$1****$2", true),

    /**
     * 身份证号（18位，兼容尾缀 X/x）：110***********123X。
     */
    ID_CARD(Pattern.compile("(?<=\\D|^)([1-9]\\d{2})\\d{11}(\\d{3}[0-9Xx])(?=\\D|$)"), "$1***********$2", true),

    /**
     * 银行卡号：6222********1234（校验常见 BIN 前缀 4/5/6 开头）。
     */
    BANK_CARD(Pattern.compile("(?<=\\D|^)([4-6]\\d{3})\\d{8,12}(\\d{4})(?=\\D|$)"), "$1********$2", true),

    /**
     * 邮箱：t***@example.com。
     *
     * <p>本地部分使用有界量词 {@code \w{0,63}}（单标签 63 字符与 RFC 1035 一致，整体不超过
     * RFC 5321 规定的 local-part 64 八位组上限），避免无界 {@code \w*}
     * 在超长输入上引发灾难性回溯（ReDoS）。域名部分同样有界，并要求至少两级域。
     */
    EMAIL(Pattern.compile("(?<=\\D|^)(\\w)\\w{0,63}(@[\\w-]{1,63}(?:\\.[\\w-]{1,63})+)"), "$1***$2", true),

    /**
     * Bearer / Basic / JWT 令牌：{@code Authorization: Bearer <jwt>} → {@code Authorization: ******}。
     *
     * <p>专门处理 {@code scheme + 空格 + token} 形态。普通的 {@link #CREDENTIAL} 规则用 {@code \S+} 取值，
     * 遇到 {@code Authorization: Bearer eyJ...} 时只会脱敏到 {@code Bearer}（值遇空格截断），JWT 泄漏。
     * 本规则在 {@code CREDENTIAL} <b>之前</b>应用，把 key + 分隔符之后的整个值（含 scheme 词与 token）
     * 一次性替换为 {@code ******}。
     *
     * <p><b>为何不保留 {@code Bearer} scheme 词</b>：若输出 {@code Authorization: Bearer ******}，
     * 后续的 {@link #CREDENTIAL} 仍会按 {@code Authorization: Bearer} 二次匹配（key + 冒号 + 首个非空白 token），
     * 产生 {@code Authorization: ****** ******} 的重复脱敏噪声。整体替换为单个 {@code ******} 既是
     * {@code CREDENTIAL} 的幂等输入（再脱敏仍为 {@code ******}），也与 {@code Authorization: BearerXYZ} 的既有结果一致。
     *
     * <p>不匹配 {@code BearerXYZ}（scheme 后无空格）——该形态落到 {@link #CREDENTIAL} 处理为
     * {@code Authorization: ******}，结果与本规则一致。
     */
    CREDENTIAL_BEARER(Pattern.compile("(?i)((?:" + MaskConstants.CREDENTIAL_KEYS + ")(\\s*[:=]\\s*))(?:bearer|basic|jwt)\\s+\\S+"), "$1******", true),

    /**
     * JSON 引号形态的凭据字段：{@code {"password":"x"}} → {@code {"password":"******"}}。
     *
     * <p>普通的 {@link #CREDENTIAL} 规则要求 key 与 {@code :}/{@code =} 紧邻（仅允许空白），
     * 而 JSON 形态 key 与 {@code :} 之间有引号 {@code "}，导致漏匹配。本规则匹配
     * {@code "<key>":"<value>"}（含可选空白），替换值为字面量 {@code "******"}（保留引号，
     * 使日志中的 JSON 仍语法合法）。在 {@code CREDENTIAL} <b>之前</b>应用。
     */
    CREDENTIAL_JSON(
            Pattern.compile("(?i)(\"(?:" + MaskConstants.CREDENTIAL_KEYS + ")\")(\\s*:\\s*)(\"[^\"]*?\")"),
            "$1$2\"******\"", true),

    /**
     * 凭据字段：password / passwd / secret / token / authorization / api key 的键值对脱敏
     * （{@code api} 键含 {@code apikey}、{@code api_key}、{@code api-key} 分隔符变体，大小写不敏感）。
     *
     * <p>匹配 {@code key=value} 或 {@code key: value} 形式（大小写不敏感），值整体替换为 ******。
     * 值部分为贪婪的 {@code \S+}：一直匹配到下一个空白字符为止，引号、逗号、分号等分隔符会
     * 一并计入值被打码（宁可多脱敏、不漏脱敏）。
     *
     * <p>注意：值中可能不含数字和 @（如 {@code password=secret}），因此
     * {@link DefaultLogMasker} 的 {@code QUICK_CHECK} 预判必须额外放行包含这些凭据键的消息，
     * 否则预判会跳过此类消息导致凭据泄漏。
     */
    CREDENTIAL(Pattern.compile("(?i)(" + MaskConstants.CREDENTIAL_KEYS + ")(\\s*[:=]\\s*)(\\S+)"), "$1$2******", true);

    /**
     * 用于匹配敏感内容的编译后正则。{@link Pattern} 本身线程安全，可被多线程并发使用。
     */
    private final Pattern pattern;

    /**
     * 替换模板，支持正则捕获组引用（如 {@code $1****$2}）。
     */
    private final String replacement;

    /**
     * 正则在长输入上是否为线性（不会发生灾难性回溯）。
     *
     * <p>为 {@code true} 时，{@link DefaultLogMasker} 即便在超长文本（超过
     * {@code MAX_MASK_LENGTH}）上也会应用本规则；为 {@code false} 时超长文本上跳过本规则以防 ReDoS。
     */
    private final boolean bounded;

    /**
     * 创建一条脱敏规则。
     *
     * @param pattern     用于匹配敏感内容的编译后正则（必须线程安全）
     * @param replacement 替换模板，支持捕获组引用（如 {@code $1****$2}）
     * @param bounded     正则在长输入上是否为线性（{@code true} 表示不会发生灾难性回溯，
     *                    超长文本上仍会被 {@link DefaultLogMasker} 应用）
     */
    MaskPattern(Pattern pattern, String replacement, boolean bounded) {
        this.pattern = pattern;
        this.replacement = replacement;
        this.bounded = bounded;
    }

}
