package io.github.dudco.paymentplayground.customer

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 테스트 클래스 안의 클래스는 컴포넌트 스캔에서 빠지므로 [ProbeController]를 `@Import`로 직접 등록한다.
 * 400 응답 변환은 `ApiExceptionHandler`(`@RestControllerAdvice`)가 슬라이스에 포함되어 담당한다.
 */
@WebMvcTest(controllers = [HeaderCurrentCustomerResolverTest.ProbeController::class])
@Import(
	HeaderCurrentCustomerResolverTest.ProbeController::class,
	CustomerWebConfig::class,
	HeaderCurrentCustomerResolver::class,
)
class HeaderCurrentCustomerResolverTest(@Autowired private val mockMvc: MockMvc) {

	@Test
	fun `X-Customer-ID 헤더 값이 CurrentCustomer 파라미터로 들어온다`() {
		mockMvc.perform(get("/probe/customer").header("X-Customer-ID", "customer-001"))
			.andExpect(status().isOk())
			.andExpect(content().string("customer-001"))
	}

	@Test
	fun `헤더 값의 앞뒤 공백은 제거한다`() {
		mockMvc.perform(get("/probe/customer").header("X-Customer-ID", "  customer-001 "))
			.andExpect(content().string("customer-001"))
	}

	@Test
	fun `헤더가 없거나 공백이면 400 VALIDATION_ERROR다`() {
		mockMvc.perform(get("/probe/customer"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
		mockMvc.perform(get("/probe/customer").header("X-Customer-ID", "   "))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
	}

	@RestController
	class ProbeController {

		@GetMapping("/probe/customer")
		fun customer(@CurrentCustomer customerId: String): String = customerId
	}
}
