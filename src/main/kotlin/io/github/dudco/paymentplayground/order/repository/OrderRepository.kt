package io.github.dudco.paymentplayground.order.repository

import io.github.dudco.paymentplayground.order.domain.Order
import org.springframework.data.jpa.repository.JpaRepository

interface OrderRepository : JpaRepository<Order, String> {
	fun findByIdempotencyKey(idempotencyKey: String): Order?
}
