package io.github.dudco.paymentplayground.cart

import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CartRedisCodecTest {
	private val codec = CartRedisCodec(JsonMapper.builder().findAndAddModules().build())
	private val cart = Cart.create(
		"customer-001", "6f1c2a52-6c0e-4c55-9a43-6a6f0f6a8f10",
		mapOf("americano" to 2, "cake" to 1), Instant.parse("2026-10-13T11:20:30Z"),
	).replaceLines(mapOf("americano" to 2, "cake" to 1), Instant.parse("2026-10-13T11:25:00Z"))

	@Test
	fun `encode한 JSON을 decode하면 같은 Cart가 된다`() {
		assertEquals(cart, codec.decode(codec.encode(cart)))
	}

	@Test
	fun `JSON은 스펙의 저장 형식을 따르고 상품명·가격을 담지 않는다`() {
		val json = codec.encode(cart)

		assertTrue(""""lines":[{"productId":"americano","quantity":2},{"productId":"cake","quantity":1}]""" in json, json)
		assertTrue(""""createdAt":"2026-10-13T11:20:30Z"""" in json, json)
		assertTrue(""""updatedAt":"2026-10-13T11:25:00Z"""" in json, json)
		assertFalse("productName" in json || "price" in json || "Amount" in json, json)
	}
}
