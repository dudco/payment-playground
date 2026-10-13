package io.github.dudco.paymentplayground.support

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * 현재 시각이 필요한 곳은 `Instant.now()` 대신 이 [Clock]을 주입받는다.
 * 테스트는 이 빈을 고정 시각 Clock으로 바꿔 시간에 의존하는 동작을 검증한다.
 */
@Configuration
class TimeConfig {

	@Bean
	fun clock(): Clock = Clock.systemUTC()
}
