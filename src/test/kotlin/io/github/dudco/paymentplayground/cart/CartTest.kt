package io.github.dudco.paymentplayground.cart

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CartTest {
	private val t0 = Instant.parse("2026-10-13T11:20:30Z")
	private val t1 = Instant.parse("2026-10-13T11:25:00Z")

	@Test
	fun `create는 라인을 상품 ID 오름차순으로 두고 생성·수정 시각을 같게 한다`() {
		val cart = Cart.create("customer-001", "key-1", mapOf("latte" to 1, "americano" to 2), t0)

		assertEquals(listOf("americano", "latte"), cart.lines.keys.toList())
		assertEquals(t0, cart.createdAt)
		assertEquals(t0, cart.updatedAt)
	}

	@Test
	fun `replaceLines는 멱등키와 createdAt을 유지하고 라인과 updatedAt을 바꾼다`() {
		val cart = Cart.create("customer-001", "key-1", mapOf("americano" to 2), t0)

		val replaced = cart.replaceLines(mapOf("cake" to 1), t1)

		assertEquals("key-1", replaced.idempotencyKey)
		assertEquals(t0, replaced.createdAt)
		assertEquals(t1, replaced.updatedAt)
		assertEquals(mapOf("cake" to 1), replaced.lines)
	}

	@Test
	fun `수량 경계값 1과 99는 허용하고 범위 밖과 빈 라인은 IllegalArgumentException이다`() {
		Cart.create("c", "k", mapOf("a" to 1, "b" to 99), t0)

		assertFailsWith<IllegalArgumentException> { Cart.create("c", "k", mapOf("a" to 0), t0) }
		assertFailsWith<IllegalArgumentException> { Cart.create("c", "k", mapOf("a" to 100), t0) }
		assertFailsWith<IllegalArgumentException> { Cart.create("c", "k", emptyMap(), t0) }
	}
}
