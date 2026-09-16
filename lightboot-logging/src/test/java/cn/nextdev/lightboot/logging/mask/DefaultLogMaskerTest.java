package cn.nextdev.lightboot.logging.mask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DefaultLogMasker 单测：默认规则组合、空值、超长跳过、快速预判。
 */
class DefaultLogMaskerTest {

    private final DefaultLogMasker masker = new DefaultLogMasker();

    /**
     * null 入参直接返回 null。
     */
    @Test
    void mask_returnsNullForNullInput() {
        assertThat(masker.mask(null)).isNull();
    }

    /**
     * 纯文本（无数字、无邮箱）走快速预判，原样返回不进正则。
     */
    @Test
    void mask_returnsPlainTextUnchanged() {
        // 快速预判：不含数字和 @，直接返回原文，不进正则
        assertThat(masker.mask("操作成功")).isEqualTo("操作成功");
    }

    /**
     * 手机号与邮箱同时出现在文本中时一并脱敏。
     */
    @Test
    void mask_masksPhoneAndEmailSimultaneously() {
        String input = "联系 13812348888 或 test@example.com";
        String masked = masker.mask(input);
        assertThat(masked).contains("138****8888").contains("t***@example.com");
    }

    /**
     * 超过 8192 字符的文本仍应用线性（bounded）规则——大 payload 最可能含批量敏感信息，
     * 整段跳过会导致 PII 明文泄漏。手机号规则是线性的，故超长文本中的手机号仍被脱敏。
     *
     * <p>用分隔符（非数字边界）包裹手机号重复构造超长文本：纯数字相连会导致手机号规则的前瞻/后顾边界
     * 不满足而不匹配，故此处用 {@code "tel:13812348888;"} 重复，确保每个手机号两侧均为非数字。
     */
    @Test
    void mask_appliesBoundedPatternsToOverlongText() {
        String input = "tel:13812348888;".repeat(1000); // 远超 8192
        String masked = masker.mask(input);
        assertThat(masked).isNotEqualTo(input);
        assertThat(masked).contains("138****8888");
        assertThat(masked).doesNotContain("13812348888");
    }

    /**
     * 超长文本中的 JSON 凭据同样被脱敏（CREDENTIAL_JSON 是线性规则）。
     */
    @Test
    void mask_masksOverlongJsonCredential() {
        String input = "{\"password\":\"Hunter123!\"}".repeat(1000); // 远超 8192
        String masked = masker.mask(input);
        assertThat(masked).contains("\"password\":\"******\"");
        assertThat(masked).doesNotContain("\"Hunter123!\"");
    }

    /**
     * JSON 引号形态的凭据（旧 CREDENTIAL 规则因 key 与冒号间有引号而漏匹配）现被 CREDENTIAL_JSON 覆盖。
     */
    @Test
    void mask_masksJsonCredential() {
        assertThat(masker.mask("{\"password\":\"Hunter123!\"}"))
                .isEqualTo("{\"password\":\"******\"}");
    }

    /**
     * JSON 凭据带空白的形态（美化 JSON 日志常见）也被覆盖。
     */
    @Test
    void mask_masksJsonCredentialWithSpaces() {
        assertThat(masker.mask("{ \"token\" : \"abc.def.ghi\" }"))
                .isEqualTo("{ \"token\" : \"******\" }");
    }

    /**
     * {@code Authorization: Bearer <jwt>} 的 JWT 本体被脱敏（旧规则只脱到 Bearer）。
     * 整体替换为 ******，且与后续 CREDENTIAL 规则幂等（不产生重复脱敏噪声）。
     */
    @Test
    void mask_masksBearerJwt() {
        assertThat(masker.mask("Authorization: Bearer eyJhbGci.eyJzdWI.sflK"))
                .isEqualTo("Authorization: ******");
    }

    /**
     * 仅指定 PHONE 规则子集时，只脱敏手机号、邮箱保持原样。
     */
    @Test
    void mask_appliesOnlySpecifiedRuleSubset() {
        DefaultLogMasker onlyPhone = new DefaultLogMasker(java.util.List.of(MaskPattern.PHONE));
        String input = "13812348888 test@example.com";
        String masked = onlyPhone.mask(input);
        assertThat(masked).contains("138****8888");
        // 未启用 EMAIL 规则，邮箱保持原样
        assertThat(masked).contains("test@example.com");
    }

    /**
     * 纯数字尾身份证整链路脱敏（消息含数字，本来就会通过 QUICK_CHECK 预判进入正则）。
     */
    @Test
    void mask_masksIdCardWithPureDigitSuffix() {
        assertThat(masker.mask("身份证 110101199003071234"))
                .isEqualTo("身份证 110***********1234");
    }

    /**
     * 尾缀大写 X 的身份证整链路脱敏。
     *
     * <p>同时证明 {@code QUICK_CHECK} 预判不会把含 X 尾身份证的消息挡在脱敏之外：
     * 预判按「包含数字或 @」门控，X 尾号码前 17 位仍是数字，消息必然进入正则脱敏，
     * 因此预判无需随 X 尾支持做任何修正。
     */
    @Test
    void mask_masksIdCardWithUppercaseXSuffix() {
        assertThat(masker.mask("身份证 11010119900307123X"))
                .isEqualTo("身份证 110***********123X");
    }

    /**
     * 尾缀小写 x 的身份证整链路脱敏。
     */
    @Test
    void mask_masksIdCardWithLowercaseXSuffix() {
        assertThat(masker.mask("身份证 11010119900307123x"))
                .isEqualTo("身份证 110***********123x");
    }

    /**
     * 凭据脱敏：password 值（含数字）整体替换为 ******。
     *
     * <p>值 {@code Hunter123!} 含数字，能通过默认 {@code QUICK_CHECK}（{@code [\d@]}）预判。
     */
    @Test
    void mask_masksPasswordCredential() {
        DefaultLogMasker m = new DefaultLogMasker();
        assertThat(m.mask("password=Hunter123!"))
                .isEqualTo("password=******");
    }

    /**
     * 凭据脱敏：值不含数字、不含 @（如 {@code password=secret}）时也必须脱敏。
     *
     * <p>这是 {@link DefaultLogMasker} {@code QUICK_CHECK} 预判的关键用例：若预判仅检查
     * {@code [\d@]}，则 {@code password=secret} 会被整体跳过，凭据泄漏。预判必须额外
     * 放行包含凭据键的消息。
     */
    @Test
    void mask_masksCredentialWithoutDigit() {
        DefaultLogMasker m = new DefaultLogMasker();
        assertThat(m.mask("password=secret"))
                .isEqualTo("password=******");
    }

    /**
     * 凭据脱敏：colon 形式 {@code Authorization: BearerXYZ} 同样脱敏。
     *
     * <p>注意 CREDENTIAL 规则值匹配为单个非空白 token（{@code \S+}），
     * 这是 HTTP Authorization 头等凭据的典型格式（无内嵌空白）。
     */
    @Test
    void mask_masksColonFormCredential() {
        DefaultLogMasker m = new DefaultLogMasker();
        assertThat(m.mask("Authorization: BearerXYZ"))
                .isEqualTo("Authorization: ******");
    }

    /**
     * 回归保护：共享常量 {@link MaskConstants#CREDENTIAL_KEYS} 中的每一个键，
     * 用一个不含数字、不含 @ 的值构造键值对时，必须被脱敏（值整体替换为 ******）。
     *
     * <p>这是针对「CREDENTIAL 脱敏规则与 QUICK_CHECK 预判共享同一凭据键列表」的防漂移用例。
     * 若未来有人把某键加进一处而漏了另一处，含该键的纯凭据消息（无数字/@）会被 QUICK_CHECK 跳过，
     * 导致凭据泄漏——本用例会失败。
     */
    @ParameterizedTest
    @MethodSource("credentialKeys")
    void mask_masksEverySharedCredentialKeyWithoutDigit(String key) {
        DefaultLogMasker m = new DefaultLogMasker();
        // 值 "secret" 不含数字也不含 @，唯一能触发脱敏的就是凭据键本身
        assertThat(m.mask(key + "=secret"))
                .as("凭据键 %s 的纯文本值必须被脱敏（QUICK_CHECK 与 CREDENTIAL 共享同一键列表）", key)
                .isEqualTo(key + "=******");
    }

    /**
     * 共享常量中每个键的「具体可匹配字面量」：用于回归用例。
     *
     * <p>常量里的 {@code api[_-]?key} 是正则子模式（匹配 apikey/api-key/api_key），
     * 不能直接当字面量拼到消息里验证，这里取其一个具体形式 {@code apikey} 代表该子模式。
     * 其余键本身就是字面量。这样用例覆盖常量中的每一个分支，保证 QUICK_CHECK 与
     * CREDENTIAL 派生自同一来源——任一处漏掉某键，对应的纯凭据消息（无数字/@）会被跳过导致泄漏，本用例失败。
     */
    static Stream<String> credentialKeys() {
        return Pattern.compile("\\|").splitAsStream(MaskConstants.CREDENTIAL_KEYS)
                // 把正则子模式 api[_-]?key 归一为具体字面量 apikey
                .map(key -> "api[_-]?key".equals(key) ? "apikey" : key);
    }
}
