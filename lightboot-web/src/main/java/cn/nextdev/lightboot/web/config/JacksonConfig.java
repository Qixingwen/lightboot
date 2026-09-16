package cn.nextdev.lightboot.web.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ext.javatime.deser.LocalDateDeserializer;
import tools.jackson.databind.ext.javatime.deser.LocalDateTimeDeserializer;
import tools.jackson.databind.ext.javatime.deser.LocalTimeDeserializer;
import tools.jackson.databind.ext.javatime.ser.LocalDateSerializer;
import tools.jackson.databind.ext.javatime.ser.LocalDateTimeSerializer;
import tools.jackson.databind.ext.javatime.ser.LocalTimeSerializer;
import tools.jackson.databind.module.SimpleModule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;

/**
 * Jackson JSON 序列化/反序列化全局配置（Jackson 3）。
 *
 * <p>统一配置日期时间格式、时区，确保全局 JSON 处理行为一致。
 *
 * <p>说明：Jackson 3 中「反序列化忽略未知属性」与「日期输出 ISO 文本而非时间戳」均为默认行为，
 * 无需再显式配置（对应 Jackson 2 时代的 {@code FAIL_ON_UNKNOWN_PROPERTIES} 与
 * {@code WRITE_DATES_AS_TIMESTAMPS} 开关）。
 */
@AutoConfiguration
public class JacksonConfig {

    /**
     * 默认构造方法。
     */
    public JacksonConfig() {
    }

    /**
     * 日期时间格式：{@code yyyy-MM-dd HH:mm:ss}
     */
    private static final String DATE_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss";

    /**
     * 日期格式：{@code yyyy-MM-dd}
     */
    private static final String DATE_FORMAT = "yyyy-MM-dd";

    /**
     * 时间格式：{@code HH:mm:ss}
     */
    private static final String TIME_FORMAT = "HH:mm:ss";

    /**
     * 默认时区：亚洲/上海
     */
    private static final String TIME_ZONE = "Asia/Shanghai";

    /**
     * {@link LocalDateTime} 格式化器（线程安全）
     */
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(DATE_TIME_FORMAT);

    /**
     * {@link LocalDate} 格式化器（线程安全）
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern(DATE_FORMAT);

    /**
     * {@link LocalTime} 格式化器（线程安全）
     */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern(TIME_FORMAT);

    /**
     * 自定义 Jackson {@code JsonMapper} 构建器。
     *
     * <p>配置内容：
     * <ul>
     *   <li>时区设为 {@code Asia/Shanghai}</li>
     *   <li>Java 8 日期时间类型的序列化器/反序列化器（jsr310 支持在 Jackson 3 中内置于 databind，
     *       通过 {@link SimpleModule} 覆盖默认格式即可，无需注册独立模块）</li>
     * </ul>
     *
     * @return Jackson JsonMapper 构建器自定义器
     */
    @Bean
    public JsonMapperBuilderCustomizer jsonMapperBuilderCustomizer() {
        SimpleModule javaTimeModule = new SimpleModule("lightBootJavaTimeModule")
                .addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME_FORMATTER))
                .addSerializer(LocalDate.class, new LocalDateSerializer(DATE_FORMATTER))
                .addSerializer(LocalTime.class, new LocalTimeSerializer(TIME_FORMATTER))
                .addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(DATE_TIME_FORMATTER))
                .addDeserializer(LocalDate.class, new LocalDateDeserializer(DATE_FORMATTER))
                .addDeserializer(LocalTime.class, new LocalTimeDeserializer(TIME_FORMATTER));

        return builder -> builder
                .defaultTimeZone(TimeZone.getTimeZone(TIME_ZONE))
                .addModule(javaTimeModule);
    }

}
