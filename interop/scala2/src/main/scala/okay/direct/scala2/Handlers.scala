package okay.direct.scala2


/** The rest of the body: `k(op, answer)`, the operation fixing the answer's type. */
trait Resume[E, B] {
  def apply[X](op: Op[E, X], answer: X): B
}

/** The answer to an operation, given in place. */
trait Answer[E] {
  def apply[X](op: Op[E, X], answer: X): Unit
}

/** A handler of E: the body's value to the result, and an operation with `k`, the rest of the body: ONE-SHOT. */
trait Handler[E, A, B] {
  def ret(a: A): B
  def on(op: E, k: Resume[E, B]): B
}

/** A handler of E that may resume `k` any number of times: runs only on a `MultiEffects`. */
trait MultiHandler[E, A, B] {
  def ret(a: A): B
  def on(op: E, k: Resume[E, B]): B
}

/** A handler of E answering every operation in place: in constant stack. */
trait InPlace[E, A, B] {
  def ret(a: A): B
  def answer(op: E, k: Answer[E]): Unit
}

/**
 * A handler of E with a state kept by the implementation: each operation answered in place through `k`, with the
 * state of its time, returning the state to go on with. Under a multi-shot handler further out, each resumption has
 * its own.
 */
trait StatefulHandler[E, S, A, B] {
  def ret(s: S, a: A): B
  def answer(s: S, op: E, k: Answer[E]): S
}
