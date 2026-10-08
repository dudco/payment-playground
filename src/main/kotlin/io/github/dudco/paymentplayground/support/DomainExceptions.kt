package io.github.dudco.paymentplayground.support

/**
 * 오류 응답의 `code`와 `message`가 되는 도메인 오류.
 *
 * 어떤 HTTP 상태로 응답할지는 하위 타입이 정하고, 실제 변환은 [ApiExceptionHandler]가 한다.
 * `message`는 그대로 클라이언트에 노출되므로 카드번호·토큰 같은 민감한 값을 넣지 않는다.
 */
sealed class DomainException(
	val code: String,
	override val message: String,
) : RuntimeException(message)

/** 대상이 없다. `404`로 응답한다. */
class NotFoundException(code: String, message: String) : DomainException(code, message)

/** 현재 상태에서 허용되지 않는 요청이다. `409`로 응답한다. */
class ConflictException(code: String, message: String) : DomainException(code, message)

/** 형식은 맞지만 비즈니스 규칙에 어긋난다. `422`로 응답한다. */
class UnprocessableException(code: String, message: String) : DomainException(code, message)
