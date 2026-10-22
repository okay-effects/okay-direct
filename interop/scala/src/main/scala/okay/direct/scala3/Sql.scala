package okay.direct.scala3

import java.sql.ResultSet
import javax.sql.DataSource
import okay.direct.{Body, Effect, Effects}
import okay.direct.sql.{Sql as JSql, Transaction as JTransaction}

/**
 * SQL AS AN EFFECT, the Scala 3 way (module `okay-direct-sql` on the class path): a statement is an operation, the
 * capability `Effect[Sql]` a context parameter, [[Transaction.run]] its handler — committed when the body returns,
 * rolled back when it fails or a handler further out drops its rest.
 * {{{
 * Fail.attempt[String, Unit]:
 *   Transaction.run(db):
 *     Sql.update("update account set balance = balance - ? where id = ?", 50, "ann")
 *     if Sql.one("select balance from account where id = ?", "ann")(_.getInt(1)).get < 0 then Fail.raise("too poor")
 * }}}
 */
type Sql = JSql

object Sql:
  type Failed = JSql.Failed
  type Refused = JSql.Refused

  private def objects(parameters: Seq[Any]): Seq[AnyRef] = parameters.map(_.asInstanceOf[AnyRef])

  /** The rows a query selects, each mapped. */
  def query[T](text: String, parameters: Any*)(row: ResultSet => T)(using sql: Effect[Sql]): List[T] =
    import scala.jdk.CollectionConverters.*
    JSql.query[T](sql, (r: ResultSet) => row(r), text, objects(parameters)*).asScala.toList

  /** The first row, if any. */
  def one[T](text: String, parameters: Any*)(row: ResultSet => T)(using Effect[Sql]): Option[T] =
    query(text, parameters*)(row).headOption

  /** A change: the number of rows changed. */
  def update(text: String, parameters: Any*)(using sql: Effect[Sql]): Int =
    JSql.update(sql, text, objects(parameters)*)

  /** The capability in scope, its changes refused. */
  def readOnly(using sql: Effect[Sql]): Effect[Sql] = JSql.readOnly(sql)

  /** The capability in scope, each statement told to `log` before it runs. */
  def logged(log: String => Unit)(using sql: Effect[Sql]): Effect[Sql] = JSql.logged(sql, (s: String) => log(s))

object Transaction:
  /** The body's statements in one transaction of `db`. */
  def run[A](db: DataSource)(body: Effect[Sql] ?=> A)(using fx: Effects): A =
    JTransaction.run(fx, db, new Body[Sql, A, RuntimeException]:
      def run(sql: Effect[Sql]): A = body(using sql))
