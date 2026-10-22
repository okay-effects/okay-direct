package okay.direct.scala2

import okay.direct.Effect

/** The rest of the body: `k(op, answer)`, the operation fixing the answer's type. */
trait Resume[E, R] {
  def apply[X](op: Effect.Op[E, X], answer: X): R
}

/** The answer to an operation, given in place. */
trait Answer[E] {
  def apply[X](op: Effect.Op[E, X], answer: X): Unit
}

/** A handler of E: the body's value to the result, and an operation with `k`, the rest of the body: ONE-SHOT. */
trait Handler[E, A, R] {
  def value(a: A): R
  def apply(op: E, k: Resume[E, R]): R
}

/** A handler of E that may resume `k` any number of times: runs only on a `MultiEffects`. */
trait MultiHandler[E, A, R] {
  def value(a: A): R
  def apply(op: E, k: Resume[E, R]): R
}

/** A handler of E answering every operation in place: in constant stack. */
trait TailHandler[E, A, R] {
  def value(a: A): R
  def apply(op: E, k: Answer[E]): Unit
}

/**
 * A handler of E with a state kept by the implementation: each operation answered in place through `k`, with the
 * state of its time, returning the state to go on with. Under a multi-shot handler further out, each resumption has
 * its own.
 */
trait StatefulHandler[E, S, A, R] {
  def value(s: S, a: A): R
  def apply(s: S, op: E, k: Answer[E]): S
}
