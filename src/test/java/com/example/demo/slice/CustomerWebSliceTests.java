package com.example.demo.slice;

import static com.example.demo.slice.SliceSupport.JSON;
import static com.example.demo.slice.SliceSupport.loginAs;
import static com.example.demo.slice.SliceSupport.order;
import static com.example.demo.slice.SliceSupport.zeroCounts;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 고객사 이름: 화면 요청이 컨트롤러·로그인 확인·JSON 변환을 거쳐 서비스로 전달되는 길만 본다. (서비스는 가짜) */
@WebMvcTest(controllers = { OrderController.class, ApiController.class })
class CustomerWebSliceTests {

	private static final String CUSTOMER_BODY = "{\"customerName\":\"삼성SDI\",\"version\":3}";

	@Autowired
	MockMvc mvc;

	@MockitoBean
	OrderService orderService;

	@MockitoBean
	MasterService masterService;

	@MockitoBean
	SimulationService simulationService;

	/** 로그인하지 않으면 고객사 수정 요청이 서비스까지 가지 못하고 401 이다. */
	@Test
	void customerUpdateNeedsLogin() throws Exception {
		mvc.perform(post("/api/orders/1/customer").contentType(JSON).content(CUSTOMER_BODY))
				.andExpect(status().isUnauthorized());
		verifyNoInteractions(orderService);
	}

	/** 요청 본문의 이름·version 과 세션의 로그인 아이디가 그대로 서비스에 전달된다. */
	@Test
	void customerUpdatePassesBodyAndLoginUserToService() throws Exception {
		mvc.perform(post("/api/orders/1/customer").session(loginAs("alice")).contentType(JSON).content(CUSTOMER_BODY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("고객사 이름을 저장했습니다"));
		verify(orderService).updateCustomerName(1L, "삼성SDI", 3L, "alice");
	}

	/** 서비스가 거부하면 이유를 그대로 담아 400 으로 돌려준다. */
	@Test
	void customerUpdateRejectionBecomes400WithReason() throws Exception {
		doThrow(new BusinessException("고객사 이름은 100자 이하로 입력하세요")).when(orderService)
				.updateCustomerName(anyLong(), any(), anyLong(), any());
		mvc.perform(post("/api/orders/1/customer").session(loginAs("alice")).contentType(JSON).content(CUSTOMER_BODY))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("고객사 이름은 100자 이하로 입력하세요"))
				.andExpect(jsonPath("$.conflict").value(false));
	}

	/** 다른 사람이 먼저 고친 경우(충돌)는 409 이다. */
	@Test
	void customerUpdateConflictBecomes409() throws Exception {
		doThrow(new BusinessException("다른 사용자가 먼저 변경했습니다", true)).when(orderService)
				.updateCustomerName(anyLong(), any(), anyLong(), any());
		mvc.perform(post("/api/orders/1/customer").session(loginAs("alice")).contentType(JSON).content(CUSTOMER_BODY))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.conflict").value(true));
	}

	/** 주문 확정 요청의 고객사 이름이 서비스의 customerName 자리로 전달된다. */
	@Test
	void confirmPassesCustomerNameToService() throws Exception {
		Order saved = mock(Order.class);
		when(saved.getId()).thenReturn(7L);
		when(orderService.confirm(any(), any(), anyLong(), any(), any(), any(), any())).thenReturn(saved);

		mvc.perform(post("/api/orders").session(loginAs("alice")).contentType(JSON).content(
				"{\"orders\":{\"CHINA\":1000},\"routeType\":\"COST\",\"masterVersion\":5,\"memo\":\"메모\",\"customerName\":\"삼성SDI\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(7));
		verify(orderService).confirm(Map.of(Country.CHINA, 1000), "COST", 5L, "메모", "삼성SDI", null, "alice");
	}

	/** 상세 응답에 고객사 이름이 있고, 작성자 본인에게만 수정 가능 표시가 켜진다. */
	@Test
	void detailShowsCustomerNameAndEditableOnlyForCreator() throws Exception {
		when(orderService.getOrder(1L)).thenReturn(order("alice", 20, "삼성SDI", null));

		mvc.perform(get("/api/orders/1").session(loginAs("alice")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.customerName").value("삼성SDI"))
				.andExpect(jsonPath("$.customerEditable").value(true));
		mvc.perform(get("/api/orders/1").session(loginAs("bob")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.customerName").value("삼성SDI"))
				.andExpect(jsonPath("$.customerEditable").value(false));
	}

	/** 목록 응답에 고객사 이름이 들어 있고, 비어 있으면 null 이다. */
	@Test
	void listShowsCustomerNameOrNull() throws Exception {
		when(orderService.listOrders(null))
				.thenReturn(List.of(order("alice", 20, "삼성SDI", null), order("alice", 20, null, null)));
		when(orderService.countByStatus()).thenReturn(zeroCounts());

		mvc.perform(get("/api/orders").session(loginAs("alice")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.orders[0].customerName").value("삼성SDI"))
				.andExpect(jsonPath("$.orders[1].customerName").doesNotExist());
	}
}
