package io.github.dudco.paymentplayground.cart

import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * [Cart]와 Redis에 저장되는 JSON 문자열을 서로 변환한다.
 * 라인은 `{productId, quantity}` 배열로 저장하며 상품명·가격은 담지 않는다.
 */
@Component
class CartRedisCodec(private val jsonMapper: JsonMapper) {

	fun encode(cart: Cart): String = jsonMapper.writeValueAsString(CartDocument.from(cart))

	fun decode(json: String): Cart = jsonMapper.readValue(json, CartDocument::class.java).toCart()
}

private data class CartDocument(
	val customerId: String,
	val idempotencyKey: String,
	val lines: List<CartLineDocument>,
	val createdAt: Instant,
	val updatedAt: Instant,
) {
	fun toCart(): Cart = Cart(
		customerId,
		idempotencyKey,
		lines.associate { it.productId to it.quantity }.toSortedMap(),
		createdAt,
		updatedAt,
	)

	companion object {
		fun from(cart: Cart): CartDocument = CartDocument(
			cart.customerId,
			cart.idempotencyKey,
			cart.lines.map { (productId, quantity) -> CartLineDocument(productId, quantity) },
			cart.createdAt,
			cart.updatedAt,
		)
	}
}

private data class CartLineDocument(
	val productId: String,
	val quantity: Int,
)
