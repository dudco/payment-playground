package io.github.dudco.paymentplayground.order.controller

import io.github.dudco.paymentplayground.order.service.OrderLineResult
import io.github.dudco.paymentplayground.order.service.OrderResult
import java.time.Instant

/** 주문 응답. `status`는 주문 상태 이름(`CREATED` 등)이다. */
data class OrderResponse(
	val orderId: String,
	val customerId: String,
	val idempotencyKey: String,
	val status: String,
	val lines: List<OrderLineResponse>,
	val totalAmount: Long,
	val createdAt: Instant,
) {
	companion object {
		fun from(result: OrderResult) = OrderResponse(
			result.orderId,
			result.customerId,
			result.idempotencyKey,
			result.status.name,
			result.lines.map(OrderLineResponse::from),
			result.totalAmount,
			result.createdAt,
		)
	}
}

data class OrderLineResponse(
	val productId: String,
	val productName: String,
	val unitPrice: Long,
	val quantity: Int,
	val lineAmount: Long,
) {
	companion object {
		fun from(result: OrderLineResult) =
			OrderLineResponse(result.productId, result.productName, result.unitPrice, result.quantity, result.lineAmount)
	}
}
