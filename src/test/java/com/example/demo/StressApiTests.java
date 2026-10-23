package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.entity.OrderStatus;
import com.example.demo.service.MasterService;
import com.example.demo.service.OrderService;
import com.example.demo.service.StressDataService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** stress 프로필의 부하 시험용 API. 로그인 없이 열리고, 기존 서비스의 규칙을 그대로 따른다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:stress-api;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("stress")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class StressApiTests {

	@Autowired
	MockMvc mvc;
	@Autowired
	OrderService orderService;
	@Autowired
	MasterService masterService;

	private static final String ORDERS = """
			{"CHINA":3000,"JAPAN":3000,"VIETNAM":3000,"INDONESIA":3000}""";

	@Test
	void healthIsOpenWithoutLogin() throws Exception {
		mvc.perform(get("/api/stress/health")).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.masterVersion").isNumber())
				.andExpect(jsonPath("$.stressOrders").value(0));
	}

	@Test
	void simulateWorksWithoutLoginAndKeepsInputValidation() throws Exception {
		mvc.perform(post("/api/stress/simulate").contentType(MediaType.APPLICATION_JSON)
				.content("{\"orders\":" + ORDERS + "}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.options.length()").value(3));
		mvc.perform(post("/api/stress/simulate").contentType(MediaType.APPLICATION_JSON)
				.content("{\"orders\":{\"CHINA\":-1,\"JAPAN\":3000,\"VIETNAM\":3000,\"INDONESIA\":3000}}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void bulkCreateUsesConfirmAndDeleteCancelsOnlyStressOrdersAndKeepsHistory() throws Exception {
		// 사람이 만든 주문 1건은 지워지면 안 된다
		long humanId = orderService.confirm(java.util.Map.of(
				com.example.demo.entity.Country.CHINA, 3000, com.example.demo.entity.Country.JAPAN, 3000,
				com.example.demo.entity.Country.VIETNAM, 3000, com.example.demo.entity.Country.INDONESIA, 3000),
				"COST", currentVersion(), null, null, null, "admin")
				.getId();

		mvc.perform(post("/api/stress/orders").contentType(MediaType.APPLICATION_JSON).content(withVersion("\"count\":5")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.created").value(5));
		assertThat(orderService.listOrders(null)).hasSize(6);
		assertThat(orderService.countByStatus().get(OrderStatus.CONFIRMED)).isEqualTo(6L);
		mvc.perform(get("/api/stress/health")).andExpect(jsonPath("$.stressOrders").value(5));

		java.util.List<Long> stressIds = orderService.listOrders(null).stream()
				.filter(o -> StressDataService.USER.equals(o.getCreatedBy())).map(o -> o.getId()).toList();
		assertThat(stressIds).hasSize(5);

		mvc.perform(delete("/api/stress/orders")).andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(5));
		assertThat(orderService.listOrders(null)).extracting(o -> o.getId()).containsExactly(humanId);
		assertThat(orderService.history(humanId)).hasSize(1);
		// 주문은 삭제돼도 상태 이력은 남는다(INTENT 원칙 9): 확정 -> 취소 두 건
		for (Long id : stressIds) {
			assertThat(orderService.history(id)).extracting(h -> h.getToStatus())
					.containsExactly(OrderStatus.CONFIRMED, OrderStatus.CANCELED);
		}
		mvc.perform(get("/api/stress/health")).andExpect(jsonPath("$.stressOrders").value(0));
		mvc.perform(delete("/api/stress/orders")).andExpect(jsonPath("$.deleted").value(0));
	}

	@Test
	void bulkCreateRejectsOutOfRangeCountAndBadDueDays() throws Exception {
		for (String bad : new String[] {"0", "-1", "1001"}) {
			mvc.perform(post("/api/stress/orders").contentType(MediaType.APPLICATION_JSON)
					.content(withVersion("\"count\":" + bad))).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("1~1000")));
		}
		// 확정 서비스의 검증(납기 5~50)이 그대로 적용된다
		mvc.perform(post("/api/stress/orders").contentType(MediaType.APPLICATION_JSON)
				.content(withVersion("\"count\":1,\"dueDays\":4"))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("5~50")));
		assertThat(orderService.listOrders(null)).isEmpty();
	}

	@Test
	void onlyMasterVersionCreatesOneDefaultOrder() throws Exception {
		mvc.perform(post("/api/stress/orders").contentType(MediaType.APPLICATION_JSON).content(withVersion("")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.created").value(1));
		assertThat(orderService.listOrders(null)).hasSize(1);
		assertThat(orderService.listOrders(null).get(0).getCreatedBy()).isEqualTo(StressDataService.USER);
	}

	/** 시뮬레이션 결과의 master.version 을 넘기는 요청 본문. extra 는 {@code "count":5} 처럼 쉼표 없이 쓴다. */
	private String withVersion(String extra) {
		return "{\"masterVersion\":" + currentVersion() + (extra.isEmpty() ? "" : "," + extra) + "}";
	}

	@Test
	void bulkCreateRequiresMasterVersionAndRejectsStaleOne() throws Exception {
		// 필수: 없으면 400, 아무것도 만들지 않는다
		mvc.perform(post("/api/stress/orders").contentType(MediaType.APPLICATION_JSON).content("{\"count\":3}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("masterVersion")));
		mvc.perform(post("/api/stress/orders")).andExpect(status().isBadRequest());
		// 시뮬레이션 뒤 마스터가 바뀐 상황: 낡은 버전은 409(INTENT 원칙 3)
		mvc.perform(post("/api/stress/orders").contentType(MediaType.APPLICATION_JSON)
				.content("{\"masterVersion\":" + (currentVersion() - 1) + ",\"count\":3}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.conflict").value(true));
		assertThat(orderService.listOrders(null)).isEmpty();
	}

	private long currentVersion() {
		return masterService.currentMaster().version();
	}
}
