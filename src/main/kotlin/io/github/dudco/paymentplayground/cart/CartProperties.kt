package io.github.dudco.paymentplayground.cart

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 장바구니 설정. `payment-playground.cart.ttl`로 Redis 키의 유효 시간을 정하며 기본값은 30분이다.
 */
@ConfigurationProperties("payment-playground.cart")
data class CartProperties(
	val ttl: Duration = Duration.ofMinutes(30),
)
