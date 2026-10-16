package io.github.dudco.paymentplayground.cart

import io.github.dudco.paymentplayground.product.ProductRepository
import io.github.dudco.paymentplayground.product.productNotFound
import io.github.dudco.paymentplayground.support.IdempotencyKeyGenerator
import io.github.dudco.paymentplayground.support.InvalidRequestException
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/** 장바구니에 담을 한 줄. 수량 범위 검증은 [Cart]와 컨트롤러가 맡는다. */
data class CartLineCommand(val productId: String, val quantity: Int)

/** 장바구니 조회·저장 결과. 장바구니가 없으면 멱등키와 만료 시각이 `null`이다. */
data class CartResult(
	val customerId: String,
	val idempotencyKey: String?,
	val lines: List<CartLineResult>,
	val totalAmount: Long,
	val expiresAt: Instant?,
)

data class CartLineResult(
	val productId: String,
	val productName: String,
	val unitPrice: Long,
	val quantity: Int,
	val lineAmount: Long,
)

/**
 * 장바구니 저장(전체 교체)과 조회.
 * 멱등키는 장바구니를 처음 만들 때 한 번 발급하고 장바구니가 존재하는 동안 유지한다.
 * 금액은 저장하지 않고 응답을 만들 때마다 카탈로그 가격으로 계산한다.
 */
@Service
class CartService(
	private val cartRepository: CartRepository,
	private val productRepository: ProductRepository,
	private val keyGenerator: IdempotencyKeyGenerator,
	private val clock: Clock,
) {
	fun get(customerId: String): CartResult {
		val stored = cartRepository.find(customerId) ?: return emptyResult(customerId)
		return toResult(stored.cart, stored.expiresAt)
	}

	fun save(customerId: String, lines: List<CartLineCommand>): CartResult {
		val productIds = lines.map { it.productId }
		if (productIds.toSet().size != productIds.size) {
			throw InvalidRequestException("같은 상품이 두 번 이상 있습니다.")
		}
		if (productIds.any { productRepository.findById(it) == null }) {
			throw productNotFound()
		}
		if (lines.isEmpty()) {
			cartRepository.delete(customerId)
			return emptyResult(customerId)
		}

		val now = clock.instant()
		val quantities = lines.associate { it.productId to it.quantity }
		val existing = cartRepository.find(customerId)?.cart
		val cart = existing?.replaceLines(quantities, now)
			?: Cart.create(customerId, keyGenerator.generate(), quantities, now)
		val expiresAt = cartRepository.save(cart)
		return toResult(cart, expiresAt)
	}

	private fun emptyResult(customerId: String) = CartResult(customerId, null, emptyList(), 0, null)

	/** 카탈로그에서 사라진 상품의 라인은 건너뛰어 응답과 총액에 포함하지 않는다. */
	private fun toResult(cart: Cart, expiresAt: Instant?): CartResult {
		val lineResults = cart.lines.mapNotNull { (productId, quantity) ->
			val product = productRepository.findById(productId) ?: return@mapNotNull null
			CartLineResult(product.id, product.name, product.price, quantity, product.price * quantity)
		}
		return CartResult(cart.customerId, cart.idempotencyKey, lineResults, lineResults.sumOf { it.lineAmount }, expiresAt)
	}
}
