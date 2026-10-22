package okay.direct;

/**
 * The rest of the handled body, MULTI-SHOT: may be resumed any number of times, each resumption going on from the
 * operation with its own answer. Given only by a {@link MultiEffects} to a {@link MultiHandler}.
 *
 * <p>A resumption has its own frames, the locals of the rest, but the heap is one: an object the rest changes is
 * changed for every resumption. So a clause collects its results in values made after the resumptions, not in an
 * object it changes between them: a branch forked inside a later resumption would change it too. The same holds for
 * an object a compiler makes before an operation and fills after it: Scala 3's {@code List(flip, flip)} makes its
 * varargs array first, so every branch fills the one array; bind the answers to locals first.
 */
public interface MultiResume<F, R, E extends Exception> extends Resume<F, R, E> {}
