package io.github.dudco.paymentplayground.support

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** 테스트에서 시각을 직접 움직이기 위한 시계. */
class MutableClock(var now: Instant) : Clock() {
	override fun instant(): Instant = now

	override fun getZone(): ZoneId = ZoneOffset.UTC

	override fun withZone(zone: ZoneId): Clock = this
}
