package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.demo.repository.OrderRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 견고성 테스트: 정상이 아닌 입력과 동시 요청에도 서버가 500 없이 정해진 응답을 주고, 데이터가 어긋나지 않는지 확인한다.
 * 정상 입력의 조합 전수 시험은 PlannerTest 가 맡는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RobustnessTests {

	private static final String ORDERS = """
			{"CHINA":3000,"JAPAN":3000,"VIETNAM":3000,"INDONESIA":3000}""";

	@Autowired
	MockMvc mvc;
	@Autowired
	OrderRepository orderRepository;

	private MockHttpSession login() throws Exception {
		MockHttpSession session = new MockHttpSession();
		send(session, "/api/login", "{\"username\":\"admin\",\"password\":\"admin\"}");
		return session;
	}

	/** 처리 중 예외가 밖으로 새면(서버 오류) 500 으로 본다. */
	private int send(MockHttpSession s, String url, String body) throws Exception {
		try {
			return postResult(s, url, body).getResponse().getStatus();
		} catch (jakarta.servlet.ServletException e) {
			return 500;
		}
	}

	private MvcResult postResult(MockHttpSession s, String url, String body) throws Exception {
		var request = post(url).contentType(MediaType.APPLICATION_JSON).content(body);
		if (s != null) {
			request.session(s);
		}
		return mvc.perform(request).andReturn();
	}

	private final List<String> violations = new ArrayList<>();

	/** 한 입력이 어긋나도 나머지를 계속 확인해, 끝에서 어긋난 입력을 한꺼번에 알린다. */
	private void assertClientError(int status, String what) {
		if (status < 400 || status > 499) {
			violations.add(what + " -> " + status);
		}
	}

	private void assertNoViolations() {
		assertThat(violations).as("4xx 로 거부되어야 하는데 그렇지 않은 입력").isEmpty();
	}

	/** R-1: 시뮬레이션 요청의 경계·비정상 수량은 500 이 아니라 400 으로 거부된다. */
	@Test
	void simulateRejectsAbnormalQuantitiesWithoutServerError() throws Exception {
		MockHttpSession s = login();
		String[] bodies = {
				"{\"orders\":{\"CHINA\":2147483647}}",
				"{\"orders\":{\"CHINA\":2147483647,\"JAPAN\":2147483647,\"VIETNAM\":2147483647}}",
				"{\"orders\":{\"CHINA\":100001}}",
				"{\"orders\":{\"CHINA\":-1,\"JAPAN\":5000}}",
				"{\"orders\":{\"CHINA\":0,\"JAPAN\":0,\"VIETNAM\":0,\"INDONESIA\":0}}",
				"{\"orders\":{}}",
				"{\"orders\":null}",
				"{}",
				"{\"orders\":{\"MARS\":1000}}",
				"{\"orders\":{\"CHINA\":\"abc\"}}",
				"{\"orders\":{\"CHINA\":1.5}}",
				"{\"orders\":{\"CHINA\":99999999999999999999}}",
				"{\"orders\":[1,2,3]}",
				"not json",
				"",
		};
		for (String body : bodies) {
			assertClientError(send(s, "/api/simulate", body), "simulate " + body);
		}
		assertNoViolations();
	}

	/** R-1: 한 나라가 상한(100,000K)과 같으면 계산되고, 한국(공장 아님) 수량은 무시된다. */
	@Test
	void simulateAcceptsLimitBoundaryAndIgnoresKorea() throws Exception {
		MockHttpSession s = login();
		assertThat(send(s, "/api/simulate", "{\"orders\":{\"CHINA\":100000}}")).isEqualTo(200);
		assertThat(send(s, "/api/simulate", "{\"orders\":{\"CHINA\":1000,\"KOREA\":999999}}")).isEqualTo(200);
	}

	/** R-2: 가입·로그인·확정 요청의 깨진 형식과 잘못된 타입은 4xx 로 거부되고, 주문은 생기지 않는다. */
	@Test
	void malformedRequestsAreRejectedAndCreateNothing() throws Exception {
		MockHttpSession s = login();
		long before = orderRepository.count();

		String[] confirmBodies = {
				"{",
				"{\"orders\":" + ORDERS + "}",
				"{\"orders\":" + ORDERS + ",\"routeType\":\"FAST\"}",
				"{\"orders\":" + ORDERS + ",\"routeType\":\"FAST\",\"masterVersion\":\"x\"}",
				"{\"orders\":" + ORDERS + ",\"routeType\":null,\"masterVersion\":1}",
				"{\"orders\":" + ORDERS + ",\"routeType\":\"SLOW\",\"masterVersion\":1}",
				"{\"orders\":null,\"routeType\":\"FAST\",\"masterVersion\":1}",
				"{\"orders\":" + ORDERS + ",\"routeType\":\"FAST\",\"masterVersion\":1,\"customerName\":\"" + "가".repeat(5000) + "\"}",
		};
		for (String body : confirmBodies) {
			assertClientError(send(s, "/api/orders", body), "confirm " + body);
		}
		assertThat(orderRepository.count()).as("거부된 확정은 주문을 만들지 않는다").isEqualTo(before);

		for (String body : new String[] {"{", "{}", "{\"username\":null,\"password\":null}",
				"{\"username\":123,\"password\":true}",
				"{\"username\":\"" + "a".repeat(100000) + "\",\"password\":\"x\"}"}) {
			assertClientError(send(null, "/api/login", body), "login " + abbreviate(body));
			assertClientError(send(null, "/api/signup", body), "signup " + abbreviate(body));
		}
		assertClientError(send(null, "/api/signup",
				"{\"username\":\"robust1\",\"password\":\"" + "p".repeat(101) + "\"}"), "긴 비밀번호");
		assertNoViolations();
	}

	/** R-3: 같은 화면에서 동시에 여러 번 확정해도 성공한 만큼만 주문이 생기고 번호가 겹치지 않는다. */
	@Test
	void concurrentConfirmsCreateExactlyAsManyOrdersAsSucceeded() throws Exception {
		MockHttpSession s = login();
		MvcResult sim = postResult(s, "/api/simulate", "{\"orders\":" + ORDERS + "}");
		int version = com.jayway.jsonpath.JsonPath.read(sim.getResponse().getContentAsString(), "$.master.version");
		String body = "{\"orders\":" + ORDERS + ",\"routeType\":\"COST\",\"masterVersion\":" + version
				+ ",\"memo\":\"동시 확정\"}";

		int threads = 8;
		long before = orderRepository.count();
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Callable<MvcResult>> jobs = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				jobs.add(() -> postResult(s, "/api/orders", body));
			}
			List<Integer> ids = new ArrayList<>();
			for (Future<MvcResult> f : pool.invokeAll(jobs)) {
				MvcResult r = f.get();
				assertThat(r.getResponse().getStatus()).as("동시 확정 응답").isIn(200, 409);
				if (r.getResponse().getStatus() == 200) {
					ids.add(com.jayway.jsonpath.JsonPath.read(r.getResponse().getContentAsString(), "$.id"));
				}
			}
			assertThat(ids).as("성공한 확정이 있다").isNotEmpty().doesNotHaveDuplicates();
			assertThat(orderRepository.count() - before).isEqualTo(ids.size());
		} finally {
			pool.shutdownNow();
		}
	}

	/** R-4: 상태·고객사 변경 API 에 없는 상태값, 누락된 version, 없는 주문 번호를 보내도 4xx 로 거부된다. */
	@Test
	void statusAndCustomerUpdatesRejectInvalidInput() throws Exception {
		MockHttpSession s = login();
		MvcResult sim = postResult(s, "/api/simulate", "{\"orders\":" + ORDERS + "}");
		int version = com.jayway.jsonpath.JsonPath.read(sim.getResponse().getContentAsString(), "$.master.version");
		MvcResult created = postResult(s, "/api/orders", "{\"orders\":" + ORDERS
				+ ",\"routeType\":\"FAST\",\"masterVersion\":" + version + "}");
		int id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

		String[] statusBodies = {"{\"status\":\"XYZ\",\"version\":0}", "{\"status\":null,\"version\":0}", "{\"version\":0}",
				"{\"status\":\"IN_PROGRESS\",\"version\":\"a\"}", "{\"status\":\"IN_PROGRESS\",\"version\":-1}",
				"{\"status\":\"IN_PROGRESS\",\"version\":99999999999999999999}", "[]", "x"};
		for (String body : statusBodies) {
			assertClientError(send(s, "/api/orders/" + id + "/status", body), "status " + body);
		}
		assertClientError(send(s, "/api/orders/999999/status", "{\"status\":\"IN_PROGRESS\",\"version\":0}"), "없는 주문");
		assertClientError(send(s, "/api/orders/abc/status", "{\"status\":\"IN_PROGRESS\",\"version\":0}"), "주문 번호가 숫자가 아님");
		assertClientError(send(s, "/api/orders/" + id + "/customer",
				"{\"customerName\":\"" + "가".repeat(5000) + "\",\"version\":0}"), "긴 고객사명");
		assertThat(mvc.perform(get("/api/orders/" + id).session(s)).andReturn().getResponse().getContentAsString())
				.as("잘못된 요청 뒤에도 상태는 확정 그대로").contains("\"status\":\"CONFIRMED\"");
		assertNoViolations();
	}

	private static String abbreviate(String s) {
		return s.length() > 40 ? s.substring(0, 40) + "…" : s;
	}
}
