package io.github.dudco.paymentplayground.cart.domain

import java.time.Instant
import java.util.SortedMap

/**
 * 고객 한 명이 가지는 장바구니. 불변이며 변경은 새 인스턴스를 만든다.
 * [lines]는 상품 ID → 수량이고, 상품 ID 오름차순으로 정렬된다.
 */
data class Cart(
	val customerId: String,
	val idempotencyKey: String,
	val lines: SortedMap<String, Int>,
	val createdAt: Instant,
	val updatedAt: Instant,
) {
	init {
		require(customerId.isNotBlank()) { "customerId는 비어 있을 수 없습니다." }
		require(idempotencyKey.isNotBlank()) { "idempotencyKey는 비어 있을 수 없습니다." }
		require(lines.isNotEmpty()) { "장바구니 라인은 비어 있을 수 없습니다." }
		require(lines.values.all { it in MIN_QUANTITY..MAX_QUANTITY }) {
			"수량은 $MIN_QUANTITY 이상 $MAX_QUANTITY 이하여야 합니다."
		}
	}

	/** 멱등키와 [createdAt]은 유지하고 [lines]와 [updatedAt]만 교체한다. */
	fun replaceLines(lines: Map<String, Int>, now: Instant): Cart =
		copy(lines = lines.toSortedMap(), updatedAt = now)

	companion object {
		const val MIN_QUANTITY = 1
		const val MAX_QUANTITY = 99

		fun create(customerId: String, idempotencyKey: String, lines: Map<String, Int>, now: Instant): Cart =
			Cart(customerId, idempotencyKey, lines.toSortedMap(), now, now)
	}
}
