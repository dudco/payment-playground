package io.github.dudco.paymentplayground

import io.github.dudco.paymentplayground.support.DatabaseCleaner
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Clock
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
class PaymentPlaygroundApplicationTests(
	@Autowired private val dataSource: DataSource,
	@Autowired private val clock: Clock,
	@Autowired private val databaseCleaner: DatabaseCleaner,
) {

	@Test
	fun contextLoads() {
	}

	@Test
	fun `Flyway가 스키마 이력 테이블을 만든다`() {
		val count = queryInt(
			"SELECT count(*) FROM sqlite_master " +
				"WHERE type = 'table' AND name = '${DatabaseCleaner.FLYWAY_HISTORY_TABLE}'",
		)

		assertEquals(1, count)
	}

	@Test
	fun `시간은 Clock 빈으로 주입받는다`() {
		assertNotNull(clock.instant())
	}

	@Test
	fun `DatabaseCleaner는 Flyway 이력 테이블을 건드리지 않고 나머지 테이블을 비운다`() {
		execute("CREATE TABLE cleaner_probe (id INTEGER PRIMARY KEY)")
		try {
			execute("INSERT INTO cleaner_probe (id) VALUES (1)")

			val targets = databaseCleaner.tableNames()
			databaseCleaner.clean()

			assertTrue("cleaner_probe" in targets)
			assertFalse(DatabaseCleaner.FLYWAY_HISTORY_TABLE in targets)
			assertEquals(0, queryInt("SELECT count(*) FROM cleaner_probe"))
		} finally {
			execute("DROP TABLE cleaner_probe")
		}
	}

	private fun execute(sql: String) {
		dataSource.connection.use { connection ->
			connection.createStatement().use { it.execute(sql) }
		}
	}

	private fun queryInt(sql: String): Int =
		dataSource.connection.use { connection ->
			connection.createStatement().use { statement ->
				statement.executeQuery(sql).use { rows ->
					rows.next()
					rows.getInt(1)
				}
			}
		}
}
