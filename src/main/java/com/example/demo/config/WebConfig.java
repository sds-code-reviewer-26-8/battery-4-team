package com.example.demo.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

	/** 부하 시험용 프로필. 이 프로필이 켜졌을 때만 {@code /api/stress/**} 가 로그인 없이 열린다. */
	public static final String STRESS_PROFILE = "stress";

	private final RateLimiter signupLimiter;
	private final RateLimiter loginLimiter;
	private final boolean stressProfile;

	/** 가입은 계정을 쌓고 로그인은 비밀번호 해시를 계산하므로 둘 다 주소별로 제한한다. 값은 설정으로 바꾼다. */
	public WebConfig(
			@Value("${app.rate-limit.signup.max-requests:10}") int signupMax,
			@Value("${app.rate-limit.signup.window-seconds:3600}") long signupWindowSeconds,
			@Value("${app.rate-limit.login.max-requests:60}") int loginMax,
			@Value("${app.rate-limit.login.window-seconds:60}") long loginWindowSeconds,
			Environment environment) {
		this.signupLimiter = new RateLimiter(signupMax, Duration.ofSeconds(signupWindowSeconds));
		this.loginLimiter = new RateLimiter(loginMax, Duration.ofSeconds(loginWindowSeconds));
		this.stressProfile = environment.acceptsProfiles(Profiles.of(STRESS_PROFILE));
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new RateLimitInterceptor(signupLimiter)).addPathPatterns("/api/signup");
		registry.addInterceptor(new RateLimitInterceptor(loginLimiter)).addPathPatterns("/api/login");
		List<String> open = new ArrayList<>(List.of("/login", "/login.html", "/api/login", "/signup", "/signup.html", "/api/signup", "/css/**", "/js/**", "/images/**", "/error", "/favicon.ico"));
		if (stressProfile) {
			open.add("/api/stress/**");
		}
		registry.addInterceptor(new LoginInterceptor())
				.addPathPatterns("/**")
				.excludePathPatterns(open);
	}
}
