package io.github.dudco.paymentplayground.order

import io.github.dudco.paymentplayground.customer.CurrentCustomer
import io.github.dudco.paymentplayground.support.InvalidRequestException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/**
 * 주문 생성과 조회. 주문 내용은 요청 본문이 아니라 서버에 저장된 장바구니에서 만들어지므로
 * 본문 파라미터를 두지 않는다.
 */
@RestController
@RequestMapping("/orders")
class OrderController(private val orderService: OrderService) {

	/** 새로 만들면 201과 Location, 같은 키의 재호출이면 200으로 기존 주문을 돌려준다. */
	@PostMapping
	fun place(
		@CurrentCustomer customerId: String,
		@RequestHeader("Idempotency-Key") idempotencyKey: String,
	): ResponseEntity<OrderView> {
		val key = idempotencyKey.trim()
		if (key.isEmpty()) throw InvalidRequestException("Idempotency-Key 헤더가 비어 있습니다.")

		val placement = orderService.place(customerId, key)
		return if (placement.created) {
			ResponseEntity.created(URI.create("/orders/${placement.order.orderId}")).body(placement.order)
		} else {
			ResponseEntity.ok(placement.order)
		}
	}

	@GetMapping("/{orderId}")
	fun get(@CurrentCustomer customerId: String, @PathVariable orderId: String): OrderView =
		orderService.get(customerId, orderId)
}
