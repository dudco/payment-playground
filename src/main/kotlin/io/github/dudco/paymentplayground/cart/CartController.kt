package io.github.dudco.paymentplayground.cart

import io.github.dudco.paymentplayground.customer.CurrentCustomer
import io.github.dudco.paymentplayground.support.InvalidRequestException
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 장바구니 조회와 저장(전체 교체).
 * 수량 범위(1..99)는 서비스가 아니라 여기서 Bean Validation으로 검증한다.
 */
@RestController
@RequestMapping("/cart")
class CartController(private val cartService: CartService) {

	@GetMapping
	fun get(@CurrentCustomer customerId: String): CartView = cartService.get(customerId)

	@PostMapping
	fun save(@CurrentCustomer customerId: String, @Valid @RequestBody request: SaveCartRequest): CartView {
		val commands = request.lines.map { line ->
			if (line == null) throw InvalidRequestException("lines에 비어 있는 항목이 있습니다.")
			CartLineCommand(line.productId, line.quantity)
		}
		return cartService.save(customerId, commands)
	}
}

/**
 * Jackson Kotlin 모듈은 컬렉션의 `null` 원소를 거절하지 않으므로 원소를 nullable로 받고,
 * [CartController.save]에서 `null` 원소를 400으로 처리한다.
 */
data class SaveCartRequest(@field:Valid val lines: List<SaveCartLineRequest?>)

data class SaveCartLineRequest(
	@field:NotBlank val productId: String,
	@field:Min(1) @field:Max(99) val quantity: Int,
)
