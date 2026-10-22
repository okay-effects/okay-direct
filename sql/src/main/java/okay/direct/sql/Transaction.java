package okay.direct.sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import okay.direct.Answer;
import okay.direct.Body;
import okay.direct.Effects;
import okay.direct.TailHandler;

/**
 * A TRANSACTION: the handler of {@link Sql}. It opens a connection, runs the body, its statements on it, and commits
 * when the body returns; when the body fails, or is unwound by a handler further out that dropped its rest, it rolls
 * back, and the failure goes on. A statement that fails fails the transaction, as {@link Sql.Failed}.
 */
public final class Transaction {
    private Transaction() {}

    public static <A, E extends Exception> A run(Effects effects, DataSource source, Body<Sql, A, E> body) throws E {
        Connection connection;
        try {
            connection = source.getConnection();
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new Sql.Failed("open a transaction", e);
        }
        try {
            A value = effects.handle(handler(connection), body);
            try {
                connection.commit();
            } catch (SQLException e) {
                throw new Sql.Failed("commit", e);
            }
            return value;
        } catch (Throwable failure) {
            try {
                connection.rollback();
            } catch (SQLException e) {
                failure.addSuppressed(e);
            }
            throw failure;
        } finally {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // closed with its outcome decided
            }
        }
    }

    private static <A> TailHandler<Sql, A, A> handler(Connection connection) {
        return new TailHandler<>() {
            @Override
            public A value(A value) {
                return value;
            }

            @Override
            public void operation(Sql op, Answer<Sql> k) {
                switch (op) {
                    case Sql.Query<?> query -> answered(connection, query, k);
                    case Sql.Update update -> k.answer(update, changed(connection, update));
                }
            }
        };
    }

    private static <T> void answered(Connection connection, Sql.Query<T> query, Answer<Sql> k) {
        k.answer(query, rows(connection, query));
    }

    private static <T> List<T> rows(Connection connection, Sql.Query<T> query) {
        try (var statement = prepared(connection, query.text(), query.parameters());
             var rows = statement.executeQuery()) {
            var values = new ArrayList<T>();
            while (rows.next()) values.add(query.rows().row(rows));
            return values;
        } catch (SQLException e) {
            throw new Sql.Failed(query.text(), e);
        }
    }

    private static int changed(Connection connection, Sql.Update update) {
        try (var statement = prepared(connection, update.text(), update.parameters())) {
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new Sql.Failed(update.text(), e);
        }
    }

    private static PreparedStatement prepared(Connection connection, String text, List<Object> parameters)
            throws SQLException {
        var statement = connection.prepareStatement(text);
        for (int i = 0; i < parameters.size(); i++) statement.setObject(i + 1, parameters.get(i));
        return statement;
    }
}
