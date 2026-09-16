package cn.nextdev.lightboot.logging.mask;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内置脱敏规则 MaskPattern 单测：验证各规则对典型输入的脱敏效果。
 */
class MaskPatternTest {

    /**
     * 手机号脱敏：中间四位替换为星号。
     */
    @Test
    void phone_masksMiddleFourDigits() {
        String input = "用户手机号 13812348888 已注册";
        String masked = MaskPattern.PHONE.getPattern().matcher(input)
                .replaceAll(MaskPattern.PHONE.getReplacement());
        assertThat(masked).isEqualTo("用户手机号 138****8888 已注册");
    }

    /**
     * 身份证脱敏：中间十一位替换为星号。
     */
    @Test
    void idCard_masksMiddleElevenDigits() {
        String input = "身份证 110101199003071234";
        String masked = MaskPattern.ID_CARD.getPattern().matcher(input)
                .replaceAll(MaskPattern.ID_CARD.getReplacement());
        assertThat(masked).isEqualTo("身份证 110***********1234");
    }

    /**
     * 身份证脱敏：尾缀为大写 X 的 18 位号码同样脱敏。
     *
     * <p>旧正则末组为 {@code (\d{4})}，X 尾号码（校验位为 X）漏匹配导致明文落日志。
     */
    @Test
    void idCard_masksMiddleElevenDigitsWithUppercaseXSuffix() {
        String input = "身份证 11010119900307123X";
        String masked = MaskPattern.ID_CARD.getPattern().matcher(input)
                .replaceAll(MaskPattern.ID_CARD.getReplacement());
        assertThat(masked).isEqualTo("身份证 110***********123X");
    }

    /**
     * 身份证脱敏：尾缀为小写 x 的 18 位号码同样脱敏。
     */
    @Test
    void idCard_masksMiddleElevenDigitsWithLowercaseXSuffix() {
        String input = "身份证 11010119900307123x";
        String masked = MaskPattern.ID_CARD.getPattern().matcher(input)
                .replaceAll(MaskPattern.ID_CARD.getReplacement());
        assertThat(masked).isEqualTo("身份证 110***********123x");
    }

    /**
     * 纯数字尾回归：末位为数字的 18 位号码仍正常脱敏（原行为不回归）。
     */
    @Test
    void idCard_stillMasksPureDigitSuffix() {
        String input = "身份证 110101199003071234";
        String masked = MaskPattern.ID_CARD.getPattern().matcher(input)
                .replaceAll(MaskPattern.ID_CARD.getReplacement());
        assertThat(masked).isEqualTo("身份证 110***********1234");
    }

    /**
     * 边界：19 位纯数字不匹配（定长 18 + 前瞻 {@code (?=\D|$)} 拒绝多余数字）。
     */
    @Test
    void idCard_doesNotMatchNineteenDigitString() {
        String input = "号码 1101011990030712345";
        String masked = MaskPattern.ID_CARD.getPattern().matcher(input)
                .replaceAll(MaskPattern.ID_CARD.getReplacement());
        assertThat(masked).isEqualTo(input);
    }

    /**
     * 边界：X 后还有数字（{@code 11010119900307123X1}）不匹配——
     * X 只能位于末位，前瞻 {@code (?=\D|$)} 保证其后不能再有数字。
     */
    @Test
    void idCard_doesNotMatchXFollowedByDigit() {
        String input = "号码 11010119900307123X1";
        String masked = MaskPattern.ID_CARD.getPattern().matcher(input)
                .replaceAll(MaskPattern.ID_CARD.getReplacement());
        assertThat(masked).isEqualTo(input);
    }

    /**
     * 15 位老证号<b>有意不支持</b>（保持原样不脱敏）——规则仅覆盖 18 位（含 X/x 尾）。
     * 如需覆盖 15 位需另行评估误伤率，不在本规则范围内。
     */
    @Test
    void idCard_intentionallyDoesNotMatchLegacyFifteenDigit() {
        String input = "号码 110101900307123";
        String masked = MaskPattern.ID_CARD.getPattern().matcher(input)
                .replaceAll(MaskPattern.ID_CARD.getReplacement());
        assertThat(masked).isEqualTo(input);
    }

    /**
     * 邻接语义：手机号串不会被 ID_CARD 规则误伤（11 位长度不满足 18 位定长形态）。
     */
    @Test
    void idCard_doesNotMaskPhoneNumberString() {
        String input = "手机 13812348888";
        String masked = MaskPattern.ID_CARD.getPattern().matcher(input)
                .replaceAll(MaskPattern.ID_CARD.getReplacement());
        assertThat(masked).isEqualTo(input);
    }

    /**
     * 银行卡脱敏：中间八位替换为星号。
     */
    @Test
    void bankCard_masksMiddleEightDigits() {
        String input = "卡号 6222020200011111";
        String masked = MaskPattern.BANK_CARD.getPattern().matcher(input)
                .replaceAll(MaskPattern.BANK_CARD.getReplacement());
        assertThat(masked).isEqualTo("卡号 6222********1111");
    }

    /**
     * 邮箱脱敏：本地部分仅保留首字符，其余替换为星号。
     */
    @Test
    void email_keepsOnlyFirstCharOfLocalPart() {
        String input = "邮箱 test@example.com";
        String masked = MaskPattern.EMAIL.getPattern().matcher(input)
                .replaceAll(MaskPattern.EMAIL.getReplacement());
        assertThat(masked).isEqualTo("邮箱 t***@example.com");
    }

    /**
     * 非手机号数字串不应被误脱敏（不足 11 位、非 1[3-9] 开头）。
     */
    @Test
    void phone_doesNotMaskNonPhoneDigitString() {
        // 不足 11 位、非 1[3-9] 开头的数字串不应被脱敏
        String input = "订单号 202605260001";
        String masked = MaskPattern.PHONE.getPattern().matcher(input)
                .replaceAll(MaskPattern.PHONE.getReplacement());
        assertThat(masked).isEqualTo(input);
    }

    /**
     * Bearer token：整体替换 key 后的值（含 scheme 词与 token）为 ******，与 CREDENTIAL 幂等。
     */
    @Test
    void credentialBearer_masksJwtAfterBearer() {
        String input = "Authorization: Bearer eyJhbGci.eyJzdWI.sflK";
        String masked = MaskPattern.CREDENTIAL_BEARER.getPattern().matcher(input)
                .replaceAll(MaskPattern.CREDENTIAL_BEARER.getReplacement());
        assertThat(masked).isEqualTo("Authorization: ******");
    }

    /**
     * Bearer 后无空格（如 {@code BearerXYZ}）不匹配本规则（落到 CREDENTIAL 处理）。
     */
    @Test
    void credentialBearer_doesNotMatchBearerWithoutSpace() {
        String input = "Authorization: BearerXYZ";
        String masked = MaskPattern.CREDENTIAL_BEARER.getPattern().matcher(input)
                .replaceAll(MaskPattern.CREDENTIAL_BEARER.getReplacement());
        assertThat(masked).isEqualTo(input);
    }

    /**
     * JSON 引号形态凭据：值替换为字面量 "******"（保留引号使日志中 JSON 仍合法）。
     */
    @Test
    void credentialJson_masksQuotedValue() {
        String input = "{\"password\":\"Hunter123!\"}";
        String masked = MaskPattern.CREDENTIAL_JSON.getPattern().matcher(input)
                .replaceAll(MaskPattern.CREDENTIAL_JSON.getReplacement());
        assertThat(masked).isEqualTo("{\"password\":\"******\"}");
    }

    /**
     * JSON 凭据带空白的美化形态也被覆盖。
     */
    @Test
    void credentialJson_masksQuotedValueWithSpaces() {
        String input = "{ \"token\" : \"abc.def.ghi\" }";
        String masked = MaskPattern.CREDENTIAL_JSON.getPattern().matcher(input)
                .replaceAll(MaskPattern.CREDENTIAL_JSON.getReplacement());
        assertThat(masked).isEqualTo("{ \"token\" : \"******\" }");
    }

    /**
     * JSON 凭据：apiKey 子模式形态。
     */
    @Test
    void credentialJson_masksApiKey() {
        String input = "{\"apiKey\":\"k1\"}";
        String masked = MaskPattern.CREDENTIAL_JSON.getPattern().matcher(input)
                .replaceAll(MaskPattern.CREDENTIAL_JSON.getReplacement());
        assertThat(masked).isEqualTo("{\"apiKey\":\"******\"}");
    }
}
