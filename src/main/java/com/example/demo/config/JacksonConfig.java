package com.example.demo.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/** 요청 값을 조용히 고쳐 받지 않는다: 정수 자리의 소수(1.5)와 문자열 자리의 숫자·불리언(123, true)은 400 으로 거부한다. */
@Configuration
public class JacksonConfig {

	@Bean
	JsonMapperBuilderCustomizer strictScalarCoercion() {
		return builder -> {
			builder.withCoercionConfig(LogicalType.Integer,
					config -> config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail));
			builder.withCoercionConfig(LogicalType.Textual, config -> config
					.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
					.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
					.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
		};
	}
}
