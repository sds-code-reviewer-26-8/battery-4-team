package com.example.demo.slice;

import static com.example.demo.slice.SliceSupport.JSON;
import static com.example.demo.slice.SliceSupport.loginAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.config.LoginInterceptor;
import com.example.demo.controller.AuthController;
import com.example.demo.service.BusinessException;
import com.example.demo.service.MemberService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 회원 가입·로그인: 로그인 없이 열린 길, 오류 응답 모양, 세션 처리를 본다. (회원 서비스는 가짜) */
@WebMvcTest(controllers = AuthController.class)
class SignupWebSliceTests {

	private static final String CREDENTIALS = "{\"username\":\"demo01\",\"password\":\"pw-1234\"}";

	@Autowired
	MockMvc mvc;

	@MockitoBean
	MemberService memberService;

	/** 가입은 로그인하지 않아도 할 수 있고, 입력한 값이 서비스로 전달된다. */
	@Test
	void signupIsOpenWithoutLoginAndPassesValues() throws Exception {
		mvc.perform(post("/api/signup").contentType(JSON).content(CREDENTIALS))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("가입했습니다. 로그인해 주세요"));
		verify(memberService).signup("demo01", "pw-1234");
	}

	/** 가입 규칙을 어기면 이유가 담긴 400 이다. */
	@Test
	void signupRuleViolationBecomes400WithReason() throws Exception {
		doThrow(new BusinessException("이미 사용 중인 아이디입니다")).when(memberService).signup("demo01", "pw-1234");
		mvc.perform(post("/api/signup").contentType(JSON).content(CREDENTIALS))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("이미 사용 중인 아이디입니다"))
				.andExpect(jsonPath("$.conflict").value(false));
	}

	/** 가입한 회원이 맞는 비밀번호로 로그인하면 세션에 아이디가 담긴다. */
	@Test
	void memberLoginStoresUsernameInSession() throws Exception {
		when(memberService.authenticate("demo01", "pw-1234")).thenReturn(true);
		MvcResult result = mvc.perform(post("/api/login").contentType(JSON).content(CREDENTIALS))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("demo01"))
				.andReturn();
		assertThat(result.getRequest().getSession().getAttribute(LoginInterceptor.SESSION_USER)).isEqualTo("demo01");
	}

	/** 고정 계정 admin/admin 은 회원 서비스를 거치지 않고 로그인된다. */
	@Test
	void adminAccountLogsInWithoutMemberLookup() throws Exception {
		mvc.perform(post("/api/login").contentType(JSON).content("{\"username\":\"admin\",\"password\":\"admin\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("admin"));
		verifyNoInteractions(memberService);
	}

	/** 틀린 정보로는 이유를 알리고 세션에 아이디를 남기지 않는다. */
	@Test
	void wrongCredentialsAreRejectedWithoutSession() throws Exception {
		when(memberService.authenticate("demo01", "pw-1234")).thenReturn(false);
		MvcResult result = mvc.perform(post("/api/login").contentType(JSON).content(CREDENTIALS))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("아이디 또는 비밀번호가 올바르지 않습니다"))
				.andReturn();
		assertThat(result.getRequest().getSession().getAttribute(LoginInterceptor.SESSION_USER)).isNull();
	}

	/** 로그인에 성공하면 로그인 전에 쥐고 있던 세션 ID 는 새것으로 바뀐다. (세션 고정 방지) */
	@Test
	void successfulLoginChangesSessionId() throws Exception {
		when(memberService.authenticate("demo01", "pw-1234")).thenReturn(true);
		MockHttpSession before = new MockHttpSession();
		String oldId = before.getId();
		MvcResult result = mvc.perform(post("/api/login").session(before).contentType(JSON).content(CREDENTIALS))
				.andExpect(status().isOk())
				.andReturn();
		assertThat(result.getRequest().getSession().getId()).isNotEqualTo(oldId);
	}

	/** 화면 상단의 로그인 아이디: 로그인하지 않으면 401, 로그인하면 아이디를 돌려준다. */
	@Test
	void meNeedsLoginAndReturnsUsername() throws Exception {
		mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/me").session(loginAs("demo01")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("demo01"));
	}
}
