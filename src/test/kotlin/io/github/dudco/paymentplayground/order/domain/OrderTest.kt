package io.github.dudco.paymentplayground.order.domain

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OrderTest {
	private val now = Instant.parse("2026-10-14T03:10:00Z")

	@Test
	fun `라인 금액과 총액을 계산하고 라인을 상품 ID 오름차순으로 둔다`() {
		val order = Order(
			"key-1", "customer-001",
			listOf(OrderLine("cake", "케이크", 6000, 1), OrderLine("americano", "아메리카노", 4500, 2)),
			now,
		)

		assertEquals(listOf("americano", "cake"), order.lines.map { it.productId })
		assertEquals(listOf(9000L, 6000L), order.lines.map { it.lineAmount })
		assertEquals(15000L, order.totalAmount)
		assertEquals(OrderStatus.CREATED, order.status)
		assertEquals(now, order.createdAt)
		assertEquals(now, order.updatedAt)
		assertNull(order.id)
	}

	@Test
	fun `수량 경계값 1과 99, 단가 0은 허용하고 범위 밖과 빈 이름은 IllegalArgumentException이다`() {
		OrderLine("cake", "케이크", 0, 1)
		OrderLine("cake", "케이크", 6000, 99)

		assertFailsWith<IllegalArgumentException> { OrderLine("cake", "케이크", 6000, 0) }
		assertFailsWith<IllegalArgumentException> { OrderLine("cake", "케이크", 6000, 100) }
		assertFailsWith<IllegalArgumentException> { OrderLine("cake", "케이크", -1, 1) }
		assertFailsWith<IllegalArgumentException> { OrderLine(" ", "케이크", 6000, 1) }
		assertFailsWith<IllegalArgumentException> { OrderLine("cake", "", 6000, 1) }
	}

	@Test
	fun `빈 라인, 중복 상품, 빈 멱등키·고객 ID는 IllegalArgumentException이다`() {
		val line = { OrderLine("cake", "케이크", 6000, 1) }

		assertFailsWith<IllegalArgumentException> { Order("key-1", "customer-001", emptyList(), now) }
		assertFailsWith<IllegalArgumentException> { Order("key-1", "customer-001", listOf(line(), line()), now) }
		assertFailsWith<IllegalArgumentException> { Order(" ", "customer-001", listOf(line()), now) }
		assertFailsWith<IllegalArgumentException> { Order("key-1", "", listOf(line()), now) }
	}
}
