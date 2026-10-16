package io.github.dudco.paymentplayground.order

import io.github.dudco.paymentplayground.cart.domain.Cart
import io.github.dudco.paymentplayground.cart.repository.InMemoryCartRepository
import io.github.dudco.paymentplayground.product.repository.MutableProductRepository
import io.github.dudco.paymentplayground.support.MutableClock
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Instant

val ORDER_TEST_NOW: Instant = Instant.parse("2026-10-14T03:10:00Z")

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
