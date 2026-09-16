package cn.nextdev.lightboot.logging.mask;

/**
 * 日志脱敏模块的共享常量。
 *
 * <p>集中存放被多个组件共同引用的脱敏相关常量，避免各处复制导致漂移。
 */
final class MaskConstants {

    private MaskConstants() {
    }

    /**
     * 凭据键的交替匹配片段（不带 {@code (?i)}，由各使用方按需包裹大小写忽略）。
     *
     * <p><b>单一事实源：</b>此常量同时被 {@link MaskPattern#CREDENTIAL} 脱敏规则与
     * {@link DefaultLogMasker} 的 {@code QUICK_CHECK} 预判引用。新增凭据键时只需改这一处，
     * 避免两边漂移导致「值不含数字/@ 的凭据（如 {@code password=secret}）因预判跳过而泄漏」。
     */
    static final String CREDENTIAL_KEYS = "password|passwd|secret|token|authorization|api[_-]?key";

}
