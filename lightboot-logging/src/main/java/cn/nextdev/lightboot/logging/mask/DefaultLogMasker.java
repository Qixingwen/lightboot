package cn.nextdev.lightboot.logging.mask;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 默认日志脱敏实现，基于正则规则逐条匹配替换。
 *
 * <p>默认启用所有内置规则。可通过构造方法指定子集。
 * 线程安全：{@link MaskPattern} 的 Pattern 对象本身线程安全。
 *
 * <p>性能保护：对超过 {@code MAX_MASK_LENGTH}（8192 字符）的文本，<b>只跳过无界（{@code bounded=false}）规则</b>，
 * 仍应用线性规则——这样大日志消息（如包含完整 JSON 响应、最可能含批量敏感信息）仍能被便宜地脱敏，
 * 而非像旧实现那样整段返回原文导致 PII 明文泄漏；同时保留对真正可能引发灾难性回溯（ReDoS）的无界规则的防护。
 * 当前所有内置规则均 {@code bounded=true}，故超长文本实际上仍会被完整脱敏。
 */
public class DefaultLogMasker implements LogMasker {

    /**
     * 超过此长度时，仅跳过无界（{@code bounded=false}）规则以防 ReDoS；线性规则仍应用。
     */
    private static final int MAX_MASK_LENGTH = 8192;

    /**
     * 快速预判：文本中不包含数字和 @ 符号时，通常无需脱敏。
     *
     * <p>但凭据键（password/passwd/secret/token/authorization/apiKey）的值可能不含数字和 @
     * （如 {@code password=secret}），凭据脱敏属安全特性，绝不可因预判被跳过。
     * 故预判同时放行包含凭据键的消息。
     */
    private static final Pattern QUICK_CHECK = Pattern.compile("[\\d@]|(?i)(" + MaskConstants.CREDENTIAL_KEYS + ")");

    private final List<MaskPattern> patterns;

    /**
     * 启用所有内置脱敏规则。
     */
    public DefaultLogMasker() {
        this(Arrays.asList(MaskPattern.values()));
    }

    /**
     * 启用指定的脱敏规则。
     *
     * @param patterns 脱敏规则列表
     */
    public DefaultLogMasker(List<MaskPattern> patterns) {
        this.patterns = List.copyOf(patterns);
    }

    /**
     * 对文本逐条应用脱敏规则；输入为 {@code null} 时返回 {@code null}，
     * 超长文本仅应用线性（bounded）规则以防 ReDoS。
     */
    @Override
    public String mask(String text) {
        if (text == null) {
            return null;
        }
        boolean overlong = text.length() > MAX_MASK_LENGTH;
        // 快速预判：只有不含数字、@ 且不含任何凭据键的文本才不可能匹配规则
        // （凭据键的值可能不含数字和 @，见类注释——预判必须额外放行包含凭据键的文本）
        if (!overlong && !QUICK_CHECK.matcher(text).find()) {
            return text;
        }
        String result = text;
        for (MaskPattern mp : patterns) {
            // 超长文本上仅应用线性（bounded）规则，跳过无界规则以防 ReDoS
            if (overlong && !mp.isBounded()) {
                continue;
            }
            result = mp.getPattern().matcher(result).replaceAll(mp.getReplacement());
        }
        return result;
    }
}
