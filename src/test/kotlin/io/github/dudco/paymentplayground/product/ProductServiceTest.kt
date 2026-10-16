package io.github.dudco.paymentplayground.product

import io.github.dudco.paymentplayground.support.NotFoundException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProductServiceTest {
	private val service = ProductService(FixedProductRepository())

	@Test
	fun `전체 상품을 저장소 순서대로 결과로 돌려준다`() {
		val results = service.findAll()

		assertEquals(ProductResult("americano", "아메리카노", 4500), results.first())
		assertEquals(listOf("americano", "latte", "cake", "sandwich", "cookie"), results.map { it.productId })
	}

	@Test
	fun `상품 하나를 결과로 돌려주고 없는 상품은 PRODUCT_NOT_FOUND다`() {
		assertEquals(ProductResult("cake", "케이크", 6000), service.get("cake"))
		assertEquals("PRODUCT_NOT_FOUND", assertFailsWith<NotFoundException> { service.get("unknown") }.code)
	}

	@Test
	fun `여러 상품을 ID로 찾고 없는 ID는 결과에서 빠진다`() {
		val results = service.findByIds(listOf("cake", "unknown", "americano"))

		assertEquals(setOf("cake", "americano"), results.keys)
		assertEquals(ProductResult("cake", "케이크", 6000), results["cake"])
		assertEquals(emptyMap(), service.findByIds(emptyList()))
	}
}
