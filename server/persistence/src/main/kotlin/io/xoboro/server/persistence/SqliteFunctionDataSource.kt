package io.xoboro.server.persistence

import java.sql.Connection
import java.sql.SQLException
import java.util.Collections
import java.util.WeakHashMap
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import javax.sql.DataSource
import org.sqlite.Function
import org.sqlite.SQLiteConnection

internal class SqliteFunctionDataSource(
  private val delegate: DataSource,
) : DataSource by delegate {
  private val initializedConnections =
    Collections.newSetFromMap(WeakHashMap<SQLiteConnection, Boolean>())

  override fun getConnection(): Connection =
    delegate.connection.installFunctions()

  override fun getConnection(
    username: String?,
    password: String?,
  ): Connection =
    delegate.getConnection(username, password).installFunctions()

  private fun Connection.installFunctions(): Connection {
    val sqlite = unwrap(SQLiteConnection::class.java)
    synchronized(initializedConnections) {
      if (initializedConnections.add(sqlite)) {
        try {
          Function.create(
            sqlite,
            REGEXP_FUNCTION,
            CaseInsensitiveRegexp(),
            REGEXP_ARGUMENT_COUNT,
            Function.FLAG_DETERMINISTIC,
          )
        } catch (failure: SQLException) {
          initializedConnections.remove(sqlite)
          close()
          throw failure
        }
      }
    }
    return this
  }

  private class CaseInsensitiveRegexp : Function() {
    private var cachedExpression: String? = null
    private var cachedPattern: Pattern? = null

    override fun xFunc() {
      val expression = value_text(0)
      val input = value_text(1)
      if (expression == null || input == null) {
        result(0)
        return
      }
      try {
        val pattern =
          if (expression == cachedExpression) {
            requireNotNull(cachedPattern)
          } else {
            Pattern
              .compile(expression, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
              .also {
                cachedExpression = expression
                cachedPattern = it
              }
          }
        result(if (pattern.matcher(input).find()) 1 else 0)
      } catch (failure: PatternSyntaxException) {
        error(failure.description)
      }
    }
  }

  private companion object {
    const val REGEXP_FUNCTION = "regexp"
    const val REGEXP_ARGUMENT_COUNT = 2
  }
}
