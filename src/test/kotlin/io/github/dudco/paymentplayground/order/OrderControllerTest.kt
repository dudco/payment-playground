package io.github.dudco.paymentplayground.order

import com.jayway.jsonpath.JsonPath
import io.github.dudco.paymentplayground.cart.InMemoryCartRepository
import io.github.dudco.paymentplayground.cart.MutableClock
import io.github.dudco.paymentplayground.support.DatabaseCleaner
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
@Import(OrderTestConfiguration::class)
class OrderControllerTest(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val carts: InMemoryCartRepository,
	@Autowired private val catalog: MutableProductCatalog,
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
	fun `주문을 만들면 201, Location, 주문 본문을 반환한다`() {
		carts.saveDefaultCart()

		val result = place("key-1")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.orderId").isNotEmpty())
			.andExpect(jsonPath("$.customerId").value("customer-001"))
			.andExpect(jsonPath("$.idempotencyKey").value("key-1"))
			.andExpect(jsonPath("$.status").value("CREATED"))
			.andExpect(jsonPath("$.lines[0].productId").value("americano"))
			.andExpect(jsonPath("$.lines[0].productName").value("아메리카노"))
			.andExpect(jsonPath("$.lines[0].unitPrice").value(4500))
			.andExpect(jsonPath("$.lines[0].quantity").value(2))
			.andExpect(jsonPath("$.lines[0].lineAmount").value(9000))
			.andExpect(jsonPath("$.lines[1].productId").value("cake"))
			.andExpect(jsonPath("$.totalAmount").value(15000))
			.andExpect(jsonPath("$.createdAt").value("2026-10-14T03:10:00Z"))
			.andReturn()

		val orderId = JsonPath.read<String>(result.response.contentAsString, "$.orderId")
		assertEquals("/orders/$orderId", result.response.getHeader("Location"))
	}

	@Test
	fun `같은 키 재호출은 200과 같은 주문을 반환한다`() {
		carts.saveDefaultCart()
		val first = place("key-1").andExpect(status().isCreated()).andReturn()
		val orderId = JsonPath.read<String>(first.response.contentAsString, "$.orderId")

		place("key-1")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.orderId").value(orderId))
	}

	@Test
	fun `멱등키 앞뒤 공백은 제거한다`() {
		carts.saveDefaultCart()

		place(" key-1 ")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.idempotencyKey").value("key-1"))
	}

	@Test
	fun `요청 본문은 무시하고 장바구니로 주문한다`() {
		carts.saveDefaultCart()

		place("key-1", body = """{"lines":[{"productId":"cookie","quantity":5}]}""")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.lines[*].productId").value(contains("americano", "cake")))
	}

	@Test
	fun `헤더가 없거나 비면 400 VALIDATION_ERROR다`() {
		carts.saveDefaultCart()

		listOf(
			place(null),
			place("   "),
			place("key-1", customerId = null),
		).forEach {
			it.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
		}
	}

	@Test
	fun `장바구니가 없으면 404 CART_NOT_FOUND다`() {
		place("key-1")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CART_NOT_FOUND"))
	}

	@Test
	fun `키가 다르면 409 IDEMPOTENCY_KEY_MISMATCH이고 메시지에 키가 없다`() {
		carts.saveDefaultCart(key = "key-1")

		place("key-2")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_MISMATCH"))
			.andExpect(content().string(not(containsString("key-2"))))
	}

	@Test
	fun `본인 주문을 조회한다`() {
		carts.saveDefaultCart()
		val orderId = placeAndGetId("key-1")

		get(orderId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.orderId").value(orderId))
			.andExpect(jsonPath("$.totalAmount").value(15000))
			.andExpect(jsonPath("$.lines.length()").value(2))
	}

	@Test
	fun `다른 고객의 주문이나 없는 주문은 404 ORDER_NOT_FOUND다`() {
		carts.saveDefaultCart()
		val orderId = placeAndGetId("key-1")

		get(orderId, "customer-002")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
		get("not-a-uuid")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
	}

	private fun placeAndGetId(key: String): String {
		val result = place(key).andExpect(status().isCreated()).andReturn()
		return JsonPath.read(result.response.contentAsString, "$.orderId")
	}

	private fun place(key: String?, customerId: String? = "customer-001", body: String? = null): ResultActions =
		mockMvc.perform(
			post("/orders").apply {
				customerId?.let { header("X-Customer-ID", it) }
				key?.let { header("Idempotency-Key", it) }
				body?.let { contentType(MediaType.APPLICATION_JSON).content(it) }
			},
		)

	private fun get(orderId: String, customerId: String = "customer-001"): ResultActions =
		mockMvc.perform(MockMvcRequestBuilders.get("/orders/$orderId").header("X-Customer-ID", customerId))
}
