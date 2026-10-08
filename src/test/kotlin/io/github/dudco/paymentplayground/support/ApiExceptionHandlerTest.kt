package io.github.dudco.paymentplayground.support

import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 테스트 클래스 안의 클래스는 컴포넌트 스캔에서 빠지므로 [ProbeController]를 `@Import`로 직접 등록한다.
 * `controllers`를 지정해 이후 추가될 실제 Controller가 이 슬라이스 컨텍스트에 올라오지 않게 한다.
 */
@WebMvcTest(controllers = [ApiExceptionHandlerTest.ProbeController::class])
@Import(ApiExceptionHandlerTest.ProbeController::class)
class ApiExceptionHandlerTest(@Autowired private val mockMvc: MockMvc) {

	@Test
	fun `NotFoundException은 404와 code, message 본문이 된다`() {
		mockMvc.perform(get("/probe/not-found"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PROBE_NOT_FOUND"))
			.andExpect(jsonPath("$.message").value("대상이 없습니다."))
	}

	@Test
	fun `ConflictException은 409와 code, message 본문이 된다`() {
		mockMvc.perform(get("/probe/conflict"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PROBE_CONFLICT"))
			.andExpect(jsonPath("$.message").value("허용되지 않는 상태입니다."))
	}

	@Test
	fun `UnprocessableException은 422와 code, message 본문이 된다`() {
		mockMvc.perform(get("/probe/unprocessable"))
			.andExpect(status().`is`(422))
			.andExpect(jsonPath("$.code").value("PROBE_UNPROCESSABLE"))
			.andExpect(jsonPath("$.message").value("처리할 수 없는 요청입니다."))
	}

	@Test
	fun `Bean Validation 실패는 400 VALIDATION_ERROR가 되고 거부된 값은 응답에 싣지 않는다`() {
		val body = mockMvc.perform(
			post("/probe/body")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""{"quantity": -98765}"""),
		)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
			.andReturn().response.getContentAsString(Charsets.UTF_8)

		assertTrue("quantity" in body, body)
		assertFalse("98765" in body, body)
	}

	@Test
	fun `읽을 수 없는 본문은 400 VALIDATION_ERROR가 된다`() {
		mockMvc.perform(
			post("/probe/body")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""{"quantity": """),
		)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.message").isNotEmpty())
	}

	@Test
	fun `필수 헤더가 없으면 400 VALIDATION_ERROR가 된다`() {
		mockMvc.perform(get("/probe/header"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.message").value("필수 헤더가 없습니다: Idempotency-Key"))
	}

	@RestController
	@RequestMapping("/probe")
	class ProbeController {

		@GetMapping("/not-found")
		fun notFound(): String = throw NotFoundException("PROBE_NOT_FOUND", "대상이 없습니다.")

		@GetMapping("/conflict")
		fun conflict(): String = throw ConflictException("PROBE_CONFLICT", "허용되지 않는 상태입니다.")

		@GetMapping("/unprocessable")
		fun unprocessable(): String = throw UnprocessableException("PROBE_UNPROCESSABLE", "처리할 수 없는 요청입니다.")

		@PostMapping("/body")
		fun body(@Valid @RequestBody request: ProbeRequest): ProbeRequest = request

		@GetMapping("/header")
		fun header(@RequestHeader("Idempotency-Key") idempotencyKey: String): String = idempotencyKey
	}

	data class ProbeRequest(
		@field:Positive
		val quantity: Int,
	)
}
