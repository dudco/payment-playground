package io.github.dudco.paymentplayground.order

import io.github.dudco.paymentplayground.cart.CartRepository
import io.github.dudco.paymentplayground.cart.CartService
import io.github.dudco.paymentplayground.product.ProductRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
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

/** [created]가 `false`면 같은 멱등키로 이미 만들어진 주문을 다시 돌려준 것이다. */
data class PlaceOrderResult(val order: OrderResult, val created: Boolean)

private fun Order.toResult() = OrderResult(
	orderId = checkNotNull(id),
	customerId = customerId,
	idempotencyKey = idempotencyKey,
	status = status,
	lines = lines.map { OrderLineResult(it.productId, it.productName, it.unitPrice, it.quantity, it.lineAmount) },
	totalAmount = totalAmount,
	createdAt = createdAt,
)

@Service
class OrderService(
	private val orderRepository: OrderRepository,
	private val cartRepository: CartRepository,
	private val cartService: CartService,
	private val productRepository: ProductRepository,
	private val transactionTemplate: TransactionTemplate,
	private val clock: Clock,
) {
	/**
	 * 고객의 장바구니로 주문을 만든다. 같은 멱등키의 주문이 있으면 새로 만들지 않고 그 주문을 돌려준다.
	 *
	 * 장바구니는 주문이 커밋된 뒤에만 지우므로 이 메서드에는 `@Transactional`을 붙이지 않는다.
	 */
	fun place(customerId: String, idempotencyKey: String): PlaceOrderResult {
		val existing = transactionTemplate.execute {
			orderRepository.findByIdempotencyKey(idempotencyKey)?.let {
				if (it.customerId != customerId) throw idempotencyKeyMismatch()
				it.toResult()
			}
		}
		if (existing != null) {
			cleanUpCartIfSameKey(customerId, idempotencyKey, existing.orderId)
			return PlaceOrderResult(existing, false)
		}

		val cart = cartRepository.find(customerId)?.cart ?: throw cartNotFound()
		if (cart.idempotencyKey != idempotencyKey) throw idempotencyKeyMismatch()

		val lines = cart.lines.mapNotNull { (productId, quantity) ->
			productRepository.findById(productId)?.let { OrderLine(it.id, it.name, it.price, quantity) }
		}
		if (lines.isEmpty()) throw cartNotFound()

		val result = transactionTemplate.execute {
			orderRepository.save(Order(idempotencyKey, customerId, lines, clock.instant())).toResult()
		}!!

		deleteCartQuietly(customerId, result.orderId)
		return PlaceOrderResult(result, true)
	}

	@Transactional(readOnly = true)
	fun get(customerId: String, orderId: String): OrderResult {
		val order = orderRepository.findById(orderId).orElse(null)
		if (order == null || order.customerId != customerId) throw orderNotFound()
		return order.toResult()
	}

	/** 주문은 이미 확정됐으므로 장바구니 삭제 실패는 로그만 남긴다. 장바구니는 TTL로 사라진다. */
	private fun deleteCartQuietly(customerId: String, orderId: String) {
		try {
			cartService.clear(customerId)
		} catch (e: Exception) {
			log.warn("주문 {} 확정 후 장바구니 삭제에 실패했습니다.", orderId, e)
		}
	}

	/** 재호출 때 남은 장바구니를 정리한다. 새 키로 다시 생긴 장바구니는 건드리지 않는다. 실패해도 응답에 영향이 없다. */
	private fun cleanUpCartIfSameKey(customerId: String, idempotencyKey: String, orderId: String) {
		try {
			if (cartRepository.find(customerId)?.cart?.idempotencyKey == idempotencyKey) {
				cartService.clear(customerId)
			}
		} catch (e: Exception) {
			log.warn("주문 {} 재호출 때 장바구니 정리에 실패했습니다.", orderId, e)
		}
	}

	private companion object {
		val log = LoggerFactory.getLogger(OrderService::class.java)
	}
}
