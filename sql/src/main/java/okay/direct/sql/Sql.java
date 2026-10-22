package okay.direct.sql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import okay.direct.Answer;
import okay.direct.Effect;
import okay.direct.Effect.Op;
import okay.direct.Interceptor;

/**
 * SQL AS AN EFFECT: a statement is an operation, data — its text and its parameters — and a transaction is its
 * handler ({@link Transaction#run}). A program performs statements and does not know the connection; the handler
 * commits when the body returns and rolls back when it fails, or when its rest is dropped by a handler further out
 * (a {@code Fail}): the body unwinds through the transaction.
 * <pre>{@code
 * Outcome<String, Void> transfer = Fail.attempt(fx, String.class, fail -> Transaction.run(fx, db, sql -> {
 *     Sql.update(sql, "update account set balance = balance - ? where id = ?", 50, "ann");
 *     if (Sql.one(sql, row -> row.getInt(1), "select balance from account where id = ?", "ann").orElseThrow() < 0)
 *         Fail.raise(fail, "not enough money");       // rolled back
 *     Sql.update(sql, "update account set balance = balance + ? where id = ?", 50, "bob");
 *     return null;
 * }));
 * }</pre>
 * CONTROL by interceptors on the capability: {@link #readOnly} refuses changes, {@link #logged} tells each statement.
 */
public sealed interface Sql {
    /** The rows a query selects, each mapped. */
    record Query<T>(String text, List<Object> parameters, Rows<T> rows) implements Sql, Op<Sql, List<T>> {}

    /** A change: the number of rows changed. */
    record Update(String text, List<Object> parameters) implements Sql, Op<Sql, Integer> {}

    /** A row mapped to a value. */
    @FunctionalInterface
    interface Rows<T> {
        T row(ResultSet row) throws SQLException;
    }

    /** A statement failed: its transaction is rolled back. */
    final class Failed extends RuntimeException {
        public Failed(String statement, SQLException cause) {
            super(statement + ": " + cause.getMessage(), cause);
        }
    }

    /** A statement refused by a control on the capability. */
    final class Refused extends RuntimeException {
        public Refused(String message) {
            super(message);
        }
    }

    static <T> List<T> query(Effect<Sql> sql, Rows<T> rows, String text, Object... parameters) {
        return sql.perform(new Query<>(text, Arrays.asList(parameters), rows));
    }

    /** The first row, if any. */
    static <T> Optional<T> one(Effect<Sql> sql, Rows<T> rows, String text, Object... parameters) {
        return query(sql, rows, text, parameters).stream().findFirst();
    }

    static int update(Effect<Sql> sql, String text, Object... parameters) {
        return sql.perform(new Update(text, Arrays.asList(parameters)));
    }

    /** The capability {@code sql}, its changes refused: queries only. */
    static Effect<Sql> readOnly(Effect<Sql> sql) {
        return Interceptor.intercept(sql, new Interceptor<Sql, Sql>() {
            @Override
            public void operation(Sql op, Pass<Sql> pass, Answer<Sql> k) {
                switch (op) {
                    case Query<?> query -> passed(query, pass, k);
                    case Update update -> throw new Refused("read only: " + update.text());
                }
            }
        });
    }

    /** The capability {@code sql}, each statement told to {@code log} before it runs: an audit trail. */
    static Effect<Sql> logged(Effect<Sql> sql, Consumer<String> log) {
        return Interceptor.intercept(sql, new Interceptor<Sql, Sql>() {
            @Override
            public void operation(Sql op, Pass<Sql> pass, Answer<Sql> k) {
                switch (op) {
                    case Query<?> query -> {
                        log.accept(query.text() + " " + query.parameters());
                        passed(query, pass, k);
                    }
                    case Update update -> {
                        log.accept(update.text() + " " + update.parameters());
                        k.answer(update, pass.pass(update));
                    }
                }
            }
        });
    }

    private static <T> void passed(Query<T> query, Interceptor.Pass<Sql> pass, Answer<Sql> k) {
        k.answer(query, pass.pass(query));
    }
}
