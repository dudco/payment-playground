package io.github.dudco.paymentplayground.support

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 모든 오류 응답이 공유하는 본문. */
data class ApiErrorResponse(
	val code: String,
	val message: String,
)

@RestControllerAdvice
class ApiExceptionHandler {

	@ExceptionHandler(DomainException::class)
	fun handleDomain(e: DomainException): ResponseEntity<ApiErrorResponse> {
		val status = when (e) {
			is NotFoundException -> HttpStatus.NOT_FOUND
			is ConflictException -> HttpStatus.CONFLICT
			is UnprocessableException -> HttpStatus.UNPROCESSABLE_CONTENT
		}
		return ResponseEntity.status(status).body(ApiErrorResponse(e.code, e.message))
	}

	/**
	 * 필드 이름과 제약 메시지만 응답에 싣는다.
	 * 거부된 값에는 카드번호나 토큰이 들어 있을 수 있으므로 포함하지 않는다.
	 */
	@ExceptionHandler(MethodArgumentNotValidException::class)
	fun handleInvalidBody(e: MethodArgumentNotValidException): ResponseEntity<ApiErrorResponse> {
		val detail = e.bindingResult.fieldErrors.joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
		return validationError(detail.ifEmpty { "요청 본문이 올바르지 않습니다." })
	}

	/** 파싱 오류 메시지에는 입력 일부가 들어 있을 수 있으므로 고정 문구만 응답한다. */
	@ExceptionHandler(HttpMessageNotReadableException::class)
	fun handleUnreadableBody(): ResponseEntity<ApiErrorResponse> =
		validationError("요청 본문을 읽을 수 없습니다.")

	@ExceptionHandler(MissingRequestHeaderException::class)
	fun handleMissingHeader(e: MissingRequestHeaderException): ResponseEntity<ApiErrorResponse> =
		validationError("필수 헤더가 없습니다: ${e.headerName}")

	private fun validationError(message: String): ResponseEntity<ApiErrorResponse> =
		ResponseEntity.badRequest().body(ApiErrorResponse(VALIDATION_ERROR, message))

	companion object {
		const val VALIDATION_ERROR = "VALIDATION_ERROR"
	}
}
