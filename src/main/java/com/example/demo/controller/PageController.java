package com.example.demo.controller;

import com.example.demo.config.LoginInterceptor;
import com.example.demo.service.OrderService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** 화면 주소를 정적 HTML 로 연결한다. 화면에 보일 데이터는 /api/* 를 호출해 채운다. */
@Controller
public class PageController {

	private final OrderService orderService;

	public PageController(OrderService orderService) {
		this.orderService = orderService;
	}

	@GetMapping("/")
	public String home() {
		return "redirect:/orders";
	}

	@GetMapping("/login")
	public String login(HttpSession session, HttpServletResponse response) {
		if (session.getAttribute(LoginInterceptor.SESSION_USER) != null) {
			return "redirect:/orders";
		}
		return page("login", response);
	}

	@GetMapping("/orders")
	public String orders(HttpServletResponse response) {
		return page("orders", response);
	}

	@GetMapping("/orders/new")
	public String newOrder(HttpServletResponse response) {
		return page("order-new", response);
	}

	@GetMapping("/orders/{id}")
	public String detail(@PathVariable Long id, HttpServletResponse response) {
		orderService.getOrder(id); // 없는 주문이면 예외를 그대로 던진다 (알려진 결함: INTENT 9장 2번)
		return page("order-detail", response);
	}

	@GetMapping("/master")
	public String master(HttpServletResponse response) {
		return page("master", response);
	}

	/** 로그인 상태가 바뀌면 이전 화면이 남아 보이지 않도록 화면은 저장하지 않게 한다. */
	private static String page(String name, HttpServletResponse response) {
		response.setHeader("Cache-Control", "no-store");
		return "forward:/" + name + ".html";
	}
}
