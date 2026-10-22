package okay.direct;

/**
 * A handler of F with a state of its own, kept by the implementation: each operation answered in place, with the
 * state of its time, and the state to go on with returned. Under a multi-shot handler further out, each resumption
 * has the state of its own time: a choice inside the state makes it branch-local, one outside threads it through
 * the branches. A handler keeping its state in a field of its own shares it among the branches instead.
 */
public interface StatefulHandler<F, S, A, R> {
    R value(S state, A value);

    /** Must answer the operation through {@code k}, exactly once; returns the state to go on with. */
    S operation(S state, F op, Answer<F> k);
}
