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
class GetOrderServiceTest(
	@Autowired private val placeOrderService: PlaceOrderService,
	@Autowired private val service: GetOrderService,
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
	fun `주문 후 가격이 바뀌어도 조회는 주문 시점 상품명·단가다`() {
		carts.saveDefaultCart()
		val placed = placeOrderService.place("customer-001", "key-1").order
		catalog.put(Product("americano", "아이스 아메리카노", 9999))

		val found = service.get("customer-001", placed.orderId)

		assertEquals(OrderLineResult("americano", "아메리카노", 4500, 2, 9000), found.lines.first { it.productId == "americano" })
		assertEquals(15000L, found.totalAmount)
	}

	@Test
	fun `없는 주문, 다른 고객의 주문, UUID가 아닌 ID는 ORDER_NOT_FOUND다`() {
		carts.saveDefaultCart()
		val placed = placeOrderService.place("customer-001", "key-1").order

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
