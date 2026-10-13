package io.github.dudco.paymentplayground.customer

import io.github.dudco.paymentplayground.support.InvalidRequestException
import org.springframework.core.MethodParameter
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/**
 * 인증이 없는 동안 `X-Customer-ID` 헤더를 현재 고객 ID로 사용한다.
 *
 * 헤더가 없거나 공백이면 [InvalidRequestException]을 던진다.
 * 거부된 헤더 값은 메시지에 싣지 않는다.
 */
@Component
class HeaderCurrentCustomerResolver : HandlerMethodArgumentResolver {

	override fun supportsParameter(parameter: MethodParameter): Boolean =
		parameter.hasParameterAnnotation(CurrentCustomer::class.java) &&
			parameter.parameterType == String::class.java

	override fun resolveArgument(
		parameter: MethodParameter,
		mavContainer: ModelAndViewContainer?,
		webRequest: NativeWebRequest,
		binderFactory: WebDataBinderFactory?,
	): Any {
		val customerId = webRequest.getHeader(HEADER)?.trim()
		if (customerId.isNullOrEmpty()) {
			throw InvalidRequestException("$HEADER 헤더가 없거나 비어 있습니다.")
		}
		return customerId
	}

	companion object {
		const val HEADER = "X-Customer-ID"
	}
}
