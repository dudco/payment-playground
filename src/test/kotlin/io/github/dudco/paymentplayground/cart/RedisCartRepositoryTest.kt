package io.github.dudco.paymentplayground.cart

import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RedisCartRepositoryTest {
	private val now = Instant.parse("2026-10-13T11:20:30Z")
	private val codec = CartRedisCodec(JsonMapper.builder().findAndAddModules().build())
	private val redis: StringRedisTemplate = Mockito.mock(StringRedisTemplate::class.java)

	@Suppress("UNCHECKED_CAST")
	private val ops: ValueOperations<String, String> = Mockito.mock(ValueOperations::class.java) as ValueOperations<String, String>
	private val repository = RedisCartRepository(redis, codec, CartProperties(Duration.ofMinutes(30)), Clock.fixed(now, ZoneOffset.UTC))
	private val cart = Cart.create("customer-001", "key-1", mapOf("americano" to 2), now)

	@BeforeEach
	fun setUp() {
		Mockito.`when`(redis.opsForValue()).thenReturn(ops)
	}

	@Test
	fun `저장은 cart 키에 JSON과 설정 TTL로 SET 한 번을 실행하고 만료 시각을 돌려준다`() {
		val expiresAt = repository.save(cart)

		Mockito.verify(ops).set("cart:customer-001", codec.encode(cart), Duration.ofMinutes(30))
		Mockito.verifyNoMoreInteractions(ops)
		assertEquals(Instant.parse("2026-10-13T11:50:30Z"), expiresAt)
	}

	@Test
	fun `조회는 GET과 TTL 확인만 하고 TTL을 연장하지 않는다`() {
		Mockito.`when`(ops.get("cart:customer-001")).thenReturn(codec.encode(cart))
		Mockito.`when`(redis.getExpire("cart:customer-001", TimeUnit.SECONDS)).thenReturn(1200L)

		val stored = repository.find("customer-001")

		assertEquals(StoredCart(cart, Instant.parse("2026-10-13T11:40:30Z")), stored)
		Mockito.verify(redis).opsForValue()
		Mockito.verify(ops).get("cart:customer-001")
		Mockito.verify(redis).getExpire("cart:customer-001", TimeUnit.SECONDS)
		Mockito.verifyNoMoreInteractions(redis, ops)
	}

	@Test
	fun `저장된 값이 없으면 null이다`() {
		Mockito.`when`(ops.get("cart:customer-001")).thenReturn(null)

		assertNull(repository.find("customer-001"))
	}

	@Test
	fun `삭제는 해당 고객의 키만 지운다`() {
		repository.delete("customer-001")

		Mockito.verify(redis).delete("cart:customer-001")
	}

	@Test
	fun `남은 TTL이 양수일 때만 만료 시각을 계산한다`() {
		assertEquals(now.plusSeconds(1200), RedisCartRepository.expiresAt(1200L, now))
		listOf(null, 0L, -1L, -2L).forEach { assertNull(RedisCartRepository.expiresAt(it, now), "ttl=$it") }
	}
}
