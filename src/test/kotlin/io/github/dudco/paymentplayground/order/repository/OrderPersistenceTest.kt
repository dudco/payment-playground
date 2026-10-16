package io.github.dudco.paymentplayground.order.repository

import io.github.dudco.paymentplayground.order.domain.Order
import io.github.dudco.paymentplayground.order.domain.OrderLine
import io.github.dudco.paymentplayground.order.domain.OrderStatus
import io.github.dudco.paymentplayground.support.DatabaseCleaner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@SpringBootTest
class OrderPersistenceTest(
	@Autowired private val orderRepository: OrderRepository,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val transactionTemplate: TransactionTemplate,
	@Autowired private val databaseCleaner: DatabaseCleaner,
) {
	private val now = Instant.parse("2026-10-14T03:10:00Z")

	@BeforeEach
	fun setUp() = databaseCleaner.clean()

	@Test
	fun `주문과 라인을 저장하고 다시 읽으면 같은 값이고 라인은 상품 ID 오름차순이다`() {
		val id = assertNotNull(orderRepository.save(order()).id)

		transactionTemplate.executeWithoutResult { _ ->
			val found = assertNotNull(orderRepository.findByIdempotencyKey("key-1"))
			assertEquals(id, found.id)
			assertEquals("customer-001", found.customerId)
			assertEquals(OrderStatus.CREATED, found.status)
			assertEquals(15000L, found.totalAmount)
			assertEquals(now, found.createdAt)
			assertEquals(now, found.updatedAt)
			assertEquals(
				listOf(Triple("americano", 4500L, 2), Triple("cake", 6000L, 1)),
				found.lines.map { Triple(it.productId, it.unitPrice, it.quantity) },
			)
		}
		assertEquals("2026-10-14T03:10:00Z", jdbc.queryForObject("SELECT created_at FROM orders", String::class.java))
		assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM order_lines WHERE order_id = ?", Int::class.java, id))
	}

	@Test
	fun `없는 멱등키는 null이다`() {
		assertNull(orderRepository.findByIdempotencyKey("unknown"))
	}

	@Test
	fun `DB 제약이 잘못된 행을 거절한다`() {
		insertOrder("o1", "key-1")

		assertFailsWith<DataAccessException> { insertOrder("o2", "key-1") }
		assertFailsWith<DataAccessException> { insertOrder("o3", "key-3", status = "UNKNOWN") }
		assertFailsWith<DataAccessException> { insertLine("l1", "o1", unitPrice = 6000, quantity = 0, lineAmount = 0) }
		assertFailsWith<DataAccessException> { insertLine("l2", "o1", unitPrice = 6000, quantity = 100, lineAmount = 600000) }
		assertFailsWith<DataAccessException> { insertLine("l3", "o1", unitPrice = 6000, quantity = 2, lineAmount = 6000) }
		insertLine("l4", "o1", unitPrice = 6000, quantity = 2, lineAmount = 12000)
		assertFailsWith<DataAccessException> { insertLine("l5", "o1", unitPrice = 6000, quantity = 1, lineAmount = 6000) }
	}

	@Test
	fun `DB에 상품 ID 역순으로 저장된 라인도 productId 오름차순으로 읽힌다`() {
		insertOrder("or1", "key-r")
		insertLine("lr1", "or1", unitPrice = 5000, quantity = 1, lineAmount = 5000, productId = "latte", productName = "카페라테")
		insertLine("lr2", "or1", unitPrice = 4500, quantity = 1, lineAmount = 4500, productId = "americano", productName = "아메리카노")

		transactionTemplate.executeWithoutResult { _ ->
			val found = assertNotNull(orderRepository.findByIdempotencyKey("key-r"))
			assertEquals(listOf("americano", "latte"), found.lines.map { it.productId })
		}
	}

	private fun order() = Order(
		idempotencyKey = "key-1",
		customerId = "customer-001",
		lines = listOf(
			OrderLine("cake", "케이크", 6000, 1),
			OrderLine("americano", "아메리카노", 4500, 2),
		),
		now = now,
	)

	private fun insertOrder(id: String, key: String, status: String = "CREATED") {
		jdbc.update(
			"INSERT INTO orders (id, idempotency_key, customer_id, status, total_amount, created_at, updated_at) " +
				"VALUES (?, ?, 'customer-001', ?, 0, '2026-10-14T03:10:00Z', '2026-10-14T03:10:00Z')",
			id, key, status,
		)
	}

	private fun insertLine(
		id: String,
		orderId: String,
		unitPrice: Long,
		quantity: Int,
		lineAmount: Long,
		productId: String = "cake",
		productName: String = "케이크",
	) {
		jdbc.update(
			"INSERT INTO order_lines (id, order_id, product_id, product_name, unit_price, quantity, line_amount) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?)",
			id, orderId, productId, productName, unitPrice, quantity, lineAmount,
		)
	}
}
