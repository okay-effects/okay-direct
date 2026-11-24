package okay.direct;

/**
 * THE AUDITED PLACE of a sensitive effect C: what {@link Audit#handle} gives its body in place of a capability.
 * Nothing can be performed through it; only {@link Audit#audit} takes it, and gives its own body the capability,
 * {@code Effect<Sensitive<C>>}, every operation through an interceptor first.
 */
public final class Audited<C> {
    final Effect<Sensitive<C>> effect;

    Audited(Effect<Sensitive<C>> effect) {
        this.effect = effect;
    }
}
