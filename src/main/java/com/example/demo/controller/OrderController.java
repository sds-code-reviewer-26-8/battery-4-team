package com.example.demo.controller;

import com.example.demo.config.LoginInterceptor;
import com.example.demo.entity.Order;
import com.example.demo.entity.OrderStatus;
import com.example.demo.entity.OrderStatusHistory;
import com.example.demo.service.BusinessException;
import com.example.demo.service.OrderService;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

@RestController
public class OrderController {

	private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private static final DateTimeFormatter SECOND = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	public record StatusRequest(OrderStatus status, long version) {
	}

	private final OrderService orderService;

	public OrderController(OrderService orderService) {
		this.orderService = orderService;
	}

	/** 주문 목록(최신순)과 상태별 건수 */
	@GetMapping("/api/orders")
	public Map<String, Object> list(@RequestParam(required = false) OrderStatus status) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Order o : orderService.listOrders(status)) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", o.getId());
			row.put("confirmedAt", o.getConfirmedAt().format(MINUTE));
			row.put("totalK", o.getTotalK());
			row.put("routeLabel", o.getRouteLabel());
			row.put("totalDays", o.getTotalDays());
			row.put("dueDays", o.getDueDays());
			row.put("dueOverDays", o.getDueOverDays());
			row.put("totalCostEok", o.getTotalCostEok());
			row.put("status", o.getStatus().name());
			row.put("statusLabel", o.getStatus().getLabel());
			row.put("createdBy", o.getCreatedBy());
			rows.add(row);
		}
		Map<OrderStatus, Long> counts = orderService.countByStatus();
		List<Map<String, Object>> statuses = new ArrayList<>();
		for (OrderStatus s : OrderStatus.values()) {
			statuses.add(Map.of("status", s.name(), "label", s.getLabel(), "count", counts.get(s)));
		}
		return Map.of("orders", rows, "statuses", statuses, "total", orderService.listOrders(null).size());
	}

	/** 주문 상세: 저장된 스냅샷(planJson), 상태 이력, 다음에 바꿀 수 있는 상태, 화면에서 본 version */
	@GetMapping("/api/orders/{id}")
	public ResponseEntity<?> detail(@PathVariable Long id) {
		try {
			Order o = orderService.getOrder(id);
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("id", o.getId());
			body.put("status", o.getStatus().name());
			body.put("statusLabel", o.getStatus().getLabel());
			body.put("version", o.getVersion());
			body.put("confirmedAt", o.getConfirmedAt().format(MINUTE));
			body.put("createdBy", o.getCreatedBy());
			body.put("routeLabel", o.getRouteLabel());
			body.put("memo", o.getMemo());
			body.put("totalDays", o.getTotalDays());
			body.put("dueDays", o.getDueDays());
			body.put("dueOverDays", o.getDueOverDays());
			body.put("next", o.getStatus().next().stream()
					.map(s -> Map.of("status", s.name(), "label", s.getLabel())).toList());
			body.put("history", orderService.history(id).stream().map(OrderController::historyRow).toList());
			body.put("planJson", o.getPlanJson());
			return ResponseEntity.ok(body);
		} catch (BusinessException e) {
			return ApiController.apiError(e);
		}
	}

	@PostMapping("/api/orders/{id}/status")
	public ResponseEntity<?> changeStatus(@PathVariable Long id, @RequestBody StatusRequest request,
			@SessionAttribute(name = LoginInterceptor.SESSION_USER, required = false) String user) {
		try {
			orderService.changeStatus(id, request.status(), request.version(), user);
			return ResponseEntity.ok(Map.of("message", "상태를 '" + request.status().getLabel() + "'(으)로 바꿨습니다"));
		} catch (BusinessException e) {
			return ApiController.apiError(e);
		}
	}

	private static Map<String, Object> historyRow(OrderStatusHistory h) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("changedAt", h.getChangedAt().format(SECOND));
		row.put("from", h.getFromStatus() != null ? h.getFromStatus().getLabel() : null);
		row.put("to", h.getToStatus().getLabel());
		row.put("changedBy", h.getChangedBy());
		return row;
	}
}
