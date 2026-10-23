package com.example.demo.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.demo.config.LoginInterceptor;
import com.example.demo.entity.Country;
import com.example.demo.entity.Order;
import com.example.demo.entity.OrderStatus;
import com.example.demo.repository.OrderRepository;
import com.example.demo.service.MasterService;
import com.example.demo.simulation.Master;
import com.example.demo.simulation.Plan;
import com.example.demo.simulation.Planner;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 성능 테스트: 응답·계산 시간이 정해 둔 한도 안에 들어오는지 JUnit 으로 잰다.
 * <ul>
 * <li>한도는 이 PC 에서 잰 값(경로 계산 1~8ms, 시뮬레이션 API 4ms, 주문 1,000건 목록 21ms, 동시 요청 104건 90ms)의
 * 수십 배로 잡았다. 장비가 느리거나 CI 가 바빠도 흔들리지 않게 하되, 계산이 눈에 띄게 느려지는 변경은 잡는다.</li>
 * <li>측정은 워밍업(JIT·캐시)을 버린 뒤 여러 번 돌려 중앙값과 p95 로 본다. 첫 한 번의 느린 값에 흔들리지 않는다.</li>
 * <li>부하를 한계까지 올려 보는 스트레스 테스트가 아니다. 보통 사용 수준의 동시 요청에서 오류와 시간만 본다.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
// 주문 1,000건 등을 다른 테스트의 DB에 남기지 않는다. 테스트 DB(메모리, DB_CLOSE_DELAY=-1)는 컨텍스트가 닫혀도 남지만
// ddl-auto=create-drop 이 닫을 때 스키마를 지워 데이터가 새지 않는다. 두 설정 중 하나가 바뀌면 이 전제를 다시 확인해야 한다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PerformanceTests {

	// ---- 한도 (밀리초). 측정값은 각 테스트가 출력하는 요약 줄에서 볼 수 있다.
	private static final long PLAN_MEDIAN_LIMIT_MS = 200;
	private static final long PLAN_P95_LIMIT_MS = 500;
	private static final long SIMULATE_API_P95_LIMIT_MS = 300;
	private static final long CONCURRENT_TOTAL_LIMIT_MS = 5_000;
	private static final long LIST_API_P95_LIMIT_MS = 300;

	@Autowired
	MockMvc mvc;
	@Autowired
	MasterService masterService;
	@Autowired
	OrderRepository orderRepository;

	// ------------------------------------------------------------ 측정 도구

	/** 한 번 재는 일(밀리초 단위 시간을 돌려준다) */
	private record Stats(String name, int runs, double medianMs, double p95Ms, double maxMs) {
		@Override
		public String toString() {
			return "[성능] %s: %d회, 중앙값 %.1fms, p95 %.1fms, 최대 %.1fms".formatted(name, runs, medianMs, p95Ms,
					maxMs);
		}
	}

	private static Stats measure(String name, int warmup, int runs, Callable<?> work) throws Exception {
		for (int i = 0; i < warmup; i++) {
			work.call();
		}
		double[] ms = new double[runs];
		for (int i = 0; i < runs; i++) {
			long t0 = System.nanoTime();
			work.call();
			ms[i] = (System.nanoTime() - t0) / 1_000_000.0;
		}
		Arrays.sort(ms);
		Stats stats = new Stats(name, runs, ms[runs / 2], ms[(int) Math.ceil(runs * 0.95) - 1], ms[runs - 1]);
		System.out.println(stats);
		return stats;
	}

	private static MockHttpSession session(String user) {
		MockHttpSession s = new MockHttpSession();
		s.setAttribute(LoginInterceptor.SESSION_USER, user);
		return s;
	}

	private static Map<Country, Integer> orders(int china, int japan, int vietnam, int indonesia) {
		Map<Country, Integer> m = new EnumMap<>(Country.class);
		m.put(Country.CHINA, china);
		m.put(Country.JAPAN, japan);
		m.put(Country.VIETNAM, vietnam);
		m.put(Country.INDONESIA, indonesia);
		return m;
	}

	private static String ordersJson(Map<Country, Integer> o) {
		return "{\"CHINA\":%d,\"JAPAN\":%d,\"VIETNAM\":%d,\"INDONESIA\":%d}".formatted(o.get(Country.CHINA),
				o.get(Country.JAPAN), o.get(Country.VIETNAM), o.get(Country.INDONESIA));
	}

	private Master masterWithShips(int ships) {
		Master m = masterService.currentMaster();
		return new Master(m.version(), ships, m.shipDailyCostWon(), m.factories(), m.routes());
	}

	// ------------------------------------------------------------ 1. 경로 계산

	/** 기본 수량(4개국 3,000K)에서 배 1~4척 모두 경로 계산이 한도 안에 끝난다. */
	@Test
	void planWithDefaultOrderIsFastForEveryShipCount() throws Exception {
		Map<Country, Integer> order = orders(3000, 3000, 3000, 3000);
		for (int ships = 1; ships <= 4; ships++) {
			Master master = masterWithShips(ships);
			Stats s = measure("경로 계산 기본 수량 배 " + ships + "척", 3, 15, () -> Planner.plan(master, order));
			assertThat(s.medianMs()).as(s.toString()).isLessThanOrEqualTo(PLAN_MEDIAN_LIMIT_MS);
			assertThat(s.p95Ms()).as(s.toString()).isLessThanOrEqualTo(PLAN_P95_LIMIT_MS);
		}
	}

	/** 총량 상한(100,000K)에 가까운 큰 주문, 방문 횟수가 많은 입력에서도 계산이 한도 안에 끝난다. */
	@Test
	void planWithLargestOrderStaysWithinLimit() throws Exception {
		int max = Planner.MAX_TOTAL_K;
		List<Map<Country, Integer>> cases = List.of(
				orders(max / 4, max / 4, max / 4, max / 4),            // 고르게
				orders(max - 3, 1, 1, 1),                                // 한 나라에 몰림
				orders(40_000, 30_000, 20_000, 10_000));               // 기울어짐
		Master master = masterWithShips(4);
		int i = 0;
		for (Map<Country, Integer> order : cases) {
			Stats s = measure("경로 계산 상한 수량 사례 " + (++i), 2, 8, () -> Planner.plan(master, order));
			assertThat(s.medianMs()).as(s.toString()).isLessThanOrEqualTo(PLAN_MEDIAN_LIMIT_MS);
			assertThat(s.p95Ms()).as(s.toString()).isLessThanOrEqualTo(PLAN_P95_LIMIT_MS);
		}
	}

	/** 같은 계산을 여러 번 돌려도 결과 안 수(3개)와 총 비용 관계가 변하지 않는다(성능을 위해 정확성을 잃지 않는다). */
	@Test
	void planResultStaysConsistentWhileMeasuring() {
		Master master = masterWithShips(4);
		Map<Country, Integer> order = orders(3000, 3000, 3000, 3000);
		List<Plan> first = Planner.plan(master, order);
		for (int i = 0; i < 10; i++) {
			List<Plan> again = Planner.plan(master, order);
			assertThat(again).hasSize(3);
			for (int j = 0; j < 3; j++) {
				assertThat(again.get(j).totalCost()).isEqualTo(first.get(j).totalCost());
				assertThat(again.get(j).totalDays()).isEqualTo(first.get(j).totalDays());
			}
		}
	}

	// ------------------------------------------------------------ 2. 시뮬레이션 API

	/** 같은 요청을 여러 번 보내도 응답 시간(p95)이 한도 안이고 항상 200 이다. */
	@Test
	void simulateApiRespondsWithinLimit() throws Exception {
		String body = "{\"orders\":" + ordersJson(orders(3000, 3000, 3000, 3000)) + "}";
		Stats s = measure("시뮬레이션 API 기본 수량", 5, 40, () -> {
			mvc.perform(post("/api/simulate").session(session("perf")).contentType(MediaType.APPLICATION_JSON)
					.content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
							.status().isOk());
			return null;
		});
		assertThat(s.p95Ms()).as(s.toString()).isLessThanOrEqualTo(SIMULATE_API_P95_LIMIT_MS);
	}

	// ------------------------------------------------------------ 3. 동시 요청

	/**
	 * 여러 사용자가 동시에 시뮬레이션하고 주문을 확정해도 모두 성공하고, 확정한 만큼 주문이 저장되며, 전체가 한도 안에 끝난다.
	 * (스트레스 테스트가 아니라 보통 사용 수준의 동시성: 사용자 8명)
	 */
	@Test
	void concurrentSimulateAndConfirmAllSucceed() throws Exception {
		int users = 8;
		int simulatesEach = 10;
		int confirmsEach = 3;
		long before = orderRepository.count();
		long version = masterService.currentMaster().version();
		String ordersJson = ordersJson(orders(3000, 3000, 3000, 3000));

		ExecutorService pool = Executors.newFixedThreadPool(users);
		List<Future<Integer>> futures = new ArrayList<>();
		long t0 = System.nanoTime();
		int failures = 0;
		try {
			for (int u = 0; u < users; u++) {
				String user = "perf" + u;
				futures.add(pool.submit(() -> {
					int failed = 0;
					MockHttpSession s = session(user);
					for (int i = 0; i < simulatesEach; i++) {
						int status = mvc.perform(post("/api/simulate").session(s).contentType(MediaType.APPLICATION_JSON)
								.content("{\"orders\":" + ordersJson + "}")).andReturn().getResponse().getStatus();
						if (status != 200) {
							failed++;
						}
					}
					for (int i = 0; i < confirmsEach; i++) {
						int status = mvc.perform(post("/api/orders").session(s).contentType(MediaType.APPLICATION_JSON)
								.content("{\"orders\":" + ordersJson + ",\"routeType\":\"COST\",\"masterVersion\":"
										+ version + ",\"memo\":\"perf\"}"))
								.andReturn().getResponse().getStatus();
						if (status != 200) {
							failed++;
						}
					}
					return failed;
				}));
			}
			for (Future<Integer> f : futures) {
				failures += f.get(60, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow(); // 시간 초과 등으로 예외가 나도 스레드를 남기지 않는다
		}
		long totalMs = (System.nanoTime() - t0) / 1_000_000;
		System.out.printf("[성능] 동시 요청 사용자 %d명, 요청 %d건, 전체 %dms%n", users,
				users * (simulatesEach + confirmsEach), totalMs);

		assertThat(failures).as("실패한 요청 수").isZero();
		assertThat(orderRepository.count() - before).as("확정으로 저장된 주문 수").isEqualTo((long) users * confirmsEach);
		assertThat(totalMs).isLessThanOrEqualTo(CONCURRENT_TOTAL_LIMIT_MS);
	}

	// ------------------------------------------------------------ 4. 주문이 많을 때 조회

	/**
	 * 주문이 1,000건(확정 700 + 진행 300) 쌓여 있어도 전체 목록과 상태 필터 조회가 한도 안에 끝나고, 응답은 200 이며 건수가 정확하다.
	 * 필터가 실제로 일부 주문을 걸러 내도록 진행 상태 주문을 따로 만든다.
	 */
	@Test
	void orderListStaysFastWithManyOrders() throws Exception {
		long existingAll = orderRepository.count();
		long existingInProgress = orderRepository.countByStatus(OrderStatus.IN_PROGRESS);
		int added = 1_000;
		int addedInProgress = 300;
		List<Order> rows = new ArrayList<>();
		for (int i = 0; i < added; i++) {
			Order o = new Order(LocalDateTime.of(2026, 10, 23, 9, 0).plusMinutes(i), "perf", 12000, "COST", "총비용 최소",
					20, 1_000_000_000L, 200_000_000L, 1_200_000_000L, null, "고객사" + (i % 20), i % 3 == 0 ? 30 : null,
					"{}");
			if (i < addedInProgress) {
				o.setStatus(OrderStatus.IN_PROGRESS);
			}
			rows.add(o);
		}
		orderRepository.saveAll(rows);

		Stats list = measure("주문 목록 조회 " + (existingAll + added) + "건", 3, 20, () -> {
			MockHttpServletResponse res = mvc.perform(get("/api/orders").session(session("perf"))).andReturn()
					.getResponse();
			assertThat(res.getStatus()).isEqualTo(200);
			assertThat(res.getContentAsString()).contains("\"orders\"");
			return res;
		});
		Stats filtered = measure("주문 목록 상태 필터 조회(진행)", 3, 20, () -> {
			MockHttpServletResponse res = mvc.perform(get("/api/orders").param("status", "IN_PROGRESS")
					.session(session("perf"))).andReturn().getResponse();
			assertThat(res.getStatus()).isEqualTo(200);
			return res;
		});
		assertThat(list.p95Ms()).as(list.toString()).isLessThanOrEqualTo(LIST_API_P95_LIMIT_MS);
		assertThat(filtered.p95Ms()).as(filtered.toString()).isLessThanOrEqualTo(LIST_API_P95_LIMIT_MS);

		// 응답 건수가 정확하다: 전체는 기존 + 추가분 전부, 필터는 진행 상태 주문만
		assertThat(orderCount(null)).as("전체 목록의 주문 수").isEqualTo((int) (existingAll + added));
		assertThat(orderCount("IN_PROGRESS")).as("진행 필터의 주문 수")
				.isEqualTo((int) (existingInProgress + addedInProgress));
	}

	/** 목록 API 를 불러 돌려준 주문 수를 센다. 응답이 200 이 아니면 실패한다. */
	private int orderCount(String status) throws Exception {
		var request = get("/api/orders").session(session("perf"));
		if (status != null) {
			request = request.param("status", status);
		}
		MockHttpServletResponse res = mvc.perform(request).andReturn().getResponse();
		assertThat(res.getStatus()).isEqualTo(200);
		List<?> ids = JsonPath.read(res.getContentAsString(), "$.orders[*].id");
		return ids.size();
	}
}
