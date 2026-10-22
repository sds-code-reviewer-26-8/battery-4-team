package com.example.demo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "factory")
public class Factory {

	@Id
	@Enumerated(EnumType.STRING)
	private Country country;

	/** 하루 최대 생산량(K) */
	@Column(nullable = false)
	private int maxDaily;

	/** 재고 상한(K) */
	@Column(nullable = false)
	private int stockLimit;

	/** 셀당 원가(원) */
	@Column(nullable = false)
	private long unitCost;

	/** 하루 운영비(원). 운영비 도입 전에 만든 DB 는 비어 있다가 첫 실행 때 기본값으로 채워진다. */
	private Long dailyOperatingCostWon;

	protected Factory() {
	}

	public Factory(Country country, int maxDaily, int stockLimit, long unitCost) {
		this.country = country;
		this.maxDaily = maxDaily;
		this.stockLimit = stockLimit;
		this.unitCost = unitCost;
	}

	public Factory(Country country, int maxDaily, int stockLimit, long unitCost, long dailyOperatingCostWon) {
		this(country, maxDaily, stockLimit, unitCost);
		this.dailyOperatingCostWon = dailyOperatingCostWon;
	}

	public Country getCountry() {
		return country;
	}

	public int getMaxDaily() {
		return maxDaily;
	}

	public void setMaxDaily(int maxDaily) {
		this.maxDaily = maxDaily;
	}

	public int getStockLimit() {
		return stockLimit;
	}

	public void setStockLimit(int stockLimit) {
		this.stockLimit = stockLimit;
	}

	public long getUnitCost() {
		return unitCost;
	}

	public void setUnitCost(long unitCost) {
		this.unitCost = unitCost;
	}

	public Long getDailyOperatingCostWon() {
		return dailyOperatingCostWon;
	}

	public void setDailyOperatingCostWon(Long dailyOperatingCostWon) {
		this.dailyOperatingCostWon = dailyOperatingCostWon;
	}
}
