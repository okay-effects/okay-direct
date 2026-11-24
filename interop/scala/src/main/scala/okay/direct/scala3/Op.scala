package okay.direct.scala3

/** AN OPERATION of the effect `F`, answered by an `X`: okay-java-api's `okay.effect.Op`, shared with okay-java and
  * every Java library of the family, its `Self` the effect itself — Scala's GADT match gives a clause the case:
  * {{{
  * enum State[S]:
  *   case Get[S]() extends State[S], Op[State[S], S]
  *   case Put[S](value: S) extends State[S], Op[State[S], Unit]
  * }}}
  */
type Op[F, X] = okay.effect.Op[F, X, F]

/** An operation of the sensitive effect `C`, answered by an `X` (okay-direct's `Audited.Op`). */
type AuditedOp[C, X] = okay.direct.Audited.Op[C, X, okay.direct.Audited[C]]
