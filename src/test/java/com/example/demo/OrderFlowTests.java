package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.entity.OrderStatus;
import com.example.demo.service.BusinessException;
import com.example.demo.service.OrderService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class OrderFlowTests {

	private static final String ORDERS = """
			{"CHINA":3000,"JAPAN":3000,"VIETNAM":3000,"INDONESIA":3000}""";

	@Autowired
	MockMvc mvc;
	@Autowired
	OrderService orderService;

	private MockHttpSession login() throws Exception {
		MockHttpSession session = new MockHttpSession();
		mvc.perform(post("/api/login").session(session).contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"admin\",\"password\":\"admin\"}"))
				.andExpect(status().isOk());
		return session;
	}

	@Test
	void anonymousUserIsSentToLogin() throws Exception {
		mvc.perform(get("/orders")).andExpect(redirectedUrl("/login"));
		mvc.perform(post("/api/simulate").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/login")).andExpect(status().isOk());
	}

	/** 화면은 정적 HTML 이라 파일 주소로 직접 불러도, 화면 데이터 API 도 로그인 없이는 열리지 않는다. */
	@Test
	void anonymousUserCannotReadStaticPagesOrScreenApis() throws Exception {
		mvc.perform(get("/orders.html")).andExpect(redirectedUrl("/login"));
		mvc.perform(get("/master.html")).andExpect(redirectedUrl("/login"));
		mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/master/form")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/master").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/login.html")).andExpect(status().isOk());
	}

	@Test
	void wrongPasswordIsRejected() throws Exception {
		mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"admin\",\"password\":\"x\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("아이디 또는 비밀번호가 올바르지 않습니다"));
		mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"other\",\"password\":\"admin\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void pagesRender() throws Exception {
		MockHttpSession s = login();
		String[][] pages = {{"/orders", "/orders.html"}, {"/orders/new", "/order-new.html"},
				{"/master", "/master.html"}};
		for (String[] page : pages) {
			mvc.perform(get(page[0]).session(s)).andExpect(status().isOk()).andExpect(forwardedUrl(page[1]));
		}
		for (String api : new String[] {"/api/me", "/api/orders", "/api/master/form"}) {
			mvc.perform(get(api).session(s)).andExpect(status().isOk());
		}
	}

	@Test
	void simulateReturnsThreeOptions() throws Exception {
		mvc.perform(post("/api/simulate").session(login()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"orders\":" + ORDERS + "}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.options.length()").value(3))
				.andExpect(jsonPath("$.options[0].type").value("COST"))
				.andExpect(jsonPath("$.options[0].stock.CHINA").isArray())
				.andExpect(jsonPath("$.visits.JAPAN").value(2))
				.andExpect(jsonPath("$.master.factories.length()").value(4));
	}

	@Test
	void emptyOrderIsRejectedWithReason() throws Exception {
		mvc.perform(post("/api/simulate").session(login()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"orders\":{\"CHINA\":0}}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").exists());
	}

	private long confirm(MockHttpSession s) throws Exception {
		MvcResult sim = mvc.perform(post("/api/simulate").session(s).contentType(MediaType.APPLICATION_JSON)
				.content("{\"orders\":" + ORDERS + "}")).andReturn();
		int version = com.jayway.jsonpath.JsonPath.read(sim.getResponse().getContentAsString(), "$.master.version");
		MvcResult res = mvc.perform(post("/api/orders").session(s).contentType(MediaType.APPLICATION_JSON)
						.content("{\"orders\":" + ORDERS + ",\"routeType\":\"FAST\",\"masterVersion\":" + version
								+ ",\"memo\":\"테스트\"}"))
				.andExpect(status().isOk()).andReturn();
		Integer id = com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.id");
		return id;
	}

	@Test
	void confirmThenDetailAndStatusFlow() throws Exception {
		MockHttpSession s = login();
		long id = confirm(s);

		mvc.perform(get("/orders/" + id).session(s)).andExpect(status().isOk())
				.andExpect(forwardedUrl("/order-detail.html"));
		mvc.perform(get("/api/orders/" + id).session(s)).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"))
				.andExpect(jsonPath("$.history.length()").value(1));
		var order = orderService.getOrder(id);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
		assertThat(order.getPlanJson()).contains("\"master\"").contains("\"plan\"");

		// 확정 → 완료는 불가
		assertThatThrownBy(() -> orderService.changeStatus(id, OrderStatus.COMPLETED, order.getVersion(), "admin"))
				.isInstanceOf(BusinessException.class);

		orderService.changeStatus(id, OrderStatus.IN_PROGRESS, order.getVersion(), "admin");
		var inProgress = orderService.getOrder(id);
		assertThat(inProgress.getStatus()).isEqualTo(OrderStatus.IN_PROGRESS);

		// 오래된 version 으로 바꾸려 하면 충돌
		assertThatThrownBy(() -> orderService.changeStatus(id, OrderStatus.COMPLETED, order.getVersion(), "admin"))
				.isInstanceOf(BusinessException.class).hasMessageContaining("먼저");

		orderService.changeStatus(id, OrderStatus.COMPLETED, inProgress.getVersion(), "admin");
		assertThat(orderService.history(id)).hasSize(3);

		// 완료는 최종 상태
		var done = orderService.getOrder(id);
		assertThatThrownBy(() -> orderService.changeStatus(id, OrderStatus.CANCELED, done.getVersion(), "admin"))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	void confirmFailsWhenMasterChangedAfterSimulation() throws Exception {
		MockHttpSession s = login();
		mvc.perform(post("/api/orders").session(s).contentType(MediaType.APPLICATION_JSON)
						.content("{\"orders\":" + ORDERS + ",\"routeType\":\"COST\",\"masterVersion\":-1}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.conflict").value(true));
	}

	@Test
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
	void masterUpdateBumpsVersionAndKeepsConfirmedPlans() throws Exception {
		MockHttpSession s = login();
		long id = confirm(s);
		String before = orderService.getOrder(id).getPlanJson();

		Map<String, String> form = new LinkedHashMap<>();
		form.put("shipCount", "3");
		form.put("shipDailyCost", "1.5");
		for (String c : new String[] {"CHINA", "JAPAN", "VIETNAM", "INDONESIA"}) {
			form.put("maxDaily_" + c, "500");
			form.put("stockLimit_" + c, "5000");
			form.put("unitCost_" + c, "600");
			form.put("operatingCost_" + c, "12.5");
		}
		String[] all = {"KOREA", "CHINA", "JAPAN", "VIETNAM", "INDONESIA"};
		for (int i = 0; i < all.length; i++) {
			for (int j = i + 1; j < all.length; j++) {
				form.put("days_" + all[i] + "_" + all[j], "3");
			}
		}
		mvc.perform(post("/api/master").session(s).contentType(MediaType.APPLICATION_JSON)
						.content(new ObjectMapper().writeValueAsString(form)))
				.andExpect(status().isOk());

		assertThat(orderService.getOrder(id).getPlanJson()).isEqualTo(before);
		mvc.perform(get("/api/master").session(s))
				.andExpect(jsonPath("$.shipDailyCostWon").value(150_000_000));
	}

	@Test
	void invalidMasterIsNotSaved() throws Exception {
		MockHttpSession s = login();
		mvc.perform(post("/api/master").session(s).contentType(MediaType.APPLICATION_JSON)
						.content("{\"shipCount\":\"9\",\"shipDailyCost\":\"abc\"}"))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/master").session(s)).andExpect(jsonPath("$.shipCount").value(3));
	}
}
