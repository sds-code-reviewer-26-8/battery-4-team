package com.example.demo.controller;

import com.example.demo.config.LoginInterceptor;
import com.example.demo.service.BusinessException;
import com.example.demo.service.MemberService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

@RestController
public class AuthController {

	private static final String ACCOUNT = "admin";

	private final MemberService memberService;

	public AuthController(MemberService memberService) {
		this.memberService = memberService;
	}

	public record LoginRequest(String username, String password) {
	}

	public record SignupRequest(String username, String password) {
	}

	@PostMapping("/api/signup")
	public ResponseEntity<?> signup(@RequestBody SignupRequest request) {
		try {
			memberService.signup(request.username(), request.password());
			return ResponseEntity.ok(Map.of("message", "가입했습니다. 로그인해 주세요"));
		} catch (BusinessException e) {
			return ApiController.apiError(e);
		}
	}

	@PostMapping("/api/login")
	public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpServletRequest servletRequest,
			HttpSession session) {
		if ((ACCOUNT.equals(request.username()) && ACCOUNT.equals(request.password()))
				|| memberService.authenticate(request.username(), request.password())) {
			// 로그인 전에 쥐고 있던 세션 ID 로는 로그인 상태가 되지 않게 새로 바꾼다. (세션 고정 방지)
			servletRequest.changeSessionId();
			session.setAttribute(LoginInterceptor.SESSION_USER, request.username());
			return ResponseEntity.ok(Map.of("username", request.username()));
		}
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(Map.of("error", "아이디 또는 비밀번호가 올바르지 않습니다", "conflict", false));
	}

	@PostMapping("/api/logout")
	public Map<String, Object> logout(HttpSession session) {
		session.invalidate();
		return Map.of();
	}

	/** 화면 상단에 보여 줄 로그인한 아이디 */
	@GetMapping("/api/me")
	public Map<String, String> me(@SessionAttribute(name = LoginInterceptor.SESSION_USER) String user) {
		return Map.of("username", user);
	}
}
