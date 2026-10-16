package io.github.dudco.paymentplayground.order.service

import io.github.dudco.paymentplayground.order.domain.orderNotFound
import io.github.dudco.paymentplayground.order.repository.OrderRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 주문 한 건 조회(주문 스펙 5.2). */
@Service
class GetOrderService(private val orderRepository: OrderRepository) {

	@Transactional(readOnly = true)
	fun get(customerId: String, orderId: String): OrderResult {
		val order = orderRepository.findById(orderId).orElse(null)
		if (order == null || order.customerId != customerId) throw orderNotFound()
		return order.toResult()
	}
}
