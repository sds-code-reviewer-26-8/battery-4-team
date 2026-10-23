package com.example.demo.slice;

import static com.example.demo.slice.SliceSupport.JSON;
import static com.example.demo.slice.SliceSupport.loginAs;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.controller.MasterController;
import com.example.demo.entity.Country;
import com.example.demo.service.BusinessException;
import com.example.demo.service.MasterService;
import com.example.demo.simulation.Master;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 공장 운영비: 마스터 화면이 받는 입력칸 값(억 원 단위)과 저장 요청의 전달·오류 응답을 본다. (마스터 서비스는 가짜) */
@WebMvcTest(controllers = MasterController.class)
class OperatingCostWebSliceTests {

	private static final long EOK = 100_000_000L;

	@Autowired
	MockMvc mvc;

	@MockitoBean
	MasterService masterService;

	private static Master master() {
		return new Master(1L, 3, EOK,
				List.of(new Master.FactoryParam(Country.CHINA, 400, 4000, 700, 10 * EOK),
						new Master.FactoryParam(Country.JAPAN, 800, 2000, 900, 15 * EOK),
						new Master.FactoryParam(Country.VIETNAM, 200, 10000, 550, 20 * EOK),
						new Master.FactoryParam(Country.INDONESIA, 300, 7000, 450, 25 * EOK)),
				List.of(new Master.RouteParam(Country.KOREA, Country.CHINA, 1)));
	}

	/** 입력칸 값에 나라별 하루 운영비가 억 원 단위로 들어 있고, 공장 4곳·나라 5곳 목록이 함께 온다. */
	@Test
	void formShowsOperatingCostInEokAndFactoryLists() throws Exception {
		when(masterService.currentMaster()).thenReturn(master());
		mvc.perform(get("/api/master/form").session(loginAs("admin")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.form.operatingCost_CHINA").value("10"))
				.andExpect(jsonPath("$.form.operatingCost_JAPAN").value("15"))
				.andExpect(jsonPath("$.form.operatingCost_VIETNAM").value("20"))
				.andExpect(jsonPath("$.form.operatingCost_INDONESIA").value("25"))
				.andExpect(jsonPath("$.factories.length()").value(4))
				.andExpect(jsonPath("$.countries.length()").value(5));
	}

	/** 저장 요청의 입력칸 값이 가공 없이 서비스로 전달된다. */
	@Test
	void savePassesFormToService() throws Exception {
		mvc.perform(post("/api/master").session(loginAs("admin")).contentType(JSON)
				.content("{\"operatingCost_CHINA\":\"30\",\"shipCount\":\"3\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("마스터 데이터를 저장했습니다"));
		verify(masterService).updateMaster(Map.of("operatingCost_CHINA", "30", "shipCount", "3"));
	}

	/** 값이 하나라도 틀리면 이유를 모두 모아 400 으로 돌려준다. */
	@Test
	void saveRejectionListsAllReasons() throws Exception {
		doThrow(new BusinessException(List.of("중국 하루 운영비는 0 이상이어야 합니다", "일본 하루 운영비는 숫자여야 합니다")))
				.when(masterService).updateMaster(anyMap());
		mvc.perform(post("/api/master").session(loginAs("admin")).contentType(JSON)
				.content("{\"operatingCost_CHINA\":\"-1\",\"operatingCost_JAPAN\":\"abc\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.conflict").value(false))
				.andExpect(jsonPath("$.messages.length()").value(2))
				.andExpect(jsonPath("$.messages[0]").value("중국 하루 운영비는 0 이상이어야 합니다"));
	}

	/** 로그인하지 않으면 조회도 저장도 서비스까지 가지 못한다. */
	@Test
	void masterApiNeedsLogin() throws Exception {
		mvc.perform(get("/api/master/form")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/master").contentType(JSON).content("{}")).andExpect(status().isUnauthorized());
		verifyNoInteractions(masterService);
	}
}
