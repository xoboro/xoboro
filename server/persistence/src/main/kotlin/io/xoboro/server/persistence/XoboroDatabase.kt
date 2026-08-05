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

  companion object {
    /**
     * Connections for [workerCount] task workers and the requests served alongside them.
     *
     * A worker holds its connection for the length of its task, so a pool sized near the worker count
     * leaves requests waiting on work that may take minutes. That wait ends in Hikari giving up, and
     * a task told the pool was empty is indistinguishable from a task that failed unless something
     * classifies it - which is how contention alone dead-lettered a metadata refresh.
     *
     * WAL admits any number of concurrent readers, so headroom genuinely buys read concurrency; it
     * buys no write throughput, because SQLite still admits one writer. The cap keeps a deployment
     * that raises its worker count from opening connections without limit.
     */
    fun poolSizeForWorkers(workerCount: Int): Int {
      require(workerCount > 0) { "Worker count must be positive" }
      return (workerCount + READ_HEADROOM_CONNECTIONS).coerceAtMost(MAXIMUM_POOL_SIZE)
    }

    private const val READ_HEADROOM_CONNECTIONS: Int = 8
    private const val MAXIMUM_POOL_SIZE: Int = 16
  }
}

class XoboroDatabase private constructor(
  private val hikariDataSource: HikariDataSource,
  val dataSource: DataSource,
  private val databaseFileLock: AutoCloseable,
  val path: Path,
  val migrationResult: MigrateResult,
) : AutoCloseable {
  val dsl: DSLContext = DSL.using(dataSource, SQLDialect.SQLITE)
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
        val dataSource = SqliteFunctionDataSource(hikariDataSource)
        val migrationResult =
          try {
            Flyway.configure()
              .dataSource(dataSource)
              .locations("classpath:db/migration")
              .load()
              .migrate()
          } catch (failure: Throwable) {
            hikariDataSource.close()
            throw failure
          }
        XoboroDatabase(
          hikariDataSource = hikariDataSource,
          dataSource = dataSource,
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
