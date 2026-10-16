package io.github.dudco.paymentplayground.support

/** `key-1`, `key-2`, ... 순서로 멱등키를 발급한다. */
class SequentialIdempotencyKeyGenerator : IdempotencyKeyGenerator {
	private var sequence = 0

	override fun generate(): String = "key-${++sequence}"
}
