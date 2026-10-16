package io.github.dudco.paymentplayground.support

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import java.time.Instant

/** [Instant]를 ISO-8601 UTC 문자열(TEXT)로 저장하고 읽는다. SQLite에는 시각 타입이 없기 때문이다. */
@Converter
class InstantTextConverter : AttributeConverter<Instant, String> {
	override fun convertToDatabaseColumn(attribute: Instant?): String? = attribute?.toString()

	override fun convertToEntityAttribute(dbData: String?): Instant? = dbData?.let(Instant::parse)
}
