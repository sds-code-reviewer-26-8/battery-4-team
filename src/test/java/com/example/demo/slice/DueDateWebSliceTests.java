package com.example.demo.slice;

import static com.example.demo.slice.SliceSupport.JSON;
import static com.example.demo.slice.SliceSupport.loginAs;
import static com.example.demo.slice.SliceSupport.order;
import static com.example.demo.slice.SliceSupport.zeroCounts;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.controller.ApiController;
import com.example.demo.controller.OrderController;
import com.example.demo.entity.Country;
import com.example.demo.entity.Order;
import com.example.demo.service.BusinessException;
import com.example.demo.service.MasterService;
import com.example.demo.service.OrderService;
import com.example.demo.service.SimulationService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 필요 납기일: 확정 요청의 납기 값 해석(정수만 허용)과 목록·상세 응답의 납기·초과 일수를 본다. (서비스는 가짜) */
@WebMvcTest(controllers = { OrderController.class, ApiController.class })
class DueDateWebSliceTests {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	OrderService orderService;

	@MockitoBean
	MasterService masterService;

	@MockitoBean
	SimulationService simulationService;

	@BeforeEach
	void stubConfirm() {
		Order saved = mock(Order.class);
		when(saved.getId()).thenReturn(7L);
		when(orderService.confirm(any(), any(), anyLong(), any(), any(), any(), any())).thenReturn(saved);
	}

	/** 납기 항목을 덧붙인 확정 요청 본문 */
	private static String confirmBody(String dueDaysPart) {
		return "{\"orders\":{\"CHINA\":1000},\"routeType\":\"COST\",\"masterVersion\":5" + dueDaysPart + "}";
	}

	@Test
	void omittedDueDaysMeansAsSoonAsPossible() throws Exception {
		mvc.perform(post("/api/orders").session(loginAs("alice")).contentType(JSON).content(confirmBody("")))
				.andExpect(status().isOk());
		verify(orderService).confirm(eq(Map.of(Country.CHINA, 1000)), eq("COST"), eq(5L), isNull(), isNull(),
				isNull(), eq("alice"));
	}

	@Test
	void nullDueDaysMeansAsSoonAsPossible() throws Exception {
		mvc.perform(post("/api/orders").session(loginAs("alice")).contentType(JSON)
				.content(confirmBody(",\"dueDays\":null"))).andExpect(status().isOk());
		verify(orderService).confirm(eq(Map.of(Country.CHINA, 1000)), eq("COST"), eq(5L), isNull(), isNull(),
				isNull(), eq("alice"));
	}

	@Test
	void integerDueDaysIsPassedToService() throws Exception {
		mvc.perform(post("/api/orders").session(loginAs("alice")).contentType(JSON)
				.content(confirmBody(",\"dueDays\":12"))).andExpect(status().isOk());
		verify(orderService).confirm(eq(Map.of(Country.CHINA, 1000)), eq("COST"), eq(5L), isNull(), isNull(),
				eq(12), eq("alice"));
	}

	/** 5.5 를 5 로 조용히 바꾸지 않고, 정수가 아니거나 정수 범위를 넘는 값은 서비스에 보내지 않고 400 으로 거부한다. */
	@ParameterizedTest
	@ValueSource(strings = { "5.5", "5.0", "\"10\"", "true", "[10]", "{}", "99999999999",
			"1000000000000000000000000000000" })
	void nonIntegerDueDaysIsRejectedBeforeService(String raw) throws Exception {
		mvc.perform(post("/api/orders").session(loginAs("alice")).contentType(JSON)
				.content(confirmBody(",\"dueDays\":" + raw)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("필요 납기일은 5~50 사이의 정수로 입력하세요"));
		verifyNoInteractions(orderService);
	}

	/** 서비스의 범위 검사(5~50) 거부는 이유를 담은 400 이다. */
	@Test
	void serviceRangeRejectionBecomes400() throws Exception {
		when(orderService.confirm(any(), any(), anyLong(), any(), any(), eq(4), any()))
				.thenThrow(new BusinessException("필요 납기일은 5~50 사이의 정수로 입력하세요"));
		mvc.perform(post("/api/orders").session(loginAs("alice")).contentType(JSON)
				.content(confirmBody(",\"dueDays\":4")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("필요 납기일은 5~50 사이의 정수로 입력하세요"))
				.andExpect(jsonPath("$.conflict").value(false));
	}

	/** 목록: 소요 일수가 납기를 넘으면 초과 일수를 주고, 넘지 않거나 납기가 없으면 null 이다. */
	@Test
	void listShowsDueOverDaysOnlyWhenLate() throws Exception {
		when(orderService.listOrders(null)).thenReturn(List.of(order("alice", 20, null, 12),
				order("alice", 20, null, 25), order("alice", 20, null, null)));
		when(orderService.countByStatus()).thenReturn(zeroCounts());

		mvc.perform(get("/api/orders").session(loginAs("alice")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.orders[0].dueDays").value(12))
				.andExpect(jsonPath("$.orders[0].dueOverDays").value(8))
				.andExpect(jsonPath("$.orders[1].dueDays").value(25))
				.andExpect(jsonPath("$.orders[1].dueOverDays").doesNotExist())
				.andExpect(jsonPath("$.orders[2].dueDays").doesNotExist())
				.andExpect(jsonPath("$.orders[2].dueOverDays").doesNotExist());
	}

	/** 상세에도 납기와 초과 일수가 같은 규칙으로 나온다. */
	@Test
	void detailShowsDueDaysAndOverDays() throws Exception {
		when(orderService.getOrder(1L)).thenReturn(order("alice", 20, null, 12));

		mvc.perform(get("/api/orders/1").session(loginAs("alice")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.dueDays").value(12))
				.andExpect(jsonPath("$.dueOverDays").value(8));
	}
}
