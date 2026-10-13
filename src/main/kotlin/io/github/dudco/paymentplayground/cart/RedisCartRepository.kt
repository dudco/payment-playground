package io.github.dudco.paymentplayground.cart

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * 장바구니를 Redis 문자열 값(`cart:{customerId}` → JSON)으로 저장한다.
 * 저장할 때마다 TTL을 설정 값으로 새로 정하고, 조회는 TTL을 바꾸지 않는다.
 */
@Repository
class RedisCartRepository(
	private val redis: StringRedisTemplate,
	private val codec: CartRedisCodec,
	private val properties: CartProperties,
	private val clock: Clock,
) : CartRepository {

	override fun find(customerId: String): StoredCart? {
		val key = key(customerId)
		val json = redis.opsForValue().get(key) ?: return null
		val ttlSeconds = redis.getExpire(key, TimeUnit.SECONDS)
		return StoredCart(codec.decode(json), expiresAt(ttlSeconds, now()))
	}

	override fun save(cart: Cart): Instant {
		redis.opsForValue().set(key(cart.customerId), codec.encode(cart), properties.ttl)
		return now().plus(properties.ttl)
	}

	override fun delete(customerId: String) {
		redis.delete(key(customerId))
	}

	/** Redis TTL이 초 단위이므로 현재 시각도 초 단위로 맞춘다. */
	private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.SECONDS)

	companion object {
		fun key(customerId: String): String = "cart:$customerId"

		/** 남은 TTL이 양수일 때만 만료 시각을 계산한다. `null`·`0`·`-1`(TTL 없음)·`-2`(키 없음)이면 `null`이다. */
		fun expiresAt(ttlSeconds: Long?, now: Instant): Instant? =
			if (ttlSeconds != null && ttlSeconds > 0) now.plusSeconds(ttlSeconds) else null
	}
}
