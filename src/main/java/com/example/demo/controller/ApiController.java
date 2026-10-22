package com.example.demo.controller;

import com.example.demo.config.LoginInterceptor;
import com.example.demo.entity.Country;
import com.example.demo.entity.Order;
import com.example.demo.service.BusinessException;
import com.example.demo.service.MasterService;
import com.example.demo.service.OrderService;
import com.example.demo.service.SimulationService;
import com.example.demo.simulation.Master;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

@RestController
public class ApiController {

	public record SimulateRequest(Map<Country, Integer> orders) {
	}

	public record ConfirmRequest(Map<Country, Integer> orders, String routeType, long masterVersion, String memo,
			String customerName) {
	}

	private final MasterService masterService;
	private final SimulationService simulationService;
	private final OrderService orderService;

	public ApiController(MasterService masterService, SimulationService simulationService,
			OrderService orderService) {
		this.masterService = masterService;
		this.simulationService = simulationService;
		this.orderService = orderService;
	}

	@GetMapping("/api/master")
	public Master masterJson() {
		return masterService.currentMaster();
	}

	@PostMapping("/api/simulate")
	public ResponseEntity<?> simulate(@RequestBody SimulateRequest request) {
		try {
			return ResponseEntity.ok(simulationService.simulate(request.orders()));
		} catch (BusinessException e) {
			return apiError(e);
		}
	}

	@PostMapping("/api/orders")
	public ResponseEntity<?> confirm(@RequestBody ConfirmRequest request, @SessionAttribute(name = LoginInterceptor.SESSION_USER, required = false) String user) {
		try {
			Order order = orderService.confirm(request.orders(), request.routeType(), request.masterVersion(),
					request.memo(), request.customerName(), user);
			return ResponseEntity.ok(Map.of("id", order.getId()));
		} catch (BusinessException e) {
			return apiError(e);
		}
	}

	static ResponseEntity<Map<String, Object>> apiError(BusinessException e) {
		HttpStatus status = e.isConflict() ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
		return ResponseEntity.status(status).body(Map.of("error", e.getMessage(), "conflict", e.isConflict()));
	}
}
