package io.github.dudco.paymentplayground.cart

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import java.time.Instant

/**
 * Jackson Kotlin 모듈은 컬렉션의 `null` 원소를 거절하지 않으므로 원소를 nullable로 받고,
 * [CartController.save]에서 `null` 원소를 400으로 처리한다.
 */
data class SaveCartRequest(@field:Valid val lines: List<SaveCartLineRequest?>)

data class SaveCartLineRequest(
	@field:NotBlank val productId: String,
	@field:Min(1) @field:Max(99) val quantity: Int,
)

/** 장바구니 응답. 장바구니가 없으면 `idempotencyKey`·`expiresAt`이 `null`이다. */
data class CartResponse(
	val customerId: String,
	val idempotencyKey: String?,
	val lines: List<CartLineResponse>,
	val totalAmount: Long,
	val expiresAt: Instant?,
) {
	companion object {
		fun from(result: CartResult) = CartResponse(
			result.customerId,
			result.idempotencyKey,
			result.lines.map(CartLineResponse::from),
			result.totalAmount,
			result.expiresAt,
		)
	}
}

data class CartLineResponse(
	val productId: String,
	val productName: String,
	val unitPrice: Long,
	val quantity: Int,
	val lineAmount: Long,
) {
	companion object {
		fun from(result: CartLineResult) =
			CartLineResponse(result.productId, result.productName, result.unitPrice, result.quantity, result.lineAmount)
	}
}
