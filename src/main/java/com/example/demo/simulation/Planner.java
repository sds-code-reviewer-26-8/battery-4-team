package com.example.demo.simulation;

import com.example.demo.entity.Country;
import com.example.demo.simulation.Master.FactoryParam;
import com.example.demo.simulation.Plan.ShipPlan;
import com.example.demo.simulation.Plan.Stop;
import com.example.demo.simulation.Plan.Trip;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 주문 수량으로부터 경로 3가지(총비용 최소 / 가장 빠르게 / 가까운 곳부터)를 계산한다.
 *
 * <p>규칙 요약
 * <ul>
 * <li>0일차에 모든 공장이 동시에 생산을 시작한다. 하루 생산 = min(하루 최대, 재고 상한 - 재고, 남은 주문)</li>
 * <li>배는 도착 시점의 재고를 모두 가져간다. 한 나라는 한 척의 배만 방문한다.</li>
 * <li>한 나라의 방문 횟수는 올림(주문 / 재고 상한). 마지막 방문 전까지는 재고 상한만큼, 마지막은 나머지를 싣는다.</li>
 * <li>여러 번 수거할 때는 한국에 들러 내려놓고 다시 출항한다(왕복을 나눈다).</li>
 * <li>배는 재고가 채워지는 날에 맞춰 늦게 출항한다. 한국에서 대기는 무료.</li>
 * <li>공장 운영비 = 하루 운영비 × 운영 일수(주문일부터 그 공장의 마지막 수거일까지). 주문 없는 공장은 0.</li>
 * </ul>
 */
public final class Planner {

	public static final String COST = "COST";
	public static final String FAST = "FAST";
	public static final String NEAR = "NEAR";

	public static final int MAX_TOTAL_K = 100_000;

	private Planner() {
	}

	/** 나라별 필요 방문 횟수 = 올림(주문 / 재고 상한). 주문이 0이면 0. */
	public static Map<Country, Integer> visits(Master master, Map<Country, Integer> orders) {
		Map<Country, Integer> result = new EnumMap<>(Country.class);
		for (Country c : Country.factories()) {
			int q = orders.getOrDefault(c, 0);
			int limit = master.factory(c).stockLimit();
			result.put(c, q <= 0 ? 0 : (q + limit - 1) / limit);
		}
		return result;
	}

	public static List<Plan> plan(Master master, Map<Country, Integer> orders) {
		List<Country> active = Country.factories().stream().filter(c -> orders.getOrDefault(c, 0) > 0).toList();
		if (active.isEmpty()) {
			throw new IllegalArgumentException("주문 수량이 없습니다");
		}
		Map<Country, int[]> amounts = amounts(master, orders, active);

		Candidate cost = null;
		Candidate fast = null;
		int maxShips = Math.min(master.shipCount(), active.size());
		for (List<List<Country>> partition : partitions(active, maxShips)) {
			List<List<Country>> groups = sortGroups(master, partition);
			for (List<List<List<Country>>> shipTrips : tripStructures(master, groups, amounts)) {
				Candidate cand = schedule(master, orders, amounts, shipTrips);
				if (cost == null || cand.shipping + cand.operating < cost.shipping + cost.operating
						|| (cand.shipping + cand.operating == cost.shipping + cost.operating
								&& cand.totalDays < cost.totalDays)) {
					cost = cand;
				}
				if (fast == null || cand.totalDays < fast.totalDays
						|| (cand.totalDays == fast.totalDays && cand.shipping < fast.shipping)) {
					fast = cand;
				}
			}
		}
		Candidate near = nearest(master, orders, amounts, active, maxShips);

		return List.of(
				toPlan(master, orders, COST, "총비용 최소", cost),
				toPlan(master, orders, FAST, "가장 빠르게", fast),
				toPlan(master, orders, NEAR, "가까운 곳부터", near));
	}

	// ---------------------------------------------------------------- 후보 생성

	/** 나라별 방문 때마다 싣는 양(K). 마지막 방문 전까지는 재고 상한, 마지막은 나머지. */
	private static Map<Country, int[]> amounts(Master master, Map<Country, Integer> orders, List<Country> active) {
		Map<Country, int[]> result = new EnumMap<>(Country.class);
		for (Country c : active) {
			int q = orders.get(c);
			int limit = master.factory(c).stockLimit();
			int v = (q + limit - 1) / limit;
			int[] a = new int[v];
			for (int j = 0; j < v - 1; j++) {
				a[j] = limit;
			}
			a[v - 1] = q - (v - 1) * limit;
			result.put(c, a);
		}
		return result;
	}

	/** 나라들을 최대 maxGroups 개의 묶음(= 배 한 척이 담당)으로 나누는 모든 방법 */
	private static List<List<List<Country>>> partitions(List<Country> items, int maxGroups) {
		List<List<List<Country>>> out = new ArrayList<>();
		partition(items, 0, new ArrayList<>(), maxGroups, out);
		return out;
	}

	private static void partition(List<Country> items, int idx, List<List<Country>> cur, int maxGroups,
			List<List<List<Country>>> out) {
		if (idx == items.size()) {
			out.add(cur.stream().<List<Country>>map(ArrayList::new).toList());
			return;
		}
		Country c = items.get(idx);
		int existing = cur.size();
		for (int i = 0; i < existing; i++) {
			List<Country> g = cur.get(i);
			g.add(c);
			partition(items, idx + 1, cur, maxGroups, out);
			g.remove(g.size() - 1);
		}
		if (cur.size() < maxGroups) {
			List<Country> g = new ArrayList<>(List.of(c));
			cur.add(g);
			partition(items, idx + 1, cur, maxGroups, out);
			cur.remove(cur.size() - 1);
		}
	}

	/** 한국에서 가까운 나라를 가진 묶음이 앞(1호)에 오도록 정렬 */
	private static List<List<Country>> sortGroups(Master master, List<List<Country>> groups) {
		return groups.stream()
				.sorted(Comparator
						.comparingInt((List<Country> g) -> g.stream()
								.mapToInt(c -> master.days(Country.KOREA, c)).min().orElse(0))
						.thenComparingInt(g -> g.stream().mapToInt(Enum::ordinal).min().orElse(0)))
				.toList();
	}

	private enum Pattern {
		FRONT, BACK, SPREAD
	}

	/**
	 * 묶음별 왕복 구성 후보를 모두 만든다. 방문 횟수가 왕복 수보다 적은 나라는 어느 왕복에 들를지
	 * (앞쪽 / 뒤쪽 / 고르게) 패턴을 바꿔 가며 만든다. 각 왕복 안의 순서는 항해 일수가 가장 짧은 순서.
	 */
	private static List<List<List<List<Country>>>> tripStructures(Master master, List<List<Country>> groups,
			Map<Country, int[]> amounts) {
		List<List<List<List<Country>>>> perGroupOptions = new ArrayList<>();
		for (List<Country> group : groups) {
			perGroupOptions.add(groupStructures(master, group, amounts));
		}
		List<List<List<List<Country>>>> result = new ArrayList<>();
		combine(perGroupOptions, 0, new ArrayList<>(), result);
		return result;
	}

	private static void combine(List<List<List<List<Country>>>> options, int idx,
			List<List<List<Country>>> cur, List<List<List<List<Country>>>> out) {
		if (idx == options.size()) {
			out.add(new ArrayList<>(cur));
			return;
		}
		for (List<List<Country>> opt : options.get(idx)) {
			cur.add(opt);
			combine(options, idx + 1, cur, out);
			cur.remove(cur.size() - 1);
		}
	}

	private static List<List<List<Country>>> groupStructures(Master master, List<Country> group,
			Map<Country, int[]> amounts) {
		int trips = group.stream().mapToInt(c -> amounts.get(c).length).max().orElse(0);
		List<Country> flexible = group.stream().filter(c -> amounts.get(c).length < trips).toList();
		List<List<List<Country>>> result = new ArrayList<>();
		int combos = (int) Math.pow(Pattern.values().length, flexible.size());
		for (int k = 0; k < combos; k++) {
			Map<Country, Pattern> chosen = new EnumMap<>(Country.class);
			int rest = k;
			for (Country c : flexible) {
				chosen.put(c, Pattern.values()[rest % Pattern.values().length]);
				rest /= Pattern.values().length;
			}
			List<List<Country>> tripStops = new ArrayList<>();
			for (int t = 0; t < trips; t++) {
				tripStops.add(new ArrayList<>());
			}
			for (Country c : group) {
				int v = amounts.get(c).length;
				for (int t : tripIndexes(trips, v, chosen.getOrDefault(c, Pattern.FRONT))) {
					tripStops.get(t).add(c);
				}
			}
			result.add(tripStops.stream().map(stops -> shortestOrder(master, stops)).toList());
			// 항해 일수가 가장 짧은 순서가 전체 소요 일수까지 가장 짧다는 보장은 없다(방문 순서가 재고가 차는
			// 시점을 바꾼다). 가까운 나라부터 도는 순서도 후보에 넣어 "가장 빠르게"가 "가까운 곳부터"보다 느리지 않게 한다.
			List<List<Country>> nearFirst = tripStops.stream().map(stops -> nearFirstOrder(master, stops)).toList();
			if (!nearFirst.equals(result.get(result.size() - 1))) {
				result.add(nearFirst);
			}
		}
		return result;
	}

	private static int[] tripIndexes(int trips, int visits, Pattern pattern) {
		int[] idx = new int[visits];
		for (int i = 0; i < visits; i++) {
			idx[i] = switch (pattern) {
				case FRONT -> i;
				case BACK -> trips - visits + i;
				case SPREAD -> visits == 1 ? (trips - 1) / 2
						: (int) Math.round(i * (trips - 1) / (double) (visits - 1));
			};
		}
		return idx;
	}

	/** 한국 출발 → 모든 나라 → 한국 도착의 항해 일수가 가장 짧은 방문 순서 */
	private static List<Country> shortestOrder(Master master, List<Country> stops) {
		List<Country> best = null;
		int bestDays = Integer.MAX_VALUE;
		for (List<Country> perm : permutations(stops)) {
			int d = loopDays(master, perm);
			if (d < bestDays) {
				bestDays = d;
				best = perm;
			}
		}
		return best;
	}

	/** 한국에서 가까운 나라부터 방문하는 순서 ('가까운 곳부터' 안과 같은 규칙) */
	private static List<Country> nearFirstOrder(Master master, List<Country> stops) {
		return stops.stream()
				.sorted(Comparator.comparingInt((Country c) -> master.days(Country.KOREA, c))
						.thenComparingInt(Enum::ordinal))
				.toList();
	}

	private static int loopDays(Master master, List<Country> order) {
		int d = 0;
		Country prev = Country.KOREA;
		for (Country c : order) {
			d += master.days(prev, c);
			prev = c;
		}
		return d + master.days(prev, Country.KOREA);
	}

	private static List<List<Country>> permutations(List<Country> items) {
		List<List<Country>> out = new ArrayList<>();
		permute(new ArrayList<>(items), 0, out);
		return out;
	}

	private static void permute(List<Country> a, int k, List<List<Country>> out) {
		if (k == a.size()) {
			out.add(new ArrayList<>(a));
			return;
		}
		for (int i = k; i < a.size(); i++) {
			java.util.Collections.swap(a, k, i);
			permute(a, k + 1, out);
			java.util.Collections.swap(a, k, i);
		}
	}

	/** 가까운 곳부터: 한국에서 가까운 나라를 차례로 배에 돌려 가며 배정하고, 왕복마다 가까운 나라부터 방문 */
	private static Candidate nearest(Master master, Map<Country, Integer> orders, Map<Country, int[]> amounts,
			List<Country> active, int ships) {
		List<Country> byDistance = active.stream()
				.sorted(Comparator.comparingInt((Country c) -> master.days(Country.KOREA, c))
						.thenComparingInt(Enum::ordinal))
				.toList();
		List<List<Country>> groups = new ArrayList<>();
		for (int i = 0; i < ships; i++) {
			groups.add(new ArrayList<>());
		}
		for (int i = 0; i < byDistance.size(); i++) {
			groups.get(i % ships).add(byDistance.get(i));
		}
		List<List<List<Country>>> shipTrips = new ArrayList<>();
		for (List<Country> group : groups) {
			int trips = group.stream().mapToInt(c -> amounts.get(c).length).max().orElse(0);
			List<List<Country>> tripStops = new ArrayList<>();
			for (int t = 0; t < trips; t++) {
				List<Country> stops = new ArrayList<>();
				for (Country c : group) {
					if (t < amounts.get(c).length) {
						stops.add(c);
					}
				}
				tripStops.add(stops);
			}
			shipTrips.add(tripStops);
		}
		return schedule(master, orders, amounts, shipTrips);
	}

	// ---------------------------------------------------------------- 일정 계산

	private record Candidate(List<ShipPlan> ships, int totalDays, long shipping, long operating,
			Map<Country, Integer> operatingDays) {
	}

	/**
	 * 배별 왕복 구성이 정해졌을 때 출항일을 계산한다. 각 방문지에서 재고가 필요한 양만큼 찰 때까지의
	 * 날짜를 구해, 기다리지 않고 도착하도록 가능한 한 늦게 출항한다(한국 대기는 무료).
	 */
	private static Candidate schedule(Master master, Map<Country, Integer> orders, Map<Country, int[]> amounts,
			List<List<List<Country>>> shipTrips) {
		List<ShipPlan> ships = new ArrayList<>();
		int totalDays = 0;
		long shipping = 0;
		Map<Country, Integer> operatingDays = new EnumMap<>(Country.class);
		int no = 1;
		for (List<List<Country>> trips : shipTrips) {
			Map<Country, Integer> lastPickup = new EnumMap<>(Country.class);
			Map<Country, Integer> visited = new EnumMap<>(Country.class);
			List<Trip> planned = new ArrayList<>();
			int free = 0;
			int sail = 0;
			for (List<Country> stops : trips) {
				int[] offset = new int[stops.size()];
				int off = 0;
				Country prev = Country.KOREA;
				for (int i = 0; i < stops.size(); i++) {
					off += master.days(prev, stops.get(i));
					offset[i] = off;
					prev = stops.get(i);
				}
				int loop = off + master.days(prev, Country.KOREA);

				int depart = free;
				for (int i = 0; i < stops.size(); i++) {
					Country c = stops.get(i);
					int amount = amounts.get(c)[visited.getOrDefault(c, 0)];
					int ready = lastPickup.getOrDefault(c, 0) + ceilDiv(amount, master.factory(c).maxDaily());
					depart = Math.max(depart, ready - offset[i]);
				}
				List<Stop> planStops = new ArrayList<>();
				for (int i = 0; i < stops.size(); i++) {
					Country c = stops.get(i);
					int v = visited.getOrDefault(c, 0);
					int arrive = depart + offset[i];
					planStops.add(new Stop(c, arrive, arrive, amounts.get(c)[v]));
					lastPickup.put(c, arrive);
					operatingDays.merge(c, arrive, Math::max);
					visited.put(c, v + 1);
				}
				int back = depart + loop;
				planned.add(new Trip(depart, back, planStops));
				sail += loop;
				free = back;
			}
			ships.add(new ShipPlan(no++, planned, sail));
			totalDays = Math.max(totalDays, free);
			shipping += (long) sail * master.shipDailyCostWon();
		}
		long operating = 0;
		for (Map.Entry<Country, Integer> e : operatingDays.entrySet()) {
			operating += e.getValue() * master.factory(e.getKey()).dailyOperatingCostWon();
		}
		return new Candidate(ships, totalDays, shipping, operating, operatingDays);
	}

	private static int ceilDiv(int a, int b) {
		return (a + b - 1) / b;
	}

	// ---------------------------------------------------------------- 일별 생산 계산

	private static Plan toPlan(Master master, Map<Country, Integer> orders, String type, String label, Candidate cand) {
		Map<Country, int[]> stock = new EnumMap<>(Country.class);
		Map<Country, int[]> production = new EnumMap<>(Country.class);
		long productionCost = 0;
		List<Plan.FactoryOperation> operations = new ArrayList<>();
		for (Country c : Country.factories()) {
			int q = orders.getOrDefault(c, 0);
			int[] st = new int[cand.totalDays + 1];
			int[] pr = new int[Math.max(cand.totalDays, 0)];
			if (q > 0) {
				simulateFactory(master.factory(c), q, pickups(cand, c), st, pr);
				productionCost += q * 1000L * master.factory(c).unitCost();
			}
			stock.put(c, st);
			production.put(c, pr);
			int opDays = cand.operatingDays.getOrDefault(c, 0);
			operations.add(new Plan.FactoryOperation(c, opDays, opDays * master.factory(c).dailyOperatingCostWon()));
		}
		return new Plan(type, label, cand.totalDays, productionCost, cand.shipping, cand.operating,
				productionCost + cand.shipping + cand.operating, cand.ships, stock, production, operations);
	}

	private static Map<Integer, Integer> pickups(Candidate cand, Country c) {
		Map<Integer, Integer> result = new java.util.HashMap<>();
		for (ShipPlan ship : cand.ships) {
			for (Trip trip : ship.trips()) {
				for (Stop s : trip.stops()) {
					if (s.country() == c) {
						result.merge(s.arriveDay(), s.loadK(), Integer::sum);
					}
				}
			}
		}
		return result;
	}

	/** 하루씩 진행하며 생산·수거를 반영한다. 수거량이 그 시점 재고와 다르면 일정 계산에 오류가 있는 것이다. */
	private static void simulateFactory(FactoryParam f, int ordered, Map<Integer, Integer> pickups, int[] stock,
			int[] production) {
		int inStock = 0;
		int remaining = ordered;
		int days = production.length;
		for (int t = 0; t <= days; t++) {
			Integer load = pickups.get(t);
			if (load != null) {
				if (load != inStock) {
					throw new IllegalStateException(
							"%s %d일차 수거량(%d)이 재고(%d)와 다릅니다".formatted(f.country(), t, load, inStock));
				}
				inStock = 0;
			}
			stock[t] = inStock;
			if (t < days) {
				int made = Math.max(0, Math.min(f.maxDaily(), Math.min(f.stockLimit() - inStock, remaining)));
				production[t] = made;
				inStock += made;
				remaining -= made;
			}
		}
		if (remaining != 0 || inStock != 0) {
			throw new IllegalStateException("%s 주문 수량을 모두 수거하지 못했습니다".formatted(f.country()));
		}
	}

	/** 테스트·검증용: 경로에 쓰인 나라 집합 */
	static TreeSet<Country> visitedCountries(Plan plan) {
		TreeSet<Country> set = new TreeSet<>();
		plan.ships().forEach(s -> s.trips().forEach(t -> t.stops().forEach(st -> set.add(st.country()))));
		return set;
	}
}
