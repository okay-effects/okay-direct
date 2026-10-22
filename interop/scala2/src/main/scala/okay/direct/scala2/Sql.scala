package okay.direct.scala2

import java.sql.ResultSet
import javax.sql.DataSource
import scala.jdk.CollectionConverters._
import okay.direct.{Body, Effect, Effects}
import okay.direct.sql.{Sql => JSql, Transaction => JTransaction}

/**
 * SQL AS AN EFFECT, the Scala 2 way (module `okay-direct-sql` on the class path): a statement is an operation, the
 * capability an implicit `Effect[Sql]`, [[Transaction.run]] its handler — committed when the body returns, rolled
 * back when it fails or a handler further out drops its rest.
 */
object Sql {
  type Failed = JSql.Failed
  type Refused = JSql.Refused

  private def objects(parameters: Seq[Any]): Seq[AnyRef] = parameters.map(_.asInstanceOf[AnyRef])

  /** The rows a query selects, each mapped. */
  def query[T](text: String, parameters: Any*)(row: ResultSet => T)(implicit sql: Effect[Sql]): List[T] =
    JSql.query[T](sql, (r: ResultSet) => row(r), text, objects(parameters): _*).asScala.toList

  /** The first row, if any. */
  def one[T](text: String, parameters: Any*)(row: ResultSet => T)(implicit sql: Effect[Sql]): Option[T] =
    query(text, parameters: _*)(row).headOption

  /** A change: the number of rows changed. */
  def update(text: String, parameters: Any*)(implicit sql: Effect[Sql]): Int =
    JSql.update(sql, text, objects(parameters): _*)

  /** The capability, its changes refused. */
  def readOnly(implicit sql: Effect[Sql]): Effect[Sql] = JSql.readOnly(sql)

  /** The capability, each statement told to `log` before it runs. */
  def logged(log: String => Unit)(implicit sql: Effect[Sql]): Effect[Sql] =
    JSql.logged(sql, (s: String) => log(s))
}

object Transaction {
  /** The body's statements in one transaction of `db`. */
  def run[A](db: DataSource)(body: Effect[Sql] => A)(implicit fx: Effects): A =
    JTransaction.run(fx, db, new Body[Sql, A, RuntimeException] { def run(sql: Effect[Sql]): A = body(sql) })
}
