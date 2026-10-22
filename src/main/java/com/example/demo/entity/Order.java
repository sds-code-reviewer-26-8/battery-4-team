package com.example.demo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;

@Entity
@Table(name = "orders")
public class Order {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private LocalDateTime confirmedAt;

	@Column(nullable = false)
	private String createdBy;

	/** 총 주문 수량(K) */
	private int totalK;

	private String routeType;

	private String routeLabel;

	private int totalDays;

	private long productionCost;

	private long shippingCost;

	private long totalCost;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OrderStatus status;

	@Column(length = 1000)
	private String memo;

	/** 확정 때 요청한 필요 납기일(일). null 이면 '가능한 빨리'. 계산에는 쓰지 않고 표시용으로만 쓴다. */
	@Column(nullable = true)
	private Integer dueDays;

	/** 확정 시점의 계획 스냅샷(주문 수량, 마스터 값, 경로, 일별 생산) JSON */
	@Lob
	@Column(nullable = false)
	private String planJson;

	@Version
	private long version;

	protected Order() {
	}

	public Order(LocalDateTime confirmedAt, String createdBy, int totalK, String routeType, String routeLabel,
			int totalDays, long productionCost, long shippingCost, long totalCost, String memo, Integer dueDays, String planJson) {
		this.confirmedAt = confirmedAt;
		this.createdBy = createdBy;
		this.totalK = totalK;
		this.routeType = routeType;
		this.routeLabel = routeLabel;
		this.totalDays = totalDays;
		this.productionCost = productionCost;
		this.shippingCost = shippingCost;
		this.totalCost = totalCost;
		this.memo = memo;
		this.dueDays = dueDays;
		this.planJson = planJson;
		this.status = OrderStatus.CONFIRMED;
	}

	public Long getId() {
		return id;
	}

	public LocalDateTime getConfirmedAt() {
		return confirmedAt;
	}

	public String getCreatedBy() {
		return createdBy;
	}

	public int getTotalK() {
		return totalK;
	}

	public String getRouteType() {
		return routeType;
	}

	public String getRouteLabel() {
		return routeLabel;
	}

	public int getTotalDays() {
		return totalDays;
	}

	public long getProductionCost() {
		return productionCost;
	}

	public long getShippingCost() {
		return shippingCost;
	}

	public long getTotalCost() {
		return totalCost;
	}

	public OrderStatus getStatus() {
		return status;
	}

	public void setStatus(OrderStatus status) {
		this.status = status;
	}

	public String getMemo() {
		return memo;
	}

	public Integer getDueDays() {
		return dueDays;
	}

	/** 소요 일수가 납기를 넘은 일수. 납기가 없거나 넘지 않으면 null */
	public Integer getDueOverDays() {
		return dueDays != null && totalDays > dueDays ? totalDays - dueDays : null;
	}

	public String getPlanJson() {
		return planJson;
	}

	public long getVersion() {
		return version;
	}

	/** 억 원 단위, 소수 2자리 */
	public String getTotalCostEok() {
		return eok(totalCost);
	}

	public String getProductionCostEok() {
		return eok(productionCost);
	}

	public String getShippingCostEok() {
		return eok(shippingCost);
	}

	private static String eok(long won) {
		return String.format("%.2f", won / 100_000_000.0);
	}
}
