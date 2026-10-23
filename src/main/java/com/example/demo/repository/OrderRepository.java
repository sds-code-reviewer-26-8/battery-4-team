package com.example.demo.repository;

import com.example.demo.entity.Order;
import com.example.demo.entity.OrderStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

	long countByStatus(OrderStatus status);

	List<Order> findByStatusOrderByIdDesc(OrderStatus status);

	List<Order> findAllByOrderByIdDesc();

	List<Order> findByCreatedBy(String createdBy);

	long countByCreatedBy(String createdBy);
}
