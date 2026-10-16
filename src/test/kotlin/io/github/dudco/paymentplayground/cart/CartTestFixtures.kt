package io.github.dudco.paymentplayground.cart

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** 테스트에서 시각을 직접 움직이기 위한 시계. */
class MutableClock(var now: Instant) : Clock() {
	override fun instant(): Instant = now

	override fun getZone(): ZoneId = ZoneOffset.UTC

	override fun withZone(zone: ZoneId): Clock = this
}

/** `key-1`, `key-2`, ... 순서로 멱등키를 발급한다. */
class SequentialIdempotencyKeyGenerator : IdempotencyKeyGenerator {
	private var sequence = 0

	override fun generate(): String = "key-${++sequence}"
}

/**
 * 메모리 맵으로 동작하는 테스트용 저장소. `@Component`를 붙이지 않는다.
 * `save`는 `clock` 기준 30분 뒤를 만료 시각으로 돌려주고 `find`도 같은 값을 돌려준다.
 */
class InMemoryCartRepository(private val clock: Clock) : CartRepository {
	private val carts = mutableMapOf<String, StoredCart>()

	/** `true`면 [delete]가 예외를 던진다. 삭제 실패를 흉내 낼 때 쓴다. */
	var failOnDelete: Boolean = false

	override fun find(customerId: String): StoredCart? = carts[customerId]

	override fun save(cart: Cart): Instant {
		val expiresAt = clock.instant().plus(TTL)
		carts[cart.customerId] = StoredCart(cart, expiresAt)
		return expiresAt
	}

	override fun delete(customerId: String) {
		if (failOnDelete) throw IllegalStateException("장바구니 삭제 실패(테스트)")
		carts.remove(customerId)
	}

	fun contains(customerId: String): Boolean = carts.containsKey(customerId)

	fun clear() {
		carts.clear()
		failOnDelete = false
	}

	private companion object {
		val TTL: Duration = Duration.ofMinutes(30)
	}
}
