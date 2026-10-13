package io.github.dudco.paymentplayground

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class PaymentPlaygroundApplication

fun main(args: Array<String>) {
	runApplication<PaymentPlaygroundApplication>(*args)
}
