package okay.direct.espresso;

import org.graalvm.continuations.Continuation;
import org.graalvm.continuations.SuspendCapability;

/** A handled body, run in a continuation of its own: what it asks the driver, and what it is given back. */
final class Fiber {
    Continuation continuation;
    SuspendCapability capability;
    /** The handling whose body this is. */
    Handling<?, ?, ?, ?> handling;
    Driver.Request request;
    Object reply;
    Driver.Event result;
    /** The key of the handling whose rest this fiber is in, dropped: its operations of that handling refused. */
    Object abandonedFor;

    static Fiber start(Handling<?, ?, ?, ?> handling) {
        var fiber = new Fiber();
        fiber.handling = handling;
        fiber.continuation = Continuation.create(handling.entry());
        return fiber;
    }
}
