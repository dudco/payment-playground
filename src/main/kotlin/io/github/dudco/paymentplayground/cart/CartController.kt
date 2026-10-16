package io.github.dudco.paymentplayground.cart

import io.github.dudco.paymentplayground.customer.CurrentCustomer
import io.github.dudco.paymentplayground.support.InvalidRequestException
import jakarta.validation.Valid
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
	fun get(@CurrentCustomer customerId: String): CartResponse = CartResponse.from(cartService.get(customerId))

	@PostMapping
	fun save(@CurrentCustomer customerId: String, @Valid @RequestBody request: SaveCartRequest): CartResponse {
		val commands = request.lines.map { line ->
			if (line == null) throw InvalidRequestException("lines에 비어 있는 항목이 있습니다.")
			CartLineCommand(line.productId, line.quantity)
		}
		return CartResponse.from(cartService.save(customerId, commands))
	}
}
