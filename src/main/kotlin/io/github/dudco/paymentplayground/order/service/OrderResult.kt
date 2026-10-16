package io.github.dudco.paymentplayground.order.service

import io.github.dudco.paymentplayground.order.domain.Order
import io.github.dudco.paymentplayground.order.domain.OrderStatus
import java.time.Instant

data class OrderLineResult(
	val productId: String,
	val productName: String,
	val unitPrice: Long,
	val quantity: Int,
	val lineAmount: Long,
)

data class OrderResult(
	val orderId: String,
	val customerId: String,
	val idempotencyKey: String,
	val status: OrderStatus,
	val lines: List<OrderLineResult>,
	val totalAmount: Long,
	val createdAt: Instant,
)

/** 트랜잭션 안에서 호출해야 지연 로딩되는 `lines`를 읽을 수 있다. */
internal fun Order.toResult() = OrderResult(
	orderId = checkNotNull(id),
	customerId = customerId,
	idempotencyKey = idempotencyKey,
	status = status,
	lines = lines.map { OrderLineResult(it.productId, it.productName, it.unitPrice, it.quantity, it.lineAmount) },
	totalAmount = totalAmount,
	createdAt = createdAt,
)
