package com.example.demo.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.entity.Country;
import com.example.demo.simulation.Master.FactoryParam;
import com.example.demo.simulation.Master.RouteParam;
import com.example.demo.simulation.Plan.Stop;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlannerTest {

	private static Master master(int ships) {
		return new Master(1, ships, 100_000_000L,
				List.of(new FactoryParam(Country.CHINA, 400, 4000, 700),
						new FactoryParam(Country.JAPAN, 800, 2000, 900),
						new FactoryParam(Country.VIETNAM, 200, 10000, 550),
						new FactoryParam(Country.INDONESIA, 300, 7000, 450)),
				List.of(new RouteParam(Country.KOREA, Country.CHINA, 1),
						new RouteParam(Country.KOREA, Country.JAPAN, 2),
						new RouteParam(Country.KOREA, Country.VIETNAM, 5),
						new RouteParam(Country.KOREA, Country.INDONESIA, 7),
						new RouteParam(Country.CHINA, Country.JAPAN, 2),
						new RouteParam(Country.CHINA, Country.VIETNAM, 4),
						new RouteParam(Country.CHINA, Country.INDONESIA, 6),
						new RouteParam(Country.JAPAN, Country.VIETNAM, 5),
						new RouteParam(Country.JAPAN, Country.INDONESIA, 7),
						new RouteParam(Country.VIETNAM, Country.INDONESIA, 2)));
	}

	private static Map<Country, Integer> orders(int china, int japan, int vietnam, int indonesia) {
		Map<Country, Integer> m = new EnumMap<>(Country.class);
		m.put(Country.CHINA, china);
		m.put(Country.JAPAN, japan);
		m.put(Country.VIETNAM, vietnam);
		m.put(Country.INDONESIA, indonesia);
		return m;
	}

	@Test
	void visitsAreCeilOfOrderOverStockLimit() {
		Map<Country, Integer> v = Planner.visits(master(3), orders(4001, 2000, 0, 14000));
		assertThat(v.get(Country.CHINA)).isEqualTo(2);
		assertThat(v.get(Country.JAPAN)).isEqualTo(1);
		assertThat(v.get(Country.VIETNAM)).isEqualTo(0);
		assertThat(v.get(Country.INDONESIA)).isEqualTo(2);
	}

	@Test
	void everyOptionCollectsExactlyWhatWasOrdered() {
		Map<Country, Integer> orders = orders(3000, 3000, 3000, 3000);
		for (Plan plan : Planner.plan(master(3), orders)) {
			Map<Country, Integer> loaded = new EnumMap<>(Country.class);
			plan.ships().forEach(s -> s.trips().forEach(t -> t.stops()
					.forEach(st -> loaded.merge(st.country(), st.loadK(), Integer::sum))));
			assertThat(loaded).as(plan.label()).isEqualTo(orders);
			assertThat(plan.totalCost()).isEqualTo(plan.productionCost() + plan.shippingCost());
		}
	}

	@Test
	void eachCountryIsVisitedByOneShipOnly() {
		for (Plan plan : Planner.plan(master(4), orders(5000, 5000, 5000, 5000))) {
			Map<Country, Integer> owner = new EnumMap<>(Country.class);
			plan.ships().forEach(s -> s.trips().forEach(t -> t.stops().forEach(st -> {
				Integer prev = owner.put(st.country(), s.no());
				assertThat(prev == null || prev == s.no()).as(plan.label() + " " + st.country()).isTrue();
			})));
		}
	}

	@Test
	void productionCostIsCellsTimesUnitCost() {
		Plan plan = Planner.plan(master(3), orders(1000, 0, 0, 0)).get(0);
		assertThat(plan.productionCost()).isEqualTo(1000L * 1000 * 700);
	}

	@Test
	void singleSmallOrderTakesProductionPlusRoundTrip() {
		// 중국 400K = 하루 최대 400K → 1일 생산, 한국-중국 1일 → 왕복 2일
		Plan plan = Planner.plan(master(1), orders(400, 0, 0, 0)).get(0);
		assertThat(plan.ships()).hasSize(1);
		Stop stop = plan.ships().get(0).trips().get(0).stops().get(0);
		assertThat(stop.arriveDay()).isEqualTo(1);
		assertThat(plan.totalDays()).isEqualTo(2);
		assertThat(plan.shippingCost()).isEqualTo(2 * 100_000_000L);
	}

	@Test
	void shipDepartsLateSoItDoesNotWaitAtPort() {
		// 베트남 2000K / 200K일 = 10일 생산. 한국-베트남 5일 → 5일차 출항, 10일차 도착, 15일차 귀환
		Plan plan = Planner.plan(master(1), orders(0, 0, 2000, 0)).get(0);
		var trip = plan.ships().get(0).trips().get(0);
		assertThat(trip.departDay()).isEqualTo(5);
		assertThat(trip.stops().get(0).arriveDay()).isEqualTo(10);
		assertThat(plan.totalDays()).isEqualTo(15);
		assertThat(plan.shippingCost()).isEqualTo(10 * 100_000_000L);
	}

	@Test
	void overStockLimitOrderIsCollectedInSeveralRoundTrips() {
		// 일본 4500K, 재고 상한 2000K → 3번 방문(2000, 2000, 500)
		Plan plan = Planner.plan(master(1), orders(0, 4500, 0, 0)).get(0);
		List<Integer> loads = plan.ships().get(0).trips().stream().map(t -> t.stops().get(0).loadK()).toList();
		assertThat(loads).containsExactly(2000, 2000, 500);
		assertThat(plan.stock().get(Country.JAPAN)[plan.totalDays()]).isZero();
	}

	@Test
	void productionStopsAtStockLimitUntilPickup() {
		Plan plan = Planner.plan(master(1), orders(0, 4500, 0, 0)).get(0);
		int limit = 2000;
		for (int s : plan.stock().get(Country.JAPAN)) {
			assertThat(s).isLessThanOrEqualTo(limit);
		}
	}

	@Test
	void costOptionIsNotMoreExpensiveAndFastOptionIsNotSlower() {
		List<Plan> plans = Planner.plan(master(3), orders(3000, 3000, 3000, 3000));
		Plan cost = plans.get(0);
		Plan fast = plans.get(1);
		Plan near = plans.get(2);
		assertThat(cost.shippingCost()).isLessThanOrEqualTo(fast.shippingCost()).isLessThanOrEqualTo(near.shippingCost());
		assertThat(fast.totalDays()).isLessThanOrEqualTo(cost.totalDays()).isLessThanOrEqualTo(near.totalDays());
	}

	/** E2E-013b(HJ-S14-08): 배 1척, 5000/2000/0/8000 에서 가장 빠르게(47일)가 가까운 곳부터(45일)보다 느렸다. */
	@Test
	void fastOptionIsNotSlowerThanNearForReportedCase() {
		List<Plan> plans = Planner.plan(master(1), orders(5000, 2000, 0, 8000));
		assertThat(plans.get(1).totalDays()).isLessThanOrEqualTo(plans.get(2).totalDays());
	}

	/** 배 1~4척, 나라별 주문 조합 전체에서 가장 빠르게는 어느 안보다 느리지 않고 운항비 최소는 어느 안보다 비싸지 않다. */
	@Test
	void fastAndCostOptionsAreBestForEveryShipAndOrderCombination() {
		int[] amounts = {0, 2000, 5000, 8000};
		for (int ships = 1; ships <= 4; ships++) {
			for (int c : amounts) {
				for (int j : amounts) {
					for (int v : amounts) {
						for (int i : amounts) {
							if (c + j + v + i == 0) {
								continue;
							}
							List<Plan> plans = Planner.plan(master(ships), orders(c, j, v, i));
							Plan cost = plans.get(0);
							Plan fast = plans.get(1);
							Plan near = plans.get(2);
							String input = "배 %d척 %d/%d/%d/%d".formatted(ships, c, j, v, i);
							assertThat(fast.totalDays()).as(input + " FAST ≤ COST 일수").isLessThanOrEqualTo(cost.totalDays());
							assertThat(fast.totalDays()).as(input + " FAST ≤ NEAR 일수").isLessThanOrEqualTo(near.totalDays());
							assertThat(cost.shippingCost()).as(input + " COST ≤ FAST 운항비").isLessThanOrEqualTo(fast.shippingCost());
							assertThat(cost.shippingCost()).as(input + " COST ≤ NEAR 운항비").isLessThanOrEqualTo(near.shippingCost());
						}
					}
				}
			}
		}
	}

	@Test
	void usesAtMostAvailableShips() {
		for (Plan plan : Planner.plan(master(2), orders(3000, 3000, 3000, 3000))) {
			assertThat(plan.ships().size()).isLessThanOrEqualTo(2);
		}
	}

	@Test
	void rejectsEmptyOrder() {
		assertThatThrownBy(() -> Planner.plan(master(3), orders(0, 0, 0, 0)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void defaultOrderSummary() {
		for (Plan plan : Planner.plan(master(3), orders(3000, 3000, 3000, 3000))) {
			System.out.printf("%s: %d일, 생산 %.2f억, 운항 %.2f억, 합계 %.2f억, 배 %d척%n", plan.label(), plan.totalDays(),
					plan.productionCost() / 1e8, plan.shippingCost() / 1e8, plan.totalCost() / 1e8,
					plan.ships().size());
			plan.ships().forEach(s -> s.trips().forEach(t -> System.out.println("  " + s.no() + "호 출항 "
					+ t.departDay() + " → " + t.stops().stream()
							.map(st -> st.country().getLabel() + "(" + st.arriveDay() + "일," + st.loadK() + ")").toList()
					+ " → 귀환 " + t.returnDay())));
		}
	}
}
