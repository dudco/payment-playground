package io.github.dudco.paymentplayground.cart

import com.jayway.jsonpath.JsonPath
import io.github.dudco.paymentplayground.customer.CustomerWebConfig
import io.github.dudco.paymentplayground.customer.HeaderCurrentCustomerResolver
import io.github.dudco.paymentplayground.product.FixedProductRepository
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 테스트 클래스 안의 설정은 컴포넌트 스캔에서 빠지므로 [Fixtures]를 `@Import`로 직접 등록한다.
 * 슬라이스 컨텍스트가 테스트 사이에 재사용되므로 저장소는 매 테스트 전에 비우고,
 * 멱등키는 값이 아니라 같음·다름만 단언한다.
 */
@WebMvcTest(controllers = [CartController::class])
@Import(
	CartService::class, FixedProductRepository::class, CustomerWebConfig::class, HeaderCurrentCustomerResolver::class,
	CartControllerTest.Fixtures::class,
)
class CartControllerTest(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val repository: InMemoryCartRepository,
) {

	@BeforeEach
	fun setUp() = repository.clear()

	@TestConfiguration
	class Fixtures {
		@Bean fun clock(): Clock = MutableClock(Instant.parse("2026-10-13T11:20:30Z"))

		@Bean fun keyGenerator(): IdempotencyKeyGenerator = SequentialIdempotencyKeyGenerator()

		@Bean fun cartRepository(clock: Clock): InMemoryCartRepository = InMemoryCartRepository(clock)
	}

	@Test
	fun `장바구니가 없으면 빈 장바구니를 반환한다`() {
		get()
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.customerId").value("customer-001"))
			.andExpect(jsonPath("$.idempotencyKey").value(nullValue()))
			.andExpect(jsonPath("$.lines.length()").value(0))
			.andExpect(jsonPath("$.totalAmount").value(0))
			.andExpect(jsonPath("$.expiresAt").value(nullValue()))
	}

	@Test
	fun `첫 저장은 멱등키와 계산된 금액, 정렬된 라인, 만료 시각을 반환한다`() {
		post("""{"lines":[{"productId":"cake","quantity":1},{"productId":"americano","quantity":2}]}""")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.idempotencyKey").isNotEmpty())
			.andExpect(jsonPath("$.lines[0].productId").value("americano"))
			.andExpect(jsonPath("$.lines[0].productName").value("아메리카노"))
			.andExpect(jsonPath("$.lines[0].unitPrice").value(4500))
			.andExpect(jsonPath("$.lines[0].lineAmount").value(9000))
			.andExpect(jsonPath("$.lines[1].productId").value("cake"))
			.andExpect(jsonPath("$.totalAmount").value(15000))
			.andExpect(jsonPath("$.expiresAt").value("2026-10-13T11:50:30Z"))
	}

	@Test
	fun `잘못된 요청은 400 VALIDATION_ERROR다`() {
		listOf(
			"""{"lines":[{"productId":"cake","quantity":0}]}""",
			"""{"lines":[{"productId":"cake","quantity":100}]}""",
			"""{"lines":[{"productId":"","quantity":1}]}""",
			"""{"lines":[{"productId":"cake","quantity":1},{"productId":"cake","quantity":2}]}""",
			"""{}""",
			"""{"lines":null}""",
			"""{"lines":[null]}""",
		).forEach { body ->
			post(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
		}
		mockMvc.perform(MockMvcRequestBuilders.get("/cart"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
	}

	@Test
	fun `추가·수량 변경·제거를 전체 교체로 반영하고 멱등키는 유지한다`() {
		val first = post("""{"lines":[{"productId":"americano","quantity":2},{"productId":"cake","quantity":1}]}""")
			.andExpect(status().isOk())
		val second = post("""{"lines":[{"productId":"americano","quantity":3},{"productId":"latte","quantity":1}]}""")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.lines[*].productId").value(contains("americano", "latte")))
			.andExpect(jsonPath("$.lines[0].quantity").value(3))
			.andExpect(jsonPath("$.totalAmount").value(18500))

		assertEquals(keyOf(first), keyOf(second))
	}

	@Test
	fun `같은 본문을 다시 보내도 멱등키와 라인이 그대로다`() {
		val body = """{"lines":[{"productId":"americano","quantity":2},{"productId":"cake","quantity":1}]}"""
		val first = post(body).andExpect(status().isOk())
		val second = post(body).andExpect(status().isOk())

		assertEquals(keyOf(first), keyOf(second))
		assertEquals(
			JsonPath.read<Any>(first.andReturn().response.contentAsString, "$.lines"),
			JsonPath.read<Any>(second.andReturn().response.contentAsString, "$.lines"),
		)
	}

	@Test
	fun `빈 lines 저장은 장바구니를 비우고 다시 담으면 다른 멱등키가 발급된다`() {
		val body = """{"lines":[{"productId":"americano","quantity":1}]}"""
		val first = post(body).andExpect(status().isOk())
		post("""{"lines":[]}""")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.idempotencyKey").value(nullValue()))
			.andExpect(jsonPath("$.lines.length()").value(0))
		val third = post(body).andExpect(status().isOk())

		assertNotEquals(keyOf(first), keyOf(third))
	}

	@Test
	fun `없는 상품이 섞이면 404 PRODUCT_NOT_FOUND이고 기존 장바구니가 그대로다`() {
		post("""{"lines":[{"productId":"americano","quantity":1}]}""").andExpect(status().isOk())
		post("""{"lines":[{"productId":"cake","quantity":1},{"productId":"unknown","quantity":1}]}""")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))

		get()
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.lines[*].productId").value(contains("americano")))
	}

	@Test
	fun `수량 경계값 1과 99는 허용된다`() {
		post("""{"lines":[{"productId":"cake","quantity":1},{"productId":"latte","quantity":99}]}""")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.lines[0].quantity").value(1))
			.andExpect(jsonPath("$.lines[1].quantity").value(99))
	}

	@Test
	fun `다른 고객의 장바구니와 멱등키에 영향을 주지 않는다`() {
		val body = """{"lines":[{"productId":"americano","quantity":1}]}"""
		val first = post(body, "customer-001").andExpect(status().isOk())
		val second = post(body, "customer-002").andExpect(status().isOk())
		assertNotEquals(keyOf(first), keyOf(second))

		post("""{"lines":[]}""", "customer-001").andExpect(status().isOk())

		get("customer-002")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.idempotencyKey").value(keyOf(second)))
			.andExpect(jsonPath("$.lines[*].productId").value(contains("americano")))
	}

	private fun keyOf(result: ResultActions): String =
		JsonPath.read<String>(result.andReturn().response.contentAsString, "$.idempotencyKey")

	private fun get(customerId: String = "customer-001"): ResultActions =
		mockMvc.perform(MockMvcRequestBuilders.get("/cart").header("X-Customer-ID", customerId))

	private fun post(body: String, customerId: String = "customer-001"): ResultActions =
		mockMvc.perform(
			MockMvcRequestBuilders.post("/cart").header("X-Customer-ID", customerId)
				.contentType(MediaType.APPLICATION_JSON).content(body),
		)
}
