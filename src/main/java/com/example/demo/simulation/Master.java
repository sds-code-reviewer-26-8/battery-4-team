package com.example.demo.simulation;

import com.example.demo.entity.Country;
import java.util.List;

/** 시뮬레이션에 쓰는 마스터 데이터 스냅샷. 주문 확정 시 그대로 저장된다. */
public record Master(long version, int shipCount, long shipDailyCostWon, List<FactoryParam> factories,
		List<RouteParam> routes) {

	public record FactoryParam(Country country, int maxDaily, int stockLimit, long unitCost, long dailyOperatingCostWon) {
	}

	public record RouteParam(Country from, Country to, int days) {
	}

	public FactoryParam factory(Country country) {
		return factories.stream().filter(f -> f.country() == country).findFirst()
				.orElseThrow(() -> new IllegalStateException("공장 정보가 없습니다: " + country));
	}

	public int days(Country a, Country b) {
		if (a == b) {
			return 0;
		}
		return routes.stream()
				.filter(r -> (r.from() == a && r.to() == b) || (r.from() == b && r.to() == a))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("항로 정보가 없습니다: " + a + "-" + b))
				.days();
	}
}
