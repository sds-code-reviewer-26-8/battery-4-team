package com.example.demo.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 가입·로그인처럼 인증 없이 열려 있고 비용이 큰 요청을 클라이언트 주소별로 제한한다. 한도를 넘으면 429 와 이유를 돌려준다.
 * 주소는 연결된 쪽의 주소(getRemoteAddr)만 쓴다. X-Forwarded-For 는 요청자가 마음대로 적을 수 있어 믿지 않는다.
 * 프록시 뒤에 두면 모든 요청이 프록시 주소로 보이므로, 그때는 서버가 전달 헤더를 처리하도록 따로 설정해야 한다.
 */
public class RateLimitInterceptor implements HandlerInterceptor {

	static final String MESSAGE = "요청이 너무 많습니다. 잠시 뒤 다시 시도해 주세요";

	private final RateLimiter limiter;

	public RateLimitInterceptor(RateLimiter limiter) {
		this.limiter = limiter;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
			throws Exception {
		String client = request.getRemoteAddr();
		if (limiter.tryAcquire(client)) {
			return true;
		}
		response.setStatus(429);
		response.setHeader("Retry-After", String.valueOf(limiter.retryAfterSeconds(client)));
		response.setCharacterEncoding("UTF-8");
		response.setContentType("application/json;charset=UTF-8");
		response.getWriter().write("{\"error\":\"" + MESSAGE + "\",\"conflict\":false}");
		return false;
	}
}
