package okay.direct;

/**
 * A program in direct style whose type says what it may do: it runs with the capabilities of its row {@code R}
 * (see {@link Eff}) and returns an A.
 *
 * <p>A handler takes the first place of the row: {@link Fx#handle} turns a {@code Program<A, Eff<F, R>>} into a
 * {@code Program<B, R>}; {@link Fx#run} runs a {@code Program<A, Pure>}. A method whose parameter is an open row
 * ({@code Fx<? extends Eff<State<Integer>, ?>>}) is a program of every row that starts so.
 *
 * <p>A program throws no checked exception: an error it may end with is an effect of its row
 * ({@code Fail<IOException>}), so it has a place and an order among the handlers. Java's checked exceptions meet the
 * row at its ends: {@code Fail.catching} turns a call that throws into a failure, {@code Fail.rethrow} a failure
 * left at the end into a thrown exception. (A type parameter for the exception would be lost by Java's inference as
 * soon as two handlers are nested.)
 */
@FunctionalInterface
public interface Program<A, R> {
    A run(Fx<R> fx);
}
