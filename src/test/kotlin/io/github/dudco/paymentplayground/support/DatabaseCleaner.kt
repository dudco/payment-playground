package io.github.dudco.paymentplayground.support

import org.springframework.stereotype.Component
import javax.sql.DataSource

/**
 * 테스트 사이에 DB 데이터를 지운다. 스키마와 Flyway 이력 테이블은 그대로 둔다.
 *
 * 통합 테스트의 `@BeforeEach`에서 [clean]을 호출한다.
 */
@Component
class DatabaseCleaner(private val dataSource: DataSource) {

	fun tableNames(): List<String> =
		dataSource.connection.use { connection ->
			connection.createStatement().use { statement ->
				statement.executeQuery(
					"""
					SELECT name FROM sqlite_master
					WHERE type = 'table'
					  AND name NOT LIKE 'sqlite_%'
					  AND name <> '$FLYWAY_HISTORY_TABLE'
					""".trimIndent(),
				).use { rows ->
					buildList {
						while (rows.next()) {
							add(rows.getString("name"))
						}
					}
				}
			}
		}

	fun clean() {
		val names = tableNames()
		dataSource.connection.use { connection ->
			connection.createStatement().use { statement ->
				names.forEach { statement.executeUpdate("DELETE FROM \"$it\"") }
			}
		}
	}

	companion object {
		const val FLYWAY_HISTORY_TABLE = "flyway_schema_history"
	}
}
