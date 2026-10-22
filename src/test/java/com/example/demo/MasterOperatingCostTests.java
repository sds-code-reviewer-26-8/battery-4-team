package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.entity.Country;
import com.example.demo.entity.Factory;
import com.example.demo.repository.FactoryRepository;
import com.example.demo.service.BusinessException;
import com.example.demo.service.MasterService;
import com.example.demo.simulation.Master;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/** 공장 하루 운영비: 기본값, 입력 검증(0 초과 1,000억 원 이하), 저장 거부, 버전 증가, 기존 DB 보정 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MasterOperatingCostTests {

	private static final long EOK = 100_000_000L;

	@Autowired
	MasterService service;
	@Autowired
	FactoryRepository factories;

	private Map<String, String> currentForm() {
		return new HashMap<>(MasterService.toForm(service.currentMaster()));
	}

	private long operatingCostWon(Master m, Country c) {
		return m.factory(c).dailyOperatingCostWon();
	}

	@Test
	void defaultIsTenEokPerFactory() {
		Master m = service.currentMaster();
		for (Country c : Country.factories()) {
			assertThat(operatingCostWon(m, c)).as(c.name()).isEqualTo(10 * EOK);
		}
		assertThat(currentForm()).containsEntry("operatingCost_CHINA", "10");
	}

	@Test
	void saveStoresEachFactoryOwnValueInWonAndBumpsVersion() {
		long before = service.currentMaster().version();
		Map<String, String> form = currentForm();
		form.put("operatingCost_CHINA", "12.5");
		form.put("operatingCost_JAPAN", "1000");   // 상한 값 자체는 허용
		form.put("operatingCost_VIETNAM", "0.00000001"); // 1원
		form.put("operatingCost_INDONESIA", "7");

		service.updateMaster(form);

		Master m = service.currentMaster();
		assertThat(m.version()).isEqualTo(before + 1);
		assertThat(operatingCostWon(m, Country.CHINA)).isEqualTo(1_250_000_000L);
		assertThat(operatingCostWon(m, Country.JAPAN)).isEqualTo(1_000 * EOK);
		assertThat(operatingCostWon(m, Country.VIETNAM)).isEqualTo(1L);
		assertThat(operatingCostWon(m, Country.INDONESIA)).isEqualTo(7 * EOK);
	}

	@Test
	void valuesOutsideRangeAreRejectedWithReasonsAndNothingIsSaved() {
		Master before = service.currentMaster();
		Map<String, String> form = currentForm();
		form.put("maxDaily_CHINA", "401");                  // 다른 칸은 올바른 값이어도 저장되지 않는다
		form.put("operatingCost_CHINA", "1000.01");         // 상한 초과
		form.put("operatingCost_JAPAN", "0");               // 0 이하
		form.put("operatingCost_VIETNAM", "-3");
		form.put("operatingCost_INDONESIA", "abc");

		assertThatThrownBy(() -> service.updateMaster(form)).isInstanceOf(BusinessException.class)
				.satisfies(e -> assertThat(((BusinessException) e).getMessages())
						.anyMatch(m -> m.contains("중국 하루 운영비") && m.contains("1,000억 원 이하"))
						.anyMatch(m -> m.contains("일본 하루 운영비") && m.contains("0보다 커야"))
						.anyMatch(m -> m.contains("베트남 하루 운영비") && m.contains("0보다 커야"))
						.anyMatch(m -> m.contains("인도네시아 하루 운영비") && m.contains("숫자")));

		Master after = service.currentMaster();
		assertThat(after).isEqualTo(before);
	}

	@Test
	void missingOrNonWonValueIsRejected() {
		Map<String, String> form = currentForm();
		form.remove("operatingCost_CHINA");
		form.put("operatingCost_JAPAN", "1.000000001"); // 원 단위로 떨어지지 않음
		assertThatThrownBy(() -> service.updateMaster(form)).isInstanceOf(BusinessException.class)
				.satisfies(e -> assertThat(((BusinessException) e).getMessages())
						.anyMatch(m -> m.contains("중국 하루 운영비"))
						.anyMatch(m -> m.contains("일본 하루 운영비") && m.contains("원 단위")));
	}

	/** 운영비 도입 전에 만든 DB: 비어 있는 공장만 기본값 10억으로 채우고, 값이 있는 공장은 덮어쓰지 않는다. */
	@Test
	void initDefaultsFillsOnlyEmptyOperatingCosts() {
		long version = service.currentMaster().version();
		Factory china = factories.findById(Country.CHINA).orElseThrow();
		china.setDailyOperatingCostWon(3 * EOK);
		Factory japan = factories.findById(Country.JAPAN).orElseThrow();
		japan.setDailyOperatingCostWon(null);
		factories.save(china);
		factories.save(japan);

		service.initDefaults();

		assertThat(factories.findById(Country.CHINA).orElseThrow().getDailyOperatingCostWon()).isEqualTo(3 * EOK);
		assertThat(factories.findById(Country.JAPAN).orElseThrow().getDailyOperatingCostWon()).isEqualTo(10 * EOK);
		assertThat(service.currentMaster().version()).isEqualTo(version);   // 보정은 마스터 버전을 올리지 않는다
	}
}
