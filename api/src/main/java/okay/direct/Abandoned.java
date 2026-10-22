package okay.direct;

/**
 * Thrown at {@code perform} in a body whose rest its handler dropped: the body unwinds, its {@code finally} blocks
 * run, and a {@code perform} of the same handler on the way throws it again. It unwinds through the bodies of
 * handlers nested in the one that dropped the rest, up to that one's body, whose end it is. Not to be caught by
 * programs.
 */
public final class Abandoned extends Error {
    private final transient Object owner;

    /** {@code owner}: the implementation's record of the body whose rest was dropped. */
    public Abandoned(Object owner) {
        super("the rest of the body was abandoned by its handler", null, false, false);
        this.owner = owner;
    }

    /** This unwinds the body of {@code owner}: its end, not to be passed further out. */
    public boolean of(Object owner) {
        return this.owner == owner;
    }
}
