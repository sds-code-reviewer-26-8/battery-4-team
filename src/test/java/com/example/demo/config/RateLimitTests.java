package com.example.demo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.controller.AuthController;
import com.example.demo.service.MemberService;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 가입·로그인 횟수 제한. 스프링 전체를 띄우지 않고 제한기와 인터셉터만 확인한다. */
class RateLimitTests {

	private static final String BODY = "{\"username\":\"user01\",\"password\":\"pass1234\"}";

	@Test
	void limiterRejectsOverTheLimitAndRecoversAfterTheWindow() {
		AtomicLong now = new AtomicLong(1_000_000);
		RateLimiter limiter = new RateLimiter(3, Duration.ofSeconds(60), now::get);

		assertThat(limiter.tryAcquire("a")).isTrue();
		assertThat(limiter.tryAcquire("a")).isTrue();
		assertThat(limiter.tryAcquire("a")).isTrue();
		assertThat(limiter.tryAcquire("a")).isFalse();
		// 다른 주소는 따로 센다
		assertThat(limiter.tryAcquire("b")).isTrue();

		// 거절한 요청은 기록하지 않으므로, 첫 요청으로부터 60초가 지나면 다시 허용된다
		now.addAndGet(59_999);
		assertThat(limiter.tryAcquire("a")).isFalse();
		now.addAndGet(1);
		assertThat(limiter.tryAcquire("a")).isTrue();
	}

	@Test
	void retryAfterIsTheRemainingSecondsRoundedUp() {
		AtomicLong now = new AtomicLong(0);
		RateLimiter limiter = new RateLimiter(1, Duration.ofSeconds(60), now::get);
		assertThat(limiter.tryAcquire("a")).isTrue();
		assertThat(limiter.tryAcquire("a")).isFalse();

		now.set(10_500);
		assertThat(limiter.retryAfterSeconds("a")).isEqualTo(50); // 49.5초 → 올림
		assertThat(limiter.retryAfterSeconds("unknown")).isEqualTo(1);
	}

	@Test
	void signupAndLoginAreLimitedPerAddressAndSeparately() throws Exception {
		MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuthController(Mockito.mock(MemberService.class)))
				.addMappedInterceptors(new String[] {"/api/signup"},
						new RateLimitInterceptor(new RateLimiter(2, Duration.ofHours(1))))
				.addMappedInterceptors(new String[] {"/api/login"},
						new RateLimitInterceptor(new RateLimiter(2, Duration.ofMinutes(1))))
				.build();

		// 가입: 두 번까지 통과, 세 번째는 429 와 이유
		mvc.perform(post("/api/signup").contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isOk());
		mvc.perform(post("/api/signup").contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isOk());
		mvc.perform(post("/api/signup").contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(jsonPath("$.error").value(RateLimitInterceptor.MESSAGE))
				.andExpect(jsonPath("$.conflict").value(false));

		// 다른 주소는 영향받지 않는다
		mvc.perform(post("/api/signup").contentType(MediaType.APPLICATION_JSON).content(BODY)
						.with(remoteAddr("10.0.0.2")))
				.andExpect(status().isOk());

		// 로그인 제한은 가입과 따로 센다 (틀린 로그인은 400, 한도를 넘으면 429)
		mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.error").value(RateLimitInterceptor.MESSAGE));
	}

	private static RequestPostProcessor remoteAddr(String address) {
		return request -> {
			request.setRemoteAddr(address);
			return request;
		};
	}
}
