package io.github.dudco.paymentplayground.order

import io.github.dudco.paymentplayground.cart.Cart
import io.github.dudco.paymentplayground.cart.InMemoryCartRepository
import io.github.dudco.paymentplayground.cart.MutableClock
import io.github.dudco.paymentplayground.product.FixedProductRepository
import io.github.dudco.paymentplayground.product.Product
import io.github.dudco.paymentplayground.product.ProductRepository
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Instant

val ORDER_TEST_NOW: Instant = Instant.parse("2026-10-14T03:10:00Z")

/** 테스트에서 상품을 바꾸거나 지울 수 있는 카탈로그. `@Component`를 붙이지 않는다. */
class MutableProductRepository : ProductRepository {
	private val products = linkedMapOf<String, Product>()

	init {
		reset()
	}

	/** `FixedProductRepository`의 5개 상품으로 되돌린다. */
	fun reset() {
		products.clear()
		FixedProductRepository().findAll().forEach { products[it.id] = it }
	}

	/** 같은 ID의 상품이 있으면 교체한다. */
	fun put(product: Product) {
		products[product.id] = product
	}

	fun remove(productId: String) {
		products.remove(productId)
	}

	override fun findAll(): List<Product> = products.values.toList()

	override fun findById(productId: String): Product? = products[productId]
}

/** 주문 통합 테스트용 Clock·장바구니 저장소·카탈로그. 테스트 클래스에서 `@Import`로 가져온다. */
@TestConfiguration
class OrderTestConfiguration {
	@Bean
	@Primary
	fun testClock(): MutableClock = MutableClock(ORDER_TEST_NOW)

	@Bean
	@Primary
	fun inMemoryCartRepository(testClock: MutableClock): InMemoryCartRepository =
		InMemoryCartRepository(testClock)

	@Bean
	@Primary
	fun mutableProductRepository(): MutableProductRepository = MutableProductRepository()
}

fun InMemoryCartRepository.saveDefaultCart(customerId: String = "customer-001", key: String = "key-1") {
	save(Cart.create(customerId, key, mapOf("americano" to 2, "cake" to 1), ORDER_TEST_NOW))
}
