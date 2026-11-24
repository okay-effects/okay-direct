package okay.direct;

/**
 * THE MARK OF A SENSITIVE EFFECT: its operations are {@link Sensitive.Op}s, of the family {@code Sensitive<C>}.
 * A sensitive effect {@code C} is a sealed interface like any:
 * <pre>{@code
 * sealed interface Pay {
 *     record Transfer(String to, int amount) implements Pay, Sensitive.Op<Pay, String, Transfer> {}
 *     record Balance() implements Pay, Sensitive.Op<Pay, Integer, Balance> {}
 * }
 * }</pre>
 * So they are performed through an {@code Effect<Sensitive<Pay>>}, which a program gets from {@link Audit#audit}
 * alone; and answered by the handler {@link Audit#handle} installs, alone: a handler of {@code Sensitive<Pay>}
 * written by hand gets each operation as a {@code Sensitive<Pay>}, of no case and no answer's type, so it can drop
 * the rest but not answer it. (okay-java-api's {@code okay.effect.Sensitive.Op} is the same mark in the row's
 * types; here the mark is a family of its own, so that {@link Effect#perform} and {@link Answer} take its
 * operations with their answer's type.)
 */
public interface Sensitive<C> {
    /** An operation of the sensitive effect C, answered by an X. */
    interface Op<C, X, Self extends Sensitive<C>> extends Sensitive<C>, okay.effect.Op<Sensitive<C>, X, Self> {}
}
