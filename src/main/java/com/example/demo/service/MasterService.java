package com.example.demo.service;

import com.example.demo.entity.Country;
import com.example.demo.entity.Factory;
import com.example.demo.entity.Route;
import com.example.demo.entity.Setting;
import com.example.demo.repository.FactoryRepository;
import com.example.demo.repository.RouteRepository;
import com.example.demo.repository.SettingRepository;
import com.example.demo.simulation.Master;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MasterService {

	public static final long WON_PER_EOK = 100_000_000L;
	private static final int MAX_SHIPS = 4;
	/** 공장 하루 운영비 기본값(억 원)과 입력 상한(억 원) */
	public static final long DEFAULT_OPERATING_COST_WON = WON_PER_EOK * 10;
	public static final long MAX_OPERATING_COST_EOK = 1_000;

	private final FactoryRepository factories;
	private final RouteRepository routes;
	private final SettingRepository settings;

	public MasterService(FactoryRepository factories, RouteRepository routes, SettingRepository settings) {
		this.factories = factories;
		this.routes = routes;
		this.settings = settings;
	}

	/**
	 * 마스터 데이터가 비어 있으면 초기값을 넣는다. 이미 있으면 하루 운영비가 비어 있는 공장만
	 * 기본값으로 채운다(운영비 도입 전에 만든 DB 용. 값이 있으면 덮어쓰지 않는다).
	 */
	@Transactional
	public void initDefaults() {
		if (settings.count() > 0) {
			for (Factory f : factories.findAll()) {
				if (f.getDailyOperatingCostWon() == null) {
					f.setDailyOperatingCostWon(DEFAULT_OPERATING_COST_WON);
				}
			}
			return;
		}
		factories.save(new Factory(Country.CHINA, 400, 4000, 700, DEFAULT_OPERATING_COST_WON));
		factories.save(new Factory(Country.JAPAN, 800, 2000, 900, DEFAULT_OPERATING_COST_WON));
		factories.save(new Factory(Country.VIETNAM, 200, 10000, 550, DEFAULT_OPERATING_COST_WON));
		factories.save(new Factory(Country.INDONESIA, 300, 7000, 450, DEFAULT_OPERATING_COST_WON));

		routes.save(new Route(Country.KOREA, Country.CHINA, 1));
		routes.save(new Route(Country.KOREA, Country.JAPAN, 2));
		routes.save(new Route(Country.KOREA, Country.VIETNAM, 5));
		routes.save(new Route(Country.KOREA, Country.INDONESIA, 7));
		routes.save(new Route(Country.CHINA, Country.JAPAN, 2));
		routes.save(new Route(Country.CHINA, Country.VIETNAM, 4));
		routes.save(new Route(Country.CHINA, Country.INDONESIA, 6));
		routes.save(new Route(Country.JAPAN, Country.VIETNAM, 5));
		routes.save(new Route(Country.JAPAN, Country.INDONESIA, 7));
		routes.save(new Route(Country.VIETNAM, Country.INDONESIA, 2));

		settings.save(new Setting(3, WON_PER_EOK));
	}

	@Transactional(readOnly = true)
	public Master currentMaster() {
		Setting s = settings.findById(Setting.SINGLETON_ID)
				.orElseThrow(() -> new IllegalStateException("마스터 데이터가 초기화되지 않았습니다"));
		List<Master.FactoryParam> fs = factories.findAll().stream()
				.sorted(Comparator.comparing(Factory::getCountry))
				// 운영비 도입 전에 만든 DB 는 initDefaults 가 보정하기 전까지 값이 비어 있을 수 있다. 보정과 같은 기본값을 쓴다.
				.map(f -> new Master.FactoryParam(f.getCountry(), f.getMaxDaily(), f.getStockLimit(), f.getUnitCost(),
						f.getDailyOperatingCostWon() != null ? f.getDailyOperatingCostWon() : DEFAULT_OPERATING_COST_WON))
				.toList();
		List<Master.RouteParam> rs = routes.findAll().stream()
				.sorted(Comparator.comparing(Route::getFromCountry).thenComparing(Route::getToCountry))
				.map(r -> new Master.RouteParam(r.getFromCountry(), r.getToCountry(), r.getDays()))
				.toList();
		return new Master(s.getMasterVersion(), s.getShipCount(), s.getShipDailyCostWon(), fs, rs);
	}

	/** 화면 입력칸 이름 → 값 (운항비는 억 원 단위 문자열) */
	public static Map<String, String> toForm(Master m) {
		Map<String, String> form = new HashMap<>();
		for (Master.FactoryParam f : m.factories()) {
			form.put("maxDaily_" + f.country(), String.valueOf(f.maxDaily()));
			form.put("stockLimit_" + f.country(), String.valueOf(f.stockLimit()));
			form.put("unitCost_" + f.country(), String.valueOf(f.unitCost()));
			form.put("operatingCost_" + f.country(), eokText(f.dailyOperatingCostWon()));
		}
		for (Master.RouteParam r : m.routes()) {
			form.put(routeKey(r.from(), r.to()), String.valueOf(r.days()));
		}
		form.put("shipCount", String.valueOf(m.shipCount()));
		form.put("shipDailyCost", eokText(m.shipDailyCostWon()));
		return form;
	}

	private static String eokText(long won) {
		return new BigDecimal(won).divide(BigDecimal.valueOf(WON_PER_EOK)).stripTrailingZeros().toPlainString();
	}

	/** 항로 입력칸 이름 (from, to 는 ordinal 순) */
	public static String routeKey(Country a, Country b) {
		return a.ordinal() < b.ordinal() ? "days_" + a + "_" + b : "days_" + b + "_" + a;
	}

	/**
	 * 폼 값을 검증하고 저장한다. 올바르지 않은 값이 하나라도 있으면 아무것도 저장하지 않고
	 * 이유를 모아 {@link BusinessException} 으로 알린다.
	 */
	@Transactional
	public void updateMaster(Map<String, String> form) {
		List<String> errors = new ArrayList<>();

		int shipCount = parseInt(form, "shipCount", "배 척수", 1, MAX_SHIPS, errors);
		long shipCostWon = 0;
		String costText = form.getOrDefault("shipDailyCost", "").trim();
		try {
			BigDecimal eok = new BigDecimal(costText);
			BigDecimal won = eok.multiply(BigDecimal.valueOf(WON_PER_EOK));
			if (eok.signum() <= 0) {
				errors.add("척당 일 운항비는 0보다 커야 합니다");
			} else if (won.stripTrailingZeros().scale() > 0) {
				errors.add("척당 일 운항비는 원 단위로 떨어지는 값이어야 합니다");
			} else {
				shipCostWon = won.longValueExact();
			}
		} catch (NumberFormatException | ArithmeticException e) {
			errors.add("척당 일 운항비(억 원)는 숫자로 입력하세요");
		}

		Map<Country, int[]> factoryValues = new EnumMap<>(Country.class);
		Map<Country, Long> unitCosts = new EnumMap<>(Country.class);
		Map<Country, Long> operatingCosts = new EnumMap<>(Country.class);
		for (Country c : Country.factories()) {
			String name = c.getLabel();
			int max = parseInt(form, "maxDaily_" + c, name + " 하루 최대 생산량", 1, 1_000_000, errors);
			int limit = parseInt(form, "stockLimit_" + c, name + " 재고 상한", 1, 1_000_000, errors);
			int cost = parseInt(form, "unitCost_" + c, name + " 셀당 원가", 1, 100_000_000, errors);
			factoryValues.put(c, new int[] {max, limit});
			unitCosts.put(c, (long) cost);
			operatingCosts.put(c, parseOperatingCost(form, "operatingCost_" + c, name + " 하루 운영비", errors));
		}

		List<Country> all = List.of(Country.values());
		Map<String, Integer> days = new HashMap<>();
		for (int i = 0; i < all.size(); i++) {
			for (int j = i + 1; j < all.size(); j++) {
				String key = routeKey(all.get(i), all.get(j));
				String label = all.get(i).getLabel() + "-" + all.get(j).getLabel() + " 항해 일수";
				days.put(key, parseInt(form, key, label, 1, 365, errors));
			}
		}

		if (!errors.isEmpty()) {
			throw new BusinessException(errors);
		}

		for (Country c : Country.factories()) {
			Factory f = factories.findById(c).orElseThrow();
			f.setMaxDaily(factoryValues.get(c)[0]);
			f.setStockLimit(factoryValues.get(c)[1]);
			f.setUnitCost(unitCosts.get(c));
			f.setDailyOperatingCostWon(operatingCosts.get(c));
		}
		for (Route r : routes.findAll()) {
			r.setDays(days.get(routeKey(r.getFromCountry(), r.getToCountry())));
		}
		Setting s = settings.findById(Setting.SINGLETON_ID).orElseThrow();
		s.setShipCount(shipCount);
		s.setShipDailyCostWon(shipCostWon);
		s.bumpMasterVersion();
	}

	/** 억 원 단위 입력을 원 단위 정수로 바꾼다. 0 초과 {@link #MAX_OPERATING_COST_EOK}억 원 이하, 원 단위로 떨어져야 한다. */
	private static long parseOperatingCost(Map<String, String> form, String key, String label, List<String> errors) {
		String text = form.getOrDefault(key, "").trim().replace(",", "");
		try {
			BigDecimal eok = new BigDecimal(text);
			if (eok.signum() <= 0) {
				errors.add("%s는 0보다 커야 합니다".formatted(label));
			} else if (eok.compareTo(BigDecimal.valueOf(MAX_OPERATING_COST_EOK)) > 0) {
				errors.add("%s는 %,d억 원 이하여야 합니다".formatted(label, MAX_OPERATING_COST_EOK));
			} else {
				BigDecimal won = eok.multiply(BigDecimal.valueOf(WON_PER_EOK));
				if (won.stripTrailingZeros().scale() > 0) {
					errors.add("%s는 원 단위로 떨어지는 값이어야 합니다".formatted(label));
				} else {
					return won.longValueExact();
				}
			}
		} catch (NumberFormatException e) {
			errors.add("%s(억 원)는 숫자로 입력하세요".formatted(label));
		}
		return DEFAULT_OPERATING_COST_WON;
	}

	private static int parseInt(Map<String, String> form, String key, String label, int min, int max,
			List<String> errors) {
		String text = form.getOrDefault(key, "").trim().replace(",", "");
		try {
			int v = Integer.parseInt(text);
			if (v < min || v > max) {
				errors.add("%s은(는) %d 이상 %d 이하의 정수여야 합니다".formatted(label, min, max));
			}
			return v;
		} catch (NumberFormatException e) {
			errors.add("%s은(는) 정수로 입력하세요".formatted(label));
			return min;
		}
	}

}
