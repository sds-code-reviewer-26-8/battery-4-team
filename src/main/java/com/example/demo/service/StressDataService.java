package com.example.demo.service;

import com.example.demo.config.WebConfig;
import com.example.demo.entity.Order;
import com.example.demo.entity.OrderStatus;
import com.example.demo.repository.OrderRepository;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** 부하 시험이 만든 주문만 정리한다. stress 프로필에서만 만들어진다. */
@Service
@Profile(WebConfig.STRESS_PROFILE)
public class StressDataService {

	/** 부하 시험이 만든 주문의 작성자. 사람이 만든 주문과 구분한다. */
	public static final String USER = "stress";

	private final OrderRepository orders;
	private final OrderService orderService;

	public StressDataService(OrderRepository orders, OrderService orderService) {
		this.orders = orders;
		this.orderService = orderService;
	}

	public long countStressOrders() {
		return orders.countByCreatedBy(USER);
	}

	/**
	 * 작성자가 stress 인 주문을 취소해 지운다. 저장소에서 바로 지우지 않고 {@link OrderService#changeStatus} 로
	 * 취소하므로 주문은 삭제되고 상태 이력(확정 -> 취소)은 남는다(INTENT 원칙 9, 5.6).
	 * 그 사이 다른 쪽이 바꾼 주문(충돌·이미 완료)은 건너뛰고, 취소한 주문 수를 돌려준다. 다른 작성자의 주문은 건드리지 않는다.
	 */
	public int deleteStressOrders() {
		List<Order> mine = orders.findByCreatedBy(USER);
		int canceled = 0;
		for (Order o : mine) {
			try {
				orderService.changeStatus(o.getId(), OrderStatus.CANCELED, o.getVersion(), USER);
				canceled++;
			} catch (BusinessException e) {
				// 건너뛴다. 남은 주문은 health 의 stressOrders 로 확인할 수 있다.
			}
		}
		return canceled;
	}
}
