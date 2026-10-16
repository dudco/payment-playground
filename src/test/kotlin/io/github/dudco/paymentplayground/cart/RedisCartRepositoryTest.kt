package io.github.dudco.paymentplayground.cart

import org.junit.jupiter.api.BeforeEach
import org.mockito.ArgumentCaptor
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RedisCartRepositoryTest {
	private val now = Instant.parse("2026-10-13T11:20:30Z")
	private val redis: StringRedisTemplate = Mockito.mock(StringRedisTemplate::class.java)

	@Suppress("UNCHECKED_CAST")
	private val ops: ValueOperations<String, String> = Mockito.mock(ValueOperations::class.java) as ValueOperations<String, String>
	private val repository = RedisCartRepository(
		redis,
		JsonMapper.builder().findAndAddModules().build(),
		CartProperties(Duration.ofMinutes(30)),
		Clock.fixed(now, ZoneOffset.UTC),
	)
	private val cart = Cart.create(
		"customer-001", "6f1c2a52-6c0e-4c55-9a43-6a6f0f6a8f10",
		mapOf("americano" to 2, "cake" to 1), now,
	).replaceLines(mapOf("americano" to 2, "cake" to 1), Instant.parse("2026-10-13T11:25:00Z"))

	/** 장바구니 스펙 3장의 저장 형식 그대로 쓴 JSON. */
	private val storedJson = """{"customerId":"customer-001","idempotencyKey":"6f1c2a52-6c0e-4c55-9a43-6a6f0f6a8f10",""" +
		""""lines":[{"productId":"americano","quantity":2},{"productId":"cake","quantity":1}],""" +
		""""createdAt":"2026-10-13T11:20:30Z","updatedAt":"2026-10-13T11:25:00Z"}"""

	@BeforeEach
	fun setUp() {
		Mockito.`when`(redis.opsForValue()).thenReturn(ops)
	}

	/** `save`가 `SET`에 넘긴 JSON 값을 꺼낸다. */
	private fun savedJson(): String {
		val value = ArgumentCaptor.forClass(String::class.java)
		Mockito.verify(ops).set(Mockito.eq("cart:customer-001"), value.capture(), Mockito.eq(Duration.ofMinutes(30)))
		return value.value
	}

	@Test
	fun `저장은 cart 키에 JSON과 설정 TTL로 SET 한 번을 실행하고 만료 시각을 돌려준다`() {
		val expiresAt = repository.save(cart)

		savedJson()
		Mockito.verifyNoMoreInteractions(ops)
		assertEquals(Instant.parse("2026-10-13T11:50:30Z"), expiresAt)
	}

	@Test
	fun `저장한 JSON을 다시 읽으면 같은 Cart다`() {
		repository.save(cart)
		val json = savedJson()
		Mockito.`when`(ops.get("cart:customer-001")).thenReturn(json)

		assertEquals(cart, repository.find("customer-001")?.cart)
	}

	@Test
	fun `저장 JSON은 스펙 형식을 따르고 상품명·가격을 담지 않는다`() {
		repository.save(cart)
		val json = savedJson()

		assertTrue(""""lines":[{"productId":"americano","quantity":2},{"productId":"cake","quantity":1}]""" in json, json)
		assertTrue(""""createdAt":"2026-10-13T11:20:30Z"""" in json, json)
		assertTrue(""""updatedAt":"2026-10-13T11:25:00Z"""" in json, json)
		assertFalse("productName" in json || "price" in json || "Amount" in json, json)
	}

	@Test
	fun `스펙 형식으로 저장된 JSON을 읽는다`() {
		Mockito.`when`(ops.get("cart:customer-001")).thenReturn(storedJson)

		assertEquals(cart, repository.find("customer-001")?.cart)
	}

	@Test
	fun `조회는 GET과 TTL 확인만 하고 TTL을 연장하지 않는다`() {
		Mockito.`when`(ops.get("cart:customer-001")).thenReturn(storedJson)
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
