package io.github.dudco.paymentplayground.order

import org.springframework.data.jpa.repository.JpaRepository

interface OrderRepository : JpaRepository<Order, String> {
	fun findByIdempotencyKey(idempotencyKey: String): Order?
}
