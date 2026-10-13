package io.github.dudco.paymentplayground.customer

import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** [CurrentCustomer] resolver를 Spring MVC에 등록한다. */
@Configuration
class CustomerWebConfig(
	private val resolver: HeaderCurrentCustomerResolver,
) : WebMvcConfigurer {

	override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
		resolvers.add(resolver)
	}
}
