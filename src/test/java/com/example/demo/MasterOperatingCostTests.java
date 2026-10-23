package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.entity.Country;
import com.example.demo.entity.Factory;
import com.example.demo.repository.FactoryRepository;
import com.example.demo.service.BusinessException;
import com.example.demo.service.MasterService;
import com.example.demo.simulation.Master;
import com.example.demo.simulation.Plan;
import com.example.demo.simulation.Planner;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
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

	/** 보정이 끝나기 전에 마스터를 읽어도 NPE 없이 기본값(10억)으로 읽고, DB 값은 건드리지 않는다. */
	@Test
	void currentMasterUsesDefaultWhileOperatingCostIsStillEmpty() {
		Factory japan = factories.findById(Country.JAPAN).orElseThrow();
		japan.setDailyOperatingCostWon(null);
		factories.save(japan);

		var japanParam = service.currentMaster().factory(Country.JAPAN);

		assertThat(japanParam.dailyOperatingCostWon()).isEqualTo(10 * EOK);
		assertThat(factories.findById(Country.JAPAN).orElseThrow().getDailyOperatingCostWon()).isNull();
	}

	private static final Map<Country, Integer> ORDERS = orders(3000, 3000, 3000, 3000);

	private static Map<Country, Integer> orders(int china, int japan, int vietnam, int indonesia) {
		Map<Country, Integer> m = new EnumMap<>(Country.class);
		m.put(Country.CHINA, china);
		m.put(Country.JAPAN, japan);
		m.put(Country.VIETNAM, vietnam);
		m.put(Country.INDONESIA, indonesia);
		return m;
	}

	private static Plan plan(Master m, String type) {
		return Planner.plan(m, ORDERS).stream().filter(p -> p.type().equals(type)).findFirst().orElseThrow();
	}

	/** 경계값: 상한을 원 하나만 넘어도, 1원 미만이어도, 비어 있거나 공백뿐이어도 저장하지 않는다. */
	@Test
	void boundaryValuesAroundLimitsAreRejectedAndNothingIsSaved() {
		for (String bad : List.of("1000.00000001", "0.000000009", "", "   ", "0", "-0.00000001")) {
			Master before = service.currentMaster();
			Map<String, String> form = currentForm();
			form.put("operatingCost_CHINA", bad);
			assertThatThrownBy(() -> service.updateMaster(form)).as("'" + bad + "'")
					.isInstanceOf(BusinessException.class);
			assertThat(service.currentMaster()).as("'" + bad + "' 저장 안 됨").isEqualTo(before);
		}
	}

	/** 경계값: 앞뒤 공백과 천 단위 쉼표는 허용하고, 허용 하한(1원)과 상한(1,000억)은 그대로 저장된다. */
	@Test
	void boundaryValuesInsideLimitsAreAcceptedAfterTrimmingAndComma() {
		Map<String, String> form = currentForm();
		form.put("operatingCost_CHINA", " 1,000 ");
		form.put("operatingCost_JAPAN", "0.00000001");
		form.put("operatingCost_VIETNAM", "999.99999999");
		form.put("operatingCost_INDONESIA", "0.5");
		service.updateMaster(form);

		Master m = service.currentMaster();
		assertThat(operatingCostWon(m, Country.CHINA)).isEqualTo(1_000 * EOK);
		assertThat(operatingCostWon(m, Country.JAPAN)).isEqualTo(1L);
		assertThat(operatingCostWon(m, Country.VIETNAM)).isEqualTo(1_000 * EOK - 1);
		assertThat(operatingCostWon(m, Country.INDONESIA)).isEqualTo(EOK / 2);
		assertThat(MasterService.toForm(m)).containsEntry("operatingCost_CHINA", "1000");
	}

	/** 마스터에서 운영비를 바꾸면 그 뒤 계산부터 공장별 새 값으로 운영비와 총 비용이 바뀌고, 운영 일수는 그대로다. */
	@Test
	void masterChangeIsReflectedInNextSimulation() {
		Plan before = plan(service.currentMaster(), "FAST");

		Map<String, String> form = currentForm();
		form.put("operatingCost_JAPAN", "25");
		service.updateMaster(form);
		Plan after = plan(service.currentMaster(), "FAST");

		assertThat(after.totalDays()).isEqualTo(before.totalDays());
		for (Plan.FactoryOperation o : after.factoryOperations()) {
			long daily = o.country() == Country.JAPAN ? 25 * EOK : 10 * EOK;
			assertThat(o.cost()).as(o.country().name()).isEqualTo(o.days() * daily);
		}
		long japanDays = after.factoryOperations().stream().filter(o -> o.country() == Country.JAPAN)
				.findFirst().orElseThrow().days();
		assertThat(after.operatingCost()).isEqualTo(before.operatingCost() + japanDays * 15 * EOK);
		assertThat(after.totalCost()).isEqualTo(before.totalCost() + japanDays * 15 * EOK);
		assertThat(after.totalCost()).isEqualTo(after.productionCost() + after.shippingCost() + after.operatingCost());
	}

	/** 옛 DB(운영비 칸이 비어 있음): 보정 전에도 후에도 계산 결과가 같고, 보정은 기본값 10억으로 채운다. */
	@Test
	void legacyDbGivesSamePlanBeforeAndAfterBackfill() {
		Plan expected = plan(service.currentMaster(), "COST");
		for (Country c : Country.factories()) {
			Factory f = factories.findById(c).orElseThrow();
			f.setDailyOperatingCostWon(null);
			factories.save(f);
		}
		assertSameCosts(plan(service.currentMaster(), "COST"), expected);   // 보정 전: 기본값으로 읽는다

		service.initDefaults();

		for (Country c : Country.factories()) {
			assertThat(factories.findById(c).orElseThrow().getDailyOperatingCostWon()).as(c.name()).isEqualTo(10 * EOK);
		}
		assertSameCosts(plan(service.currentMaster(), "COST"), expected);   // 보정 후
	}

	/** Plan 은 배열(stock)을 품고 있어 equals 로 비교할 수 없으므로 일수·비용·공장별 운영을 비교한다. */
	private static void assertSameCosts(Plan actual, Plan expected) {
		assertThat(actual.totalDays()).isEqualTo(expected.totalDays());
		assertThat(actual.operatingCost()).isEqualTo(expected.operatingCost());
		assertThat(actual.totalCost()).isEqualTo(expected.totalCost());
		assertThat(actual.factoryOperations()).isEqualTo(expected.factoryOperations());
	}
}
