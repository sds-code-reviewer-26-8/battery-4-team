package com.example.demo.slice;

import com.example.demo.config.LoginInterceptor;
import com.example.demo.entity.Order;
import com.example.demo.entity.OrderStatus;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.mock.web.MockHttpSession;

/** 슬라이스 테스트가 함께 쓰는 도우미 */
final class SliceSupport {

	static final String JSON = "application/json;charset=UTF-8";

	private SliceSupport() {
	}

	/** 로그인한 상태의 세션 */
	static MockHttpSession loginAs(String user) {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(LoginInterceptor.SESSION_USER, user);
		return session;
	}

	/** 확정 상태의 주문(저장하지 않은 객체) */
	static Order order(String createdBy, int totalDays, String customerName, Integer dueDays) {
		return new Order(LocalDateTime.of(2026, 10, 23, 9, 0), createdBy, 12000, "COST", "총비용 최소", totalDays,
				1L, 1L, 2L, "메모", customerName, dueDays, "{}");
	}

	/** 모든 상태의 건수를 0으로 채운 맵 */
	static Map<OrderStatus, Long> zeroCounts() {
		Map<OrderStatus, Long> counts = new EnumMap<>(OrderStatus.class);
		for (OrderStatus s : OrderStatus.values()) {
			counts.put(s, 0L);
		}
		return counts;
	}
}
