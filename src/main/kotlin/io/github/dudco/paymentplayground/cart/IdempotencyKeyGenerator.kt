package io.github.dudco.paymentplayground.cart

import org.springframework.stereotype.Component
import java.util.UUID

/** 체크아웃 멱등키를 발급한다. 테스트에서는 순차 발급기로 바꿔 끼운다. */
fun interface IdempotencyKeyGenerator {
	fun generate(): String
}

@Component
class UuidIdempotencyKeyGenerator : IdempotencyKeyGenerator {
	override fun generate(): String = UUID.randomUUID().toString()
}
