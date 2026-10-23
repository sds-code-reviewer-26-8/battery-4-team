package com.example.demo.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** 필요 납기 초과 일수 계산({@link Order#getDueOverDays()})만 검사한다. DB·Spring 없이 돈다. */
class OrderDueOverDaysTest {

	private static Order order(int totalDays, Integer dueDays) {
		return new Order(LocalDateTime.of(2026, 10, 22, 9, 0), "admin", 12000, "FAST", "가장 빠르게", totalDays,
				1L, 1L, 2L, null, null, dueDays, "{}");
	}

	@Test
	void asapOrderNeverShowsOverdue() {
		Order o = order(40, null);
		assertThat(o.getDueDays()).isNull();
		assertThat(o.getDueOverDays()).isNull();
	}

	@Test
	void finishingExactlyOnDueDateIsNotOverdue() {
		assertThat(order(30, 30).getDueOverDays()).isNull();
	}

	@Test
	void finishingEarlyIsNotOverdue() {
		assertThat(order(29, 30).getDueOverDays()).isNull();
	}

	@Test
	void oneDayLateIsOverdueByOne() {
		assertThat(order(31, 30).getDueOverDays()).isEqualTo(1);
	}

	@Test
	void overdueDaysIsTotalDaysMinusDueDays() {
		assertThat(order(47, 5).getDueOverDays()).isEqualTo(42);
		assertThat(order(60, 50).getDueOverDays()).isEqualTo(10);
	}
}
