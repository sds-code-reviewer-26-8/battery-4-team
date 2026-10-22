package com.example.demo.entity;

import java.util.List;

public enum OrderStatus {
	CONFIRMED("확정"),
	IN_PROGRESS("진행"),
	COMPLETED("완료"),
	CANCELED("취소");

	private final String label;

	OrderStatus(String label) {
		this.label = label;
	}

	public String getLabel() {
		return label;
	}

	public List<OrderStatus> next() {
		return switch (this) {
			case CONFIRMED -> List.of(IN_PROGRESS, CANCELED);
			case IN_PROGRESS -> List.of(COMPLETED, CANCELED);
			case COMPLETED, CANCELED -> List.of();
		};
	}

	/** 고객사 이름을 고칠 수 있는 상태(확정·진행)인가 */
	public boolean isCustomerEditable() {
		return this == CONFIRMED || this == IN_PROGRESS;
	}

	public boolean canChangeTo(OrderStatus target) {
		return next().contains(target);
	}
}
