package com.example.demo.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 설정값(app.rate-limit.*)이 실제 앱에서 가입·로그인 한도로 적용되는지 확인한다.
 * 다른 테스트와 DB 를 나누려고 메모리 DB 이름을 따로 쓴다.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:ratelimit-wiring;DB_CLOSE_DELAY=-1",
		"app.rate-limit.signup.max-requests=2",
		"app.rate-limit.signup.window-seconds=3600",
		"app.rate-limit.login.max-requests=3",
		"app.rate-limit.login.window-seconds=60"})
@AutoConfigureMockMvc
class RateLimitWiringTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void configuredLimitsApplyToSignupAndLogin() throws Exception {
		// 가입: 잘못된 입력(400)도 횟수에 든다. 두 번까지는 이유를 알려 주고, 세 번째는 429
		for (int i = 0; i < 2; i++) {
			mvc.perform(post("/api/signup").contentType(MediaType.APPLICATION_JSON)
							.content("{\"username\":\"a!\",\"password\":\"x\"}"))
					.andExpect(status().isBadRequest());
		}
		mvc.perform(post("/api/signup").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"a!\",\"password\":\"x\"}"))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(jsonPath("$.error").value(RateLimitInterceptor.MESSAGE));

		// 로그인: 설정한 3회까지는 비밀번호 오류(400), 네 번째는 429. 가입 한도와 따로 센다
		for (int i = 0; i < 3; i++) {
			mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
							.content("{\"username\":\"admin\",\"password\":\"x\"}"))
					.andExpect(status().isBadRequest());
		}
		mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"admin\",\"password\":\"admin\"}"))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.error").value(RateLimitInterceptor.MESSAGE));
	}
}
