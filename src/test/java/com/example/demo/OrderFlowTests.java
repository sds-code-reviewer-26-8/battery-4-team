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

	// ------------------------------------------------------------------ 고객사 이름

	/** 고객사 값을 raw JSON 조각으로 넣어 확정한다. customerJson 이 null 이면 customerName 키를 보내지 않는다. */
	private MvcResult confirmWithCustomer(MockHttpSession s, String customerJson) throws Exception {
		MvcResult sim = mvc.perform(post("/api/simulate").session(s).contentType(MediaType.APPLICATION_JSON)
				.content("{\"orders\":" + ORDERS + "}")).andReturn();
		int version = com.jayway.jsonpath.JsonPath.read(sim.getResponse().getContentAsString(), "$.master.version");
		String customer = customerJson == null ? "" : ",\"customerName\":" + customerJson;
		return mvc.perform(post("/api/orders").session(s).contentType(MediaType.APPLICATION_JSON)
				.content("{\"orders\":" + ORDERS + ",\"routeType\":\"FAST\",\"masterVersion\":" + version
						+ ",\"memo\":\"테스트\"" + customer + "}")).andReturn();
	}

	private long confirmedId(MvcResult res) throws Exception {
		assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(200);
		Integer id = com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.id");
		return id;
	}

	private String customerBody(String name, long version) {
		return "{\"customerName\":" + (name == null ? "null" : "\"" + name + "\"") + ",\"version\":" + version + "}";
	}

	@Test
	void confirmStoresTrimmedCustomerName() throws Exception {
		MockHttpSession s = login();
		long id = confirmedId(confirmWithCustomer(s, "\"  DSD 모터스  \""));
		assertThat(orderService.getOrder(id).getCustomerName()).isEqualTo("DSD 모터스");
		mvc.perform(get("/api/orders/" + id).session(s)).andExpect(status().isOk())
				.andExpect(jsonPath("$.customerName").value("DSD 모터스"))
				.andExpect(jsonPath("$.customerEditable").value(true));
		mvc.perform(get("/api/orders").session(s)).andExpect(status().isOk())
				.andExpect(jsonPath("$.orders[0].id").value((int) id))
				.andExpect(jsonPath("$.orders[0].customerName").value("DSD 모터스"));
	}

	@Test
	void confirmWithoutOrBlankCustomerNameStoresNull() throws Exception {
		MockHttpSession s = login();
		long noKey = confirmedId(confirmWithCustomer(s, null));
		long blank = confirmedId(confirmWithCustomer(s, "\"   \""));
		long empty = confirmedId(confirmWithCustomer(s, "\"\""));
		long explicitNull = confirmedId(confirmWithCustomer(s, "null"));
		for (long id : new long[] {noKey, blank, empty, explicitNull}) {
			assertThat(orderService.getOrder(id).getCustomerName()).isNull();
			mvc.perform(get("/api/orders/" + id).session(s)).andExpect(status().isOk())
					.andExpect(jsonPath("$.customerName").value((Object) null));
		}
	}

	@Test
	void customerNameLongerThan100IsRejectedAndExactly100Accepted() throws Exception {
		MockHttpSession s = login();
		MvcResult tooLong = confirmWithCustomer(s, "\"" + "가".repeat(101) + "\"");
		assertThat(tooLong.getResponse().getStatus()).isEqualTo(400);
		assertThat(tooLong.getResponse().getContentAsString()).contains("고객사 이름은 100자 이하로 입력하세요");
		// 앞뒤 공백을 지운 뒤의 길이로 검사한다: 100자 + 공백은 허용
		long id = confirmedId(confirmWithCustomer(s, "\"  " + "나".repeat(100) + "  \""));
		assertThat(orderService.getOrder(id).getCustomerName()).hasSize(100);

		// 수정에서도 같은 규칙
		var order = orderService.getOrder(id);
		mvc.perform(post("/api/orders/" + id + "/customer").session(s).contentType(MediaType.APPLICATION_JSON)
						.content(customerBody("다".repeat(101), order.getVersion())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("고객사 이름은 100자 이하로 입력하세요"))
				.andExpect(jsonPath("$.conflict").value(false));
		assertThat(orderService.getOrder(id).getCustomerName()).hasSize(100);
	}

	@Test
	void customerNameCanBeEditedWhileConfirmedAndInProgressWithoutHistory() throws Exception {
		MockHttpSession s = login();
		long id = confirmedId(confirmWithCustomer(s, "\"처음 고객사\""));
		assertThat(orderService.history(id)).hasSize(1);

		// 확정 상태
		var confirmed = orderService.getOrder(id);
		mvc.perform(post("/api/orders/" + id + "/customer").session(s).contentType(MediaType.APPLICATION_JSON)
						.content(customerBody("  바뀐 고객사  ", confirmed.getVersion())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("고객사 이름을 저장했습니다"));
		assertThat(orderService.getOrder(id).getCustomerName()).isEqualTo("바뀐 고객사");
		assertThat(orderService.history(id)).hasSize(1);

		// 진행 상태 (고객사 수정이 version 을 올릴 수 있으므로 새 version 을 다시 읽는다)
		orderService.changeStatus(id, OrderStatus.IN_PROGRESS, orderService.getOrder(id).getVersion(), "admin");
		assertThat(orderService.history(id)).hasSize(2);
		orderService.updateCustomerName(id, "진행 중 변경", orderService.getOrder(id).getVersion(), "admin");
		assertThat(orderService.getOrder(id).getCustomerName()).isEqualTo("진행 중 변경");
		assertThat(orderService.history(id)).hasSize(2);

		// 비우면 null
		orderService.updateCustomerName(id, "   ", orderService.getOrder(id).getVersion(), "admin");
		assertThat(orderService.getOrder(id).getCustomerName()).isNull();
		assertThat(orderService.history(id)).hasSize(2);
	}

	@Test
	void customerNameCannotBeEditedAfterCompleted() throws Exception {
		MockHttpSession s = login();
		long id = confirmedId(confirmWithCustomer(s, "\"완료 전 고객사\""));
		orderService.changeStatus(id, OrderStatus.IN_PROGRESS, orderService.getOrder(id).getVersion(), "admin");
		orderService.changeStatus(id, OrderStatus.COMPLETED, orderService.getOrder(id).getVersion(), "admin");

		mvc.perform(get("/api/orders/" + id).session(s)).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.customerEditable").value(false));
		mvc.perform(post("/api/orders/" + id + "/customer").session(s).contentType(MediaType.APPLICATION_JSON)
						.content(customerBody("완료 뒤 변경", orderService.getOrder(id).getVersion())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("완료 상태의 주문은 고객사를 수정할 수 없습니다"))
				.andExpect(jsonPath("$.conflict").value(false));
		assertThat(orderService.getOrder(id).getCustomerName()).isEqualTo("완료 전 고객사");
		assertThat(orderService.history(id)).hasSize(3);
	}

	@Test
	void customerNameOfCanceledOrderCannotBeEdited() throws Exception {
		MockHttpSession s = login();
		long id = confirmedId(confirmWithCustomer(s, "\"취소될 고객사\""));
		long version = orderService.getOrder(id).getVersion();
		orderService.changeStatus(id, OrderStatus.CANCELED, version, "admin");
		// 취소하면 주문이 삭제되므로 찾을 수 없다
		mvc.perform(post("/api/orders/" + id + "/customer").session(s).contentType(MediaType.APPLICATION_JSON)
						.content(customerBody("취소 뒤 변경", version)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").exists())
				.andExpect(jsonPath("$.conflict").value(false));
	}

	@Test
	void staleVersionOnCustomerEditIsConflict() throws Exception {
		MockHttpSession s = login();
		long id = confirmedId(confirmWithCustomer(s, "\"원래 고객사\""));
		long stale = orderService.getOrder(id).getVersion();
		orderService.changeStatus(id, OrderStatus.IN_PROGRESS, stale, "admin");

		mvc.perform(post("/api/orders/" + id + "/customer").session(s).contentType(MediaType.APPLICATION_JSON)
						.content(customerBody("낡은 화면에서 변경", stale)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.conflict").value(true))
				.andExpect(jsonPath("$.error").exists());
		assertThatThrownBy(() -> orderService.updateCustomerName(id, "낡은 화면에서 변경", stale, "admin"))
				.isInstanceOf(BusinessException.class).hasMessageContaining("먼저");
		assertThat(orderService.getOrder(id).getCustomerName()).isEqualTo("원래 고객사");
	}

	@Test
	void onlyTheOrderCreatorCanEditCustomerName() throws Exception {
		MockHttpSession s = login();
		long id = confirmedId(confirmWithCustomer(s, "\"작성자 고객사\""));
		long version = orderService.getOrder(id).getVersion();

		assertThatThrownBy(() -> orderService.updateCustomerName(id, "남이 바꿈", version, "other"))
				.isInstanceOf(BusinessException.class)
				.hasMessage("주문을 작성한 사람만 고객사를 수정할 수 있습니다");
		assertThatThrownBy(() -> orderService.updateCustomerName(id, "남이 바꿈", version, null))
				.isInstanceOf(BusinessException.class)
				.hasMessage("주문을 작성한 사람만 고객사를 수정할 수 있습니다");
		assertThat(orderService.getOrder(id).getCustomerName()).isEqualTo("작성자 고객사");
		assertThat(orderService.getOrder(id).getVersion()).isEqualTo(version);
	}

	@Test
	void customerNameIsNotPartOfSnapshot() throws Exception {
		MockHttpSession s = login();
		long id = confirmedId(confirmWithCustomer(s, "\"스냅샷에 없어야 하는 고객사\""));
		String before = orderService.getOrder(id).getPlanJson();
		assertThat(before).doesNotContain("스냅샷에 없어야 하는 고객사").doesNotContainIgnoringCase("customer");
		orderService.updateCustomerName(id, "바뀐 이름", orderService.getOrder(id).getVersion(), "admin");
		assertThat(orderService.getOrder(id).getPlanJson()).isEqualTo(before);
	}

	@Test
	void customerApisNeedLogin() throws Exception {
		mvc.perform(post("/api/orders/1/customer").contentType(MediaType.APPLICATION_JSON)
						.content("{\"customerName\":\"x\",\"version\":0}"))
				.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
						.content("{\"orders\":" + ORDERS + ",\"routeType\":\"FAST\",\"masterVersion\":1,\"customerName\":\"x\"}"))
				.andExpect(status().isUnauthorized());
	}
}
