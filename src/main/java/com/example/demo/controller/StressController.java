package com.example.demo.controller;

import com.example.demo.config.WebConfig;
import com.example.demo.entity.Country;
import com.example.demo.entity.Order;
import com.example.demo.service.BusinessException;
import com.example.demo.service.MasterService;
import com.example.demo.service.OrderService;
import com.example.demo.service.SimulationService;
import com.example.demo.service.StressDataService;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * JMeter 같은 부하 시험 도구용 API. stress 프로필({@code --spring.profiles.active=stress})에서만 만들어지고,
 * 이 프로필에서만 로그인 없이 열린다. 계산·확정은 기존 서비스를 그대로 부르므로 검증 규칙도 같다.
 * 개발·운영 앱에 켜지 않는다.
 */
@RestController
@RequestMapping("/api/stress")
@Profile(WebConfig.STRESS_PROFILE)
public class StressController {

	static final int MAX_BULK = 1000;
	private static final String DEFAULT_ROUTE = "COST";
	private static final int DEFAULT_K = 3000;

	/**
	 * masterVersion 은 필수다. 시뮬레이션 결과의 {@code master.version} 을 그대로 넘긴다(INTENT 원칙 3).
	 * 나머지는 생략할 수 있고, 생략하면 4개국 각 3,000K, 경로 COST, 1건, 가능한 빨리다.
	 */
	public record BulkRequest(Long masterVersion, Integer count, Map<Country, Integer> orders, String routeType,
			Integer dueDays) {
	}

	private final MasterService masterService;
	private final SimulationService simulationService;
	private final OrderService orderService;
	private final StressDataService stressData;

	public StressController(MasterService masterService, SimulationService simulationService,
			OrderService orderService, StressDataService stressData) {
		this.masterService = masterService;
		this.simulationService = simulationService;
		this.orderService = orderService;
		this.stressData = stressData;
	}

	/** 서버가 떠 있는지와 부하 시험 전후의 데이터 양을 본다. */
	@GetMapping("/health")
	public Map<String, Object> health() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("status", "UP");
		body.put("masterVersion", masterService.currentMaster().version());
		body.put("orders", orderService.countByStatus());
		body.put("stressOrders", stressData.countStressOrders());
		return body;
	}

	/** {@code /api/simulate} 와 같은 계산을 로그인 없이 부른다. */
	@PostMapping("/simulate")
	public ResponseEntity<?> simulate(@RequestBody ApiController.SimulateRequest request) {
		try {
			return ResponseEntity.ok(simulationService.simulate(request.orders()));
		} catch (BusinessException e) {
			return ApiController.apiError(e);
		}
	}

	/**
	 * 같은 주문을 count 건 확정한다. 건마다 {@link OrderService#confirm} 을 따로 불러 서버 재계산·검증이 그대로 돌고,
	 * 넘긴 masterVersion 이 현재 마스터와 다르면 409 로 거부한다. 중간에 마스터가 바뀌면 그때까지 만든 주문은 남는다.
	 */
	@PostMapping("/orders")
	public ResponseEntity<?> createOrders(@RequestBody(required = false) BulkRequest request) {
		try {
			if (request == null || request.masterVersion() == null) {
				throw new BusinessException("masterVersion 이 필요합니다. 시뮬레이션 결과의 master.version 을 넘기세요");
			}
			int count = request.count() == null ? 1 : request.count();
			if (count < 1 || count > MAX_BULK) {
				throw new BusinessException("count 는 1~%d 사이로 입력하세요".formatted(MAX_BULK));
			}
			Map<Country, Integer> orders = request.orders() == null ? evenOrders() : request.orders();
			String route = request.routeType() == null ? DEFAULT_ROUTE : request.routeType();
			Integer dueDays = request.dueDays();
			long masterVersion = request.masterVersion();

			Long first = null;
			Long last = null;
			for (int i = 0; i < count; i++) {
				Order order = orderService.confirm(orders, route, masterVersion, null, null, dueDays,
						StressDataService.USER);
				first = first == null ? order.getId() : first;
				last = order.getId();
			}
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("created", count);
			body.put("firstId", first);
			body.put("lastId", last);
			return ResponseEntity.ok(body);
		} catch (BusinessException e) {
			return ApiController.apiError(e);
		}
	}

	/** 부하 시험이 만든 주문(작성자 stress)을 취소해 지운다. 상태 이력은 남는다. */
	@DeleteMapping("/orders")
	public Map<String, Object> deleteOrders() {
		return Map.of("deleted", stressData.deleteStressOrders());
	}

	private static Map<Country, Integer> evenOrders() {
		Map<Country, Integer> m = new EnumMap<>(Country.class);
		for (Country c : Country.factories()) {
			m.put(c, DEFAULT_K);
		}
		return m;
	}
}
