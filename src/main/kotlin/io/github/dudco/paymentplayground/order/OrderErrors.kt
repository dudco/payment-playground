package io.github.dudco.paymentplayground.order

import io.github.dudco.paymentplayground.support.ConflictException
import io.github.dudco.paymentplayground.support.NotFoundException

/** 주문할 장바구니가 없거나 주문 가능한 라인이 없다. `404 CART_NOT_FOUND`. */
fun cartNotFound(): NotFoundException = NotFoundException("CART_NOT_FOUND", "주문할 장바구니가 없습니다.")

/** 없는 주문이거나 다른 고객의 주문이다. `404 ORDER_NOT_FOUND`. */
fun orderNotFound(): NotFoundException = NotFoundException("ORDER_NOT_FOUND", "주문을 찾을 수 없습니다.")

/** 요청한 멱등키가 현재 장바구니나 기존 주문과 맞지 않는다. 키 값은 메시지에 넣지 않는다. `409 IDEMPOTENCY_KEY_MISMATCH`. */
fun idempotencyKeyMismatch(): ConflictException =
	ConflictException("IDEMPOTENCY_KEY_MISMATCH", "멱등키가 현재 장바구니와 맞지 않습니다.")
