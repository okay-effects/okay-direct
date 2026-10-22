package okay.direct.espresso;

import okay.direct.Effect;

/**
 * THE DRIVER of a thread's fibers. Espresso's continuations do not nest, so every handled body is a fiber of its
 * own, resumed by the driver alone; a fiber asks the driver for what it needs and suspends: to perform an operation,
 * to start the body of a handler it installs, to resume a body its handler's clause resumes. The thread's own code
 * runs the driver's loop until the event its handling waits for; a handling installed in a fiber waits for its
 * events suspended, given them by the driver as its reply.
 */
final class Driver {
    private static final ThreadLocal<Driver> CURRENT = ThreadLocal.withInitial(Driver::new);

    static Driver current() {
        return CURRENT.get();
    }

    /** The fiber running now; null while the thread's own code runs. */
    Fiber running;

    /** What a fiber asks. */
    sealed interface Request {}
    record Perform(Object key, Effect.Op<?, ?> op) implements Request {}
    record Start(Handling<?, ?, ?, ?> handling) implements Request {}
    record Continue(Handling<?, ?, ?, ?> handling, Fiber performer, Object answer) implements Request {}

    /** What the code of a handling is given: its body's value, failure, or operation. */
    sealed interface Event {}
    record Returned(Object value) implements Event {}
    record Failed(Throwable failure) implements Event {}
    /** The body's rest dropped by its handler has unwound. */
    record Ended() implements Event {}
    record Performed(Effect.Op<?, ?> op, Fiber performer) implements Event {}

    /** Replies to a perform that are not an answer. */
    record Abandon(Object key) {}
    record Refuse(String why) {}

    /** Suspends the running fiber with its request; its reply when resumed. */
    Object ask(Request request) {
        var fiber = running;
        fiber.request = request;
        var inside = Bindings.current();
        fiber.capability.suspend();
        Bindings.restore(inside);
        return current().running.reply;
    }

    /** The driver's loop, on the thread: fibers resumed until an event for {@code waiting}, owned by the thread. */
    Event loop(Handling<?, ?, ?, ?> waiting, Fiber first, Object reply) {
        var fiber = first;
        fiber.reply = reply;
        while (true) {
            var outside = Bindings.current();
            running = fiber;
            try {
                fiber.continuation.resume();
            } finally {
                running = null;
                Bindings.restore(outside);
            }
            Handling<?, ?, ?, ?> target;
            Event event;
            if (fiber.continuation.isCompleted()) {
                target = fiber.handling;
                event = fiber.result;
            } else {
                switch (fiber.request) {
                    case Perform p -> {
                        target = find(fiber, p.key());
                        if (target == null) {
                            fiber.reply = new Refuse("performed outside its body: " + p.op());
                            continue;
                        }
                        if (abandoned(fiber, p.key())) {
                            fiber.reply = new Abandon(target.key);
                            continue;
                        }
                        event = new Performed(p.op(), fiber);
                    }
                    case Start s -> {
                        fiber = Fiber.start(s.handling());
                        fiber.reply = null;
                        continue;
                    }
                    case Continue c -> {
                        fiber = c.performer();
                        fiber.reply = c.answer();
                        continue;
                    }
                }
            }
            if (target.owner == null) {
                if (target != waiting) throw new IllegalStateException("an event for a handling not waiting: " + event);
                return event;
            }
            fiber = target.owner;
            fiber.reply = event;
        }
    }

    /** The fiber is in a rest of the handling of {@code key} that was dropped: a branch's, not the handling's. */
    private static boolean abandoned(Fiber fiber, Object key) {
        for (var f = fiber; f != null; f = f.handling.owner) {
            if (f.abandonedFor == key) return true;
            if (f.handling.key == key) return false;
        }
        return false;
    }

    /** The handling of {@code key} the fiber's body is in: its own, or one of those its body's handler is in. */
    private static Handling<?, ?, ?, ?> find(Fiber fiber, Object key) {
        for (var f = fiber; f != null; f = f.handling.owner) if (f.handling.key == key) return f.handling;
        return null;
    }
}
