package io.github.dudco.paymentplayground.cart

import java.time.Instant

/** 저장된 장바구니와 만료 시각. 만료 시각을 알 수 없으면 `null`이다. */
data class StoredCart(
	val cart: Cart,
	val expiresAt: Instant?,
)

interface CartRepository {
	fun find(customerId: String): StoredCart?

	/** 장바구니를 저장하고 새 만료 시각을 반환한다. */
	fun save(cart: Cart): Instant

	fun delete(customerId: String)
}
