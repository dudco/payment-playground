package io.github.dudco.paymentplayground.support

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstantTextConverterTest {
	private val converter = InstantTextConverter()

	@Test
	fun `Instant를 ISO-8601 UTC 문자열로 바꾸고 되돌린다`() {
		val instant = Instant.parse("2026-10-14T03:10:00.123456Z")

		assertEquals("2026-10-14T03:10:00Z", converter.convertToDatabaseColumn(Instant.parse("2026-10-14T03:10:00Z")))
		assertEquals(instant, converter.convertToEntityAttribute(converter.convertToDatabaseColumn(instant)))
		assertNull(converter.convertToDatabaseColumn(null))
		assertNull(converter.convertToEntityAttribute(null))
	}
}
