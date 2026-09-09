package io.github.dudco.paymentplayground

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class PaymentPlaygroundApplication

fun main(args: Array<String>) {
	runApplication<PaymentPlaygroundApplication>(*args)
}
