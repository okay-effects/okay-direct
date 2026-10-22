package okay.direct;

/**
 * THE FAMILY OF A SENSITIVE EFFECT's operations. A sensitive effect {@code C} is a sealed interface like any, but
 * its operations are {@link Audited.Op}s, of the family {@code Audited<C>}:
 * <pre>{@code
 * sealed interface Pay {
 *     record Transfer(String to, int amount) implements Pay, Audited.Op<Pay, String> {}
 *     record Balance() implements Pay, Audited.Op<Pay, Integer> {}
 * }
 * }</pre>
 * So they are performed through an {@code Effect<Audited<Pay>>}, which a program gets from {@link Audit#audit}
 * alone; and answered by the handler {@link Audit#handle} installs, alone: a handler of {@code Audited<Pay>}
 * written by hand gets each operation as an {@code Audited<Pay>}, of no case and no answer's type, so it can drop
 * the rest but not answer it.
 */
public interface Audited<C> {
    /** An operation of the sensitive effect C, answered by an X. */
    interface Op<C, X> extends Audited<C>, Effect.Op<Audited<C>, X> {}
}
