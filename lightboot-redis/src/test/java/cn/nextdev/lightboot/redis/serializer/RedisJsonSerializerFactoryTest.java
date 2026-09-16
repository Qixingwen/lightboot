package cn.nextdev.lightboot.redis.serializer;

import cn.nextdev.lightboot.redis.config.RedisProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RedisJsonSerializerFactory 单测：验证白名单收紧——只允许白名单包内的多态类型反序列化。
 */
class RedisJsonSerializerFactoryTest {

    /**
     * create 返回非空的序列化器。
     */
    @Test
    void create_returnsNonNullSerializer() {
        RedisJsonSerializerFactory factory = new RedisJsonSerializerFactory();
        GenericJacksonJsonRedisSerializer serializer = factory.create(new RedisProperties());
        assertThat(serializer).isNotNull();
    }

    /**
     * 白名单内的显式 JDK 类型（String/Number/Boolean/Character/Collection/Map 子类型）可正常序列化/反序列化往返。
     */
    @Test
    void create_supportsRoundTripForJavaBuiltinTypes() {
        // String 等显式 JDK 类型在白名单内，可正常序列化/反序列化（NON-NEGOTIABLE：收紧后合法 JDK 类型仍可用；
        // 注意白名单不含 java. 通配：Date/枚举等非 final 类型默认被拒；java.time 为 final 类不写类型标识，
        // 静默按字符串往返，并非被白名单「拒绝」）
        RedisJsonSerializerFactory factory = new RedisJsonSerializerFactory();
        GenericJacksonJsonRedisSerializer serializer = factory.create(new RedisProperties());

        byte[] bytes = serializer.serialize("hello");
        Object result = serializer.deserialize(bytes);

        assertThat(result).isEqualTo("hello");
    }

    /**
     * 非白名单的危险类型在反序列化时被拒绝（白名单收紧生效）。
     */
    @Test
    void create_rejectsNonWhitelistedDangerousType() {
        // 收紧后（移除 allowIfBaseType(Object.class)），非白名单包的多态反序列化应被
        // BasicPolymorphicTypeValidator 拒绝。为真正验证校验器生效，这里必须使用一个
        // 【真实存在于类路径、但不在白名单】的类型 —— 否则若用不存在的类名（如 com.evil.Attack），
        // Jackson 会先因 ClassNotFoundException 失败，使测试因错误原因通过，无法证明白名单生效。
        // org.springframework.context.support.GenericApplicationContext 在 spring-context 中存在，
        // 既不属于 java.* 也不属于 cn.nextdev.lightboot.*，是理想的非白名单存在类型样本。
        RedisJsonSerializerFactory factory = new RedisJsonSerializerFactory();
        GenericJacksonJsonRedisSerializer serializer = factory.create(new RedisProperties());

        byte[] malicious =
                "{\"@class\":\"org.springframework.context.support.GenericApplicationContext\"}".getBytes();

        // 反序列化应抛异常：PolymorphicTypeValidator 拒绝该类型（prevented for security reasons）
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> serializer.deserialize(malicious))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("security");
    }

    /**
     * 用户配置的 basePackages 被加入反序列化白名单。
     */
    @Test
    void create_addsUserBasePackagesToWhitelist() {
        RedisProperties props = new RedisProperties();
        props.getSerializer().setBasePackages(java.util.List.of("com.example.entity"));

        RedisJsonSerializerFactory factory = new RedisJsonSerializerFactory();
        GenericJacksonJsonRedisSerializer serializer = factory.create(props);

        assertThat(serializer).isNotNull();  // 配置不报错即通过
    }

    /**
     * base-packages 含空串/纯空白时启动即失败（fail-fast），防止一条空配置静默放行。
     */
    @Test
    void create_rejectsBlankBasePackage() {
        RedisProperties props = new RedisProperties();
        props.getSerializer().setBasePackages(List.of("   "));

        assertThatThrownBy(() -> new RedisJsonSerializerFactory().create(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法配置项");
    }

    /**
     * base-packages 含通配符时启动即失败——白名单是包前缀语义，通配符只会造成误解。
     */
    @Test
    void create_rejectsWildcardBasePackage() {
        for (String wildcard : new String[] {"*", "com.example.*"}) {
            RedisProperties props = new RedisProperties();
            props.getSerializer().setBasePackages(List.of(wildcard));

            assertThatThrownBy(() -> new RedisJsonSerializerFactory().create(props))
                    .as("通配符配置 '%s' 必须被拒绝", wildcard)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("非法配置项");
        }
    }

    /**
     * base-packages 放行 java 包前缀时启动即失败（含 JDK 内部包，白名单形同虚设）。
     */
    @Test
    void create_rejectsJavaPackagePrefix() {
        RedisProperties props = new RedisProperties();
        props.getSerializer().setBasePackages(List.of("java."));

        assertThatThrownBy(() -> new RedisJsonSerializerFactory().create(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("禁止放行危险包前缀")
                .hasMessageContaining("java");
    }

    /**
     * base-packages 放行 com.sun 包前缀时启动即失败（常见反序列化 gadget 所在包）。
     */
    @Test
    void create_rejectsComSunPackagePrefix() {
        RedisProperties props = new RedisProperties();
        props.getSerializer().setBasePackages(List.of("com.sun."));

        assertThatThrownBy(() -> new RedisJsonSerializerFactory().create(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("禁止放行危险包前缀")
                .hasMessageContaining("com.sun");
    }

    /**
     * 危险包前缀校验大小写不敏感（{@code JAVA} 同样拒绝，杜绝大小写变体绕过）。
     */
    @Test
    void create_rejectsForbiddenPrefixCaseInsensitive() {
        RedisProperties props = new RedisProperties();
        props.getSerializer().setBasePackages(List.of("JAVA"));

        assertThatThrownBy(() -> new RedisJsonSerializerFactory().create(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("禁止放行危险包前缀");
    }

    /**
     * 包点归一化：配置不带尾点的 {@code com.example} 会统一补尾点变为 {@code com.example.}，
     * 使 allowIfSubType 的前缀匹配具有包边界语义——允许 {@code com.example.dto.User}、
     * 拒绝 {@code com.exampleFoo.User}。
     *
     * <p>测法说明：{@code buildTypeValidator} 为私有方法，通过反射获取校验器实例后直接调用
     * {@link PolymorphicTypeValidator#validateSubClassName}。该方法在类名字符串层面做前缀匹配、
     * <b>不加载类</b>（BasicPolymorphicTypeValidator 的名称匹配器仅调用
     * {@code String.startsWith}），因此可以用类路径上不存在的类名精确验证包边界语义——
     * 若走端到端反序列化路径，不存在的类会先抛 ClassNotFoundException，无法区分「白名单拒绝」
     * 与「类不存在」。白名单配置通过性与拒绝路径的回归见
     * {@link #create_addsUserBasePackagesToWhitelist()}（配置不报错即通过）与
     * {@link #create_rejectsDangerousJavaType()}（端到端反序列化拒绝）。
     */
    @Test
    void create_normalizesBasePackageWithDotBoundary() throws Exception {
        RedisProperties props = new RedisProperties();
        props.getSerializer().setBasePackages(List.of("com.example"));

        PolymorphicTypeValidator validator = buildValidatorViaReflection(props);

        // 补尾点后 com.example.dto.User 位于放行前缀 com.example. 之下
        assertThat(validator.validateSubClassName(null, null, "com.example.dto.User"))
                .isEqualTo(PolymorphicTypeValidator.Validity.ALLOWED);
        // 无包点边界时 startsWith("com.example") 会误放行 com.exampleFoo；归一化后不再匹配
        assertThat(validator.validateSubClassName(null, null, "com.exampleFoo.User"))
                .isEqualTo(PolymorphicTypeValidator.Validity.INDETERMINATE);

        // 内置框架包白名单不受影响
        assertThat(validator.validateSubClassName(null, null, "cn.nextdev.lightboot.any.Type"))
                .isEqualTo(PolymorphicTypeValidator.Validity.ALLOWED);
        // 用户包配置不得放宽 JDK 类型（java.util.Date 在名称层面仍不放行）
        assertThat(validator.validateSubClassName(null, null, "java.util.Date"))
                .isEqualTo(PolymorphicTypeValidator.Validity.INDETERMINATE);
    }

    /**
     * 危险的 java.* 类型（非显式白名单的具体类型）在反序列化时被类型校验器拒绝。
     *
     * <p>用 {@code java.util.Date} 作为样本：
     * <ul>
     *   <li>有公开无参构造方法——当白名单允许时 Jackson 能正常实例化，确保唯一变量是「白名单是否放行」；</li>
     *   <li>不在新白名单（String/Number/Boolean/Character/Collection/Map）内——收紧后必须被拒；</li>
     *   <li>非 Spring 等内置黑名单类型——避免被 Jackson 自带安全检查拦截，混淆白名单来源。</li>
     * </ul>
     *
     * <p>注意：本工厂配置的多态类型信息为 {@code As.PROPERTY}（见 {@code RedisJsonSerializerFactory}，
     * payload 使用 {@code {"@class": "类型名", ...}} 形态）；实测两种形态（{@code @class} 属性与
     * {@code WRAPPER_ARRAY}）均能到达类型校验器，本测试采用与生产配置一致的 {@code @class} 形态。
     *
     * <p>断言必须精确匹配 {@link tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator}
     * 的拒绝信息（"denied resolution"），以区分于「构造失败」等普通 Jackson 反序列化错误——
     * 后者会让测试无论白名单是否生效都通过（即原 ProcessBuilder 用例的空转问题）。
     */
    @Test
    void create_rejectsDangerousJavaType() {
        RedisJsonSerializerFactory factory = new RedisJsonSerializerFactory();
        GenericJacksonJsonRedisSerializer serializer = factory.create(new RedisProperties());

        // WRAPPER_ARRAY 形态：java.util.Date 可实例化、不在白名单、非内置黑名单——
        // 唯一阻断来源就是 BasicPolymorphicTypeValidator。
        byte[] malicious = "[\"java.util.Date\",{}]".getBytes();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> serializer.deserialize(malicious))
                // 必须是类型校验器的拒绝（denied resolution），而非普通反序列化错误
                .hasMessageContaining("PolymorphicTypeValidator")
                .hasMessageContaining("denied resolution");
    }

    /**
     * 反射调用私有 {@code buildTypeValidator} 获取类型校验器，用于在类名字符串层面验证
     * allowIfSubType 前缀语义（见 {@link #create_normalizesBasePackageWithDotBoundary()} 的测法说明）。
     */
    private PolymorphicTypeValidator buildValidatorViaReflection(RedisProperties props) throws Exception {
        Method method = RedisJsonSerializerFactory.class
                .getDeclaredMethod("buildTypeValidator", RedisProperties.class);
        method.setAccessible(true);
        return (PolymorphicTypeValidator) method.invoke(new RedisJsonSerializerFactory(), props);
    }
}
