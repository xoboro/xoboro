package io.xoboro.server.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource

data class DatabaseConfig(
  val path: Path,
  val maximumPoolSize: Int = 4,
  val busyTimeoutMillis: Int = 10_000,
  val acquireProcessLock: Boolean = true,
) {
  init {
    require(maximumPoolSize > 0) { "Maximum pool size must be positive" }
    require(busyTimeoutMillis >= 0) { "Busy timeout must not be negative" }
  }
}

class XoboroDatabase private constructor(
  private val hikariDataSource: HikariDataSource,
  private val databaseFileLock: AutoCloseable,
  val path: Path,
  val migrationResult: MigrateResult,
) : AutoCloseable {
  val dataSource: DataSource = hikariDataSource
  val dsl: DSLContext = DSL.using(hikariDataSource, SQLDialect.SQLITE)
  val backups: DatabaseBackupManager = DatabaseBackupManager(this, path)

  fun <T> transaction(block: (DSLContext) -> T): T =
    dsl.transactionResult { configuration ->
      block(DSL.using(configuration))
    }

  fun isAvailable(): Boolean =
    runCatching {
      dsl.fetchValue("SELECT 1", Int::class.java) == 1
    }.getOrDefault(false)

  override fun close() {
    try {
      hikariDataSource.close()
    } finally {
      databaseFileLock.close()
    }
  }

  companion object {
    fun open(config: DatabaseConfig): XoboroDatabase {
      val absolutePath = config.path.toAbsolutePath().normalize()
      absolutePath.parent?.let(Files::createDirectories)
      val databaseFileLock =
        if (config.acquireProcessLock) {
          DatabaseFileLock.acquire(absolutePath)
        } else {
          AutoCloseable {}
        }

      return try {
        val sqliteConfig =
          SQLiteConfig().apply {
            enforceForeignKeys(true)
            setBusyTimeout(config.busyTimeoutMillis)
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
          }
        val sqliteDataSource =
          SQLiteDataSource(sqliteConfig).apply {
            url = "jdbc:sqlite:$absolutePath"
          }
        val hikariDataSource =
          HikariDataSource(
            HikariConfig().apply {
              dataSource = sqliteDataSource
              poolName = "xoboro-sqlite"
              maximumPoolSize = config.maximumPoolSize
              minimumIdle = 0
              isAutoCommit = true
            },
          )
        val migrationResult =
          try {
            Flyway.configure()
              .dataSource(hikariDataSource)
              .locations("classpath:db/migration")
              .load()
              .migrate()
          } catch (failure: Throwable) {
            hikariDataSource.close()
            throw failure
          }
        XoboroDatabase(
          hikariDataSource = hikariDataSource,
          databaseFileLock = databaseFileLock,
          path = absolutePath,
          migrationResult = migrationResult,
        )
      } catch (failure: Throwable) {
        databaseFileLock.close()
        throw failure
      }
    }
  }
}
