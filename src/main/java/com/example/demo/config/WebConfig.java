package com.example.demo.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

	private final RateLimiter signupLimiter;
	private final RateLimiter loginLimiter;

	/** 가입은 계정을 쌓고 로그인은 비밀번호 해시를 계산하므로 둘 다 주소별로 제한한다. 값은 설정으로 바꾼다. */
	public WebConfig(
			@Value("${app.rate-limit.signup.max-requests:10}") int signupMax,
			@Value("${app.rate-limit.signup.window-seconds:3600}") long signupWindowSeconds,
			@Value("${app.rate-limit.login.max-requests:60}") int loginMax,
			@Value("${app.rate-limit.login.window-seconds:60}") long loginWindowSeconds) {
		this.signupLimiter = new RateLimiter(signupMax, Duration.ofSeconds(signupWindowSeconds));
		this.loginLimiter = new RateLimiter(loginMax, Duration.ofSeconds(loginWindowSeconds));
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new RateLimitInterceptor(signupLimiter)).addPathPatterns("/api/signup");
		registry.addInterceptor(new RateLimitInterceptor(loginLimiter)).addPathPatterns("/api/login");
		registry.addInterceptor(new LoginInterceptor())
				.addPathPatterns("/**")
				.excludePathPatterns("/login", "/login.html", "/api/login", "/signup", "/signup.html", "/api/signup", "/css/**", "/js/**", "/images/**", "/error", "/favicon.ico");
	}
}
