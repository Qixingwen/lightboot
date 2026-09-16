package cn.nextdev.lightboot.logging.mask;

/**
 * 日志脱敏接口。
 *
 * <p>对日志文本中的敏感信息进行脱敏处理。
 * 默认实现 {@link DefaultLogMasker} 基于正则匹配。
 * 业务方可实现此接口提供自定义脱敏逻辑（如基于 JSON 字段名匹配）。
 */
public interface LogMasker {

    /**
     * 对输入文本进行脱敏。
     *
     * @param text 原始文本，可能为 null
     * @return 脱敏后的文本，输入为 null 时返回 null
     */
    String mask(String text);
}
