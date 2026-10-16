package io.github.dudco.paymentplayground.order.service

import io.github.dudco.paymentplayground.cart.domain.Cart
import io.github.dudco.paymentplayground.cart.repository.InMemoryCartRepository
import io.github.dudco.paymentplayground.order.ORDER_TEST_NOW
import io.github.dudco.paymentplayground.order.OrderTestConfiguration
import io.github.dudco.paymentplayground.order.domain.OrderStatus
import io.github.dudco.paymentplayground.order.repository.OrderRepository
import io.github.dudco.paymentplayground.order.saveDefaultCart
import io.github.dudco.paymentplayground.product.domain.Product
import io.github.dudco.paymentplayground.product.repository.MutableProductRepository
import io.github.dudco.paymentplayground.support.ConflictException
import io.github.dudco.paymentplayground.support.DatabaseCleaner
import io.github.dudco.paymentplayground.support.MutableClock
import io.github.dudco.paymentplayground.support.NotFoundException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@Import(OrderTestConfiguration::class)
class OrderServiceTest(
	@Autowired private val service: OrderService,
	@Autowired private val orderRepository: OrderRepository,
	@Autowired private val carts: InMemoryCartRepository,
	@Autowired private val catalog: MutableProductRepository,
	@Autowired private val clock: MutableClock,
	@Autowired private val databaseCleaner: DatabaseCleaner,
) {
	@BeforeEach
	fun setUp() {
		databaseCleaner.clean()
		carts.clear()
		catalog.reset()
		clock.now = ORDER_TEST_NOW
	}

	@Test
	fun `장바구니로 주문을 만들고 커밋 후 장바구니를 지운다`() {
		carts.saveDefaultCart()

		val placement = service.place("customer-001", "key-1")

		assertTrue(placement.created)
		with(placement.order) {
			assertEquals("customer-001", customerId)
			assertEquals("key-1", idempotencyKey)
			assertEquals(OrderStatus.CREATED, status)
			assertEquals(
				listOf(
					OrderLineResult("americano", "아메리카노", 4500, 2, 9000),
					OrderLineResult("cake", "케이크", 6000, 1, 6000),
				),
				lines,
			)
			assertEquals(15000L, totalAmount)
			assertEquals(ORDER_TEST_NOW, createdAt)
		}
		assertEquals(1, orderRepository.count())
		assertFalse(carts.contains("customer-001"))
	}

	@Test
	fun `같은 키 재호출은 새 주문을 만들지 않고 기존 주문을 반환한다`() {
		carts.saveDefaultCart()
		val first = service.place("customer-001", "key-1")
		clock.now = ORDER_TEST_NOW.plusSeconds(60)

		val second = service.place("customer-001", "key-1")

		assertFalse(second.created)
		assertEquals(first.order, second.order)
		assertEquals(1, orderRepository.count())
	}

	@Test
	fun `재호출 때 같은 키의 장바구니가 남아 있으면 지우고 기존 주문을 반환한다`() {
		carts.saveDefaultCart()
		service.place("customer-001", "key-1")
		carts.save(Cart.create("customer-001", "key-1", mapOf("cookie" to 5), ORDER_TEST_NOW))

		val second = service.place("customer-001", "key-1")

		assertFalse(second.created)
		assertEquals(listOf("americano", "cake"), second.order.lines.map { it.productId })
		assertFalse(carts.contains("customer-001"))
	}

	@Test
	fun `재호출 때 장바구니가 새 키로 다시 생겼으면 지우지 않는다`() {
		carts.saveDefaultCart()
		service.place("customer-001", "key-1")
		carts.saveDefaultCart(key = "key-2")

		val second = service.place("customer-001", "key-1")

		assertFalse(second.created)
		assertTrue(carts.contains("customer-001"))
		assertEquals("key-2", carts.find("customer-001")?.cart?.idempotencyKey)
	}

	@Test
	fun `장바구니가 없으면 CART_NOT_FOUND이고 주문을 만들지 않는다`() {
		val e = assertFailsWith<NotFoundException> { service.place("customer-001", "key-1") }

		assertEquals("CART_NOT_FOUND", e.code)
		assertEquals(0, orderRepository.count())
	}

	@Test
	fun `장바구니 키와 다른 키는 IDEMPOTENCY_KEY_MISMATCH이고 장바구니는 그대로다`() {
		carts.saveDefaultCart(key = "key-1")

		val e = assertFailsWith<ConflictException> { service.place("customer-001", "key-2") }

		assertEquals("IDEMPOTENCY_KEY_MISMATCH", e.code)
		assertEquals(0, orderRepository.count())
		assertTrue(carts.contains("customer-001"))
	}

	@Test
	fun `다른 고객의 주문 키는 IDEMPOTENCY_KEY_MISMATCH다`() {
		carts.saveDefaultCart()
		service.place("customer-001", "key-1")
		carts.saveDefaultCart(customerId = "customer-002", key = "key-9")

		val e = assertFailsWith<ConflictException> { service.place("customer-002", "key-1") }

		assertEquals("IDEMPOTENCY_KEY_MISMATCH", e.code)
		assertEquals(1, orderRepository.count())
		assertTrue(carts.contains("customer-002"))
	}

	@Test
	fun `카탈로그에 없는 상품 라인은 빼고 주문한다`() {
		carts.saveDefaultCart()
		catalog.remove("cake")

		val placement = service.place("customer-001", "key-1")

		assertEquals(listOf("americano"), placement.order.lines.map { it.productId })
		assertEquals(9000L, placement.order.totalAmount)
	}

	@Test
	fun `주문 가능한 라인이 없으면 CART_NOT_FOUND다`() {
		carts.save(Cart.create("customer-001", "key-1", mapOf("cake" to 1), ORDER_TEST_NOW))
		catalog.remove("cake")

		val e = assertFailsWith<NotFoundException> { service.place("customer-001", "key-1") }

		assertEquals("CART_NOT_FOUND", e.code)
		assertEquals(0, orderRepository.count())
	}

	@Test
	fun `주문 생성이 실패하면 장바구니를 지우지 않는다`() {
		carts.saveDefaultCart()
		catalog.put(Product("americano", "아메리카노", -1))

		assertFailsWith<IllegalArgumentException> { service.place("customer-001", "key-1") }

		assertEquals(0, orderRepository.count())
		assertTrue(carts.contains("customer-001"))
	}

	@Test
	fun `장바구니 삭제가 실패해도 주문은 만들어진다`() {
		carts.saveDefaultCart()
		carts.failOnDelete = true

		val placement = service.place("customer-001", "key-1")

		assertTrue(placement.created)
		assertEquals(1, orderRepository.count())
	}

	@Test
	fun `재호출 때 장바구니 조회가 실패해도 기존 주문을 반환한다`() {
		carts.saveDefaultCart()
		val first = service.place("customer-001", "key-1")
		carts.failOnFind = true

		val second = service.place("customer-001", "key-1")

		assertFalse(second.created)
		assertEquals(first.order, second.order)
		assertEquals(1, orderRepository.count())
	}

	@Test
	fun `재호출 때 장바구니 삭제가 실패해도 기존 주문을 반환한다`() {
		carts.saveDefaultCart()
		val first = service.place("customer-001", "key-1")
		carts.save(Cart.create("customer-001", "key-1", mapOf("cookie" to 5), ORDER_TEST_NOW))
		carts.failOnDelete = true

		val second = service.place("customer-001", "key-1")

		assertFalse(second.created)
		assertEquals(first.order, second.order)
		assertEquals(1, orderRepository.count())
	}

	@Test
	fun `주문 후 가격이 바뀌어도 조회는 주문 시점 상품명·단가다`() {
		carts.saveDefaultCart()
		val placed = service.place("customer-001", "key-1").order
		catalog.put(Product("americano", "아이스 아메리카노", 9999))

		val found = service.get("customer-001", placed.orderId)

		assertEquals(OrderLineResult("americano", "아메리카노", 4500, 2, 9000), found.lines.first { it.productId == "americano" })
		assertEquals(15000L, found.totalAmount)
	}

	@Test
	fun `없는 주문, 다른 고객의 주문, UUID가 아닌 ID는 ORDER_NOT_FOUND다`() {
		carts.saveDefaultCart()
		val placed = service.place("customer-001", "key-1").order

		listOf(
			"customer-002" to placed.orderId,
			"customer-001" to UUID.randomUUID().toString(),
			"customer-001" to "not-a-uuid",
		).forEach { (customerId, orderId) ->
			val e = assertFailsWith<NotFoundException> { service.get(customerId, orderId) }
			assertEquals("ORDER_NOT_FOUND", e.code)
		}
	}
}
