package io.github.dudco.paymentplayground.cart

import io.github.dudco.paymentplayground.product.MockProductCatalog
import io.github.dudco.paymentplayground.support.InvalidRequestException
import io.github.dudco.paymentplayground.support.NotFoundException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class CartServiceTest {
	private val clock = MutableClock(Instant.parse("2026-10-13T11:20:30Z"))
	private val repository = InMemoryCartRepository(clock)
	private val service = CartService(repository, MockProductCatalog(), SequentialIdempotencyKeyGenerator(), clock)

	@Test
	fun `장바구니가 없으면 빈 응답이다`() {
		assertEquals(CartView("customer-001", null, emptyList(), 0, null), service.get("customer-001"))
	}

	@Test
	fun `첫 저장은 멱등키를 발급하고 카탈로그 가격으로 금액을 계산한다`() {
		val view = service.save("customer-001", listOf(CartLineCommand("cake", 1), CartLineCommand("americano", 2)))

		assertEquals("key-1", view.idempotencyKey)
		assertEquals(
			listOf(
				CartLineView("americano", "아메리카노", 4500, 2, 9000),
				CartLineView("cake", "케이크", 6000, 1, 6000),
			),
			view.lines,
		)
		assertEquals(15000L, view.totalAmount)
		assertEquals(Instant.parse("2026-10-13T11:50:30Z"), view.expiresAt)
	}

	@Test
	fun `이어지는 저장은 멱등키와 createdAt을 유지하고 라인을 교체한다`() {
		service.save("customer-001", listOf(CartLineCommand("americano", 2), CartLineCommand("cake", 1)))
		clock.now = Instant.parse("2026-10-13T11:25:00Z")

		val view = service.save("customer-001", listOf(CartLineCommand("americano", 3), CartLineCommand("latte", 1)))

		assertEquals("key-1", view.idempotencyKey)
		assertEquals(listOf("americano" to 3, "latte" to 1), view.lines.map { it.productId to it.quantity })
		val stored = assertNotNull(repository.find("customer-001")).cart
		assertEquals(Instant.parse("2026-10-13T11:20:30Z"), stored.createdAt)
		assertEquals(Instant.parse("2026-10-13T11:25:00Z"), stored.updatedAt)
	}

	@Test
	fun `빈 라인 저장은 장바구니를 지우고 다시 담으면 새 멱등키가 발급된다`() {
		service.save("customer-001", listOf(CartLineCommand("cookie", 1)))

		assertEquals(CartView("customer-001", null, emptyList(), 0, null), service.save("customer-001", emptyList()))
		assertFalse(repository.contains("customer-001"))
		assertEquals("key-2", service.save("customer-001", listOf(CartLineCommand("cookie", 1))).idempotencyKey)
	}

	@Test
	fun `없는 상품이 섞이면 PRODUCT_NOT_FOUND이고 기존 장바구니가 그대로다`() {
		service.save("customer-001", listOf(CartLineCommand("americano", 1)))

		val error = assertFailsWith<NotFoundException> {
			service.save("customer-001", listOf(CartLineCommand("cake", 1), CartLineCommand("unknown", 1)))
		}

		assertEquals("PRODUCT_NOT_FOUND", error.code)
		assertEquals(mapOf("americano" to 1), assertNotNull(repository.find("customer-001")).cart.lines)
	}

	@Test
	fun `같은 상품이 두 번 있으면 InvalidRequestException이다`() {
		assertFailsWith<InvalidRequestException> {
			service.save("customer-001", listOf(CartLineCommand("cake", 1), CartLineCommand("cake", 2)))
		}
		assertFalse(repository.contains("customer-001"))
	}

	@Test
	fun `고객마다 장바구니와 멱등키가 분리된다`() {
		service.save("customer-001", listOf(CartLineCommand("americano", 1)))
		service.save("customer-002", listOf(CartLineCommand("cake", 2)))

		service.save("customer-001", emptyList())

		val other = service.get("customer-002")
		assertEquals("key-2", other.idempotencyKey)
		assertEquals(12000L, other.totalAmount)
	}

	@Test
	fun `카탈로그에서 사라진 상품의 라인은 응답과 금액에서 제외한다`() {
		repository.save(Cart.create("customer-001", "key-x", mapOf("americano" to 1, "discontinued" to 5), clock.now))

		val view = service.get("customer-001")

		assertEquals(listOf("americano"), view.lines.map { it.productId })
		assertEquals(4500L, view.totalAmount)
	}
}
