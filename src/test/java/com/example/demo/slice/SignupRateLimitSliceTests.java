package com.example.demo.slice;

import static com.example.demo.slice.SliceSupport.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.controller.AuthController;
import com.example.demo.service.MemberService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 가입 요청 횟수 제한: 한도(2회)를 넘으면 429 와 이유·다시 시도할 시간을 돌려주고, 로그인 제한과는 따로 센다. */
@WebMvcTest(controllers = AuthController.class, properties = { "app.rate-limit.signup.max-requests=2",
		"app.rate-limit.signup.window-seconds=3600" })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SignupRateLimitSliceTests {

	private static final String CREDENTIALS = "{\"username\":\"demo01\",\"password\":\"pw-1234\"}";

	@Autowired
	MockMvc mvc;

	@MockitoBean
	MemberService memberService;

	@Test
	void signupOverLimitReturns429WithReasonAndRetryAfter() throws Exception {
		mvc.perform(post("/api/signup").contentType(JSON).content(CREDENTIALS)).andExpect(status().isOk());
		mvc.perform(post("/api/signup").contentType(JSON).content(CREDENTIALS)).andExpect(status().isOk());
		String retryAfter = mvc.perform(post("/api/signup").contentType(JSON).content(CREDENTIALS))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.error").value("요청이 너무 많습니다. 잠시 뒤 다시 시도해 주세요"))
				.andExpect(jsonPath("$.conflict").value(false))
				.andExpect(header().exists("Retry-After"))
				.andReturn().getResponse().getHeader("Retry-After");
		assertThat(Long.parseLong(retryAfter)).isPositive();
	}

	/** 가입 한도를 다 써도 로그인은 막히지 않는다(제한을 따로 센다). */
	@Test
	void signupLimitDoesNotBlockLogin() throws Exception {
		for (int i = 0; i < 3; i++) {
			mvc.perform(post("/api/signup").contentType(JSON).content(CREDENTIALS));
		}
		mvc.perform(post("/api/login").contentType(JSON).content(CREDENTIALS))
				.andExpect(status().isBadRequest());
	}
}
