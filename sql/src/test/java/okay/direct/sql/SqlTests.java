package okay.direct.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import okay.direct.Effect;
import okay.direct.Effects;
import okay.direct.std.Fail;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Transactions as handlers of SQL: committed, rolled back, controlled. */
abstract class SqlTests {
    abstract Effects effects();

    JdbcDataSource db;

    @BeforeEach
    void database() {
        db = new JdbcDataSource();
        db.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Transaction.run(effects(), db, sql -> {
            Sql.update(sql, "create table account(id varchar primary key, balance int)");
            Sql.update(sql, "insert into account values (?, ?), (?, ?)", "ann", 100, "bob", 0);
            return null;
        });
    }

    Map<String, Integer> balances() {
        var rows = Transaction.run(effects(), db, sql -> Sql.query(sql,
            row -> Map.entry(row.getString(1), row.getInt(2)), "select id, balance from account order by id"));
        var balances = new java.util.HashMap<String, Integer>();
        rows.forEach(row -> balances.put(row.getKey(), row.getValue()));
        return balances;
    }

    static int balance(Effect<Sql> sql, String id) {
        return Sql.one(sql, row -> row.getInt(1), "select balance from account where id = ?", id).orElseThrow();
    }

    /** A transfer: the program knows statements and failure, not connections nor commits. */
    static Void transfer(Effect<Sql> sql, Effect<Fail<String>> fail, String from, String to, int amount) {
        Sql.update(sql, "update account set balance = balance - ? where id = ?", amount, from);
        if (balance(sql, from) < 0) Fail.raise(fail, "not enough money on " + from);
        Sql.update(sql, "update account set balance = balance + ? where id = ?", amount, to);
        return null;
    }

    @Test
    void aTransactionCommitsWhenItsBodyReturns() {
        var fx = effects();
        var outcome = Fail.attempt(fx, String.class, fail -> Transaction.run(fx, db,
            sql -> transfer(sql, fail, "ann", "bob", 30)));
        assertEquals(new Fail.Success<String, Void>(null), outcome);
        assertEquals(Map.of("ann", 70, "bob", 30), balances());
    }

    @Test
    void aFailFurtherOutRollsItBack() {
        var fx = effects();
        var outcome = Fail.attempt(fx, String.class, fail -> Transaction.run(fx, db,
            sql -> transfer(sql, fail, "ann", "bob", 130)));
        assertEquals(new Fail.Failure<String, Void>("not enough money on ann"), outcome);
        assertEquals(Map.of("ann", 100, "bob", 0), balances()); // the debit, done before the failure, rolled back
    }

    @Test
    void anExceptionRollsItBack() {
        var thrown = assertThrows(IllegalStateException.class, () -> Transaction.run(effects(), db, sql -> {
            Sql.update(sql, "update account set balance = 0 where id = ?", "ann");
            throw new IllegalStateException("crash");
        }));
        assertEquals("crash", thrown.getMessage());
        assertEquals(Map.of("ann", 100, "bob", 0), balances());
    }

    @Test
    void aFailedStatementFailsTheTransaction() {
        var thrown = assertThrows(Sql.Failed.class, () -> Transaction.run(effects(), db, sql -> {
            Sql.update(sql, "update account set balance = 0 where id = ?", "ann");
            return Sql.update(sql, "insert into account values (?, ?)", "bob", 1); // duplicate key
        }));
        assertTrue(thrown.getMessage().startsWith("insert into account"));
        assertEquals(Map.of("ann", 100, "bob", 0), balances());
    }

    @Test
    void aReadOnlyCapabilityRefusesChanges() {
        assertThrows(Sql.Refused.class, () -> Transaction.run(effects(), db, sql -> {
            var reading = Sql.readOnly(sql);
            balance(reading, "ann");
            return Sql.update(reading, "delete from account");
        }));
        assertEquals(Map.of("ann", 100, "bob", 0), balances());
    }

    @Test
    void aLoggedCapabilityTellsEachStatement() {
        var fx = effects();
        var log = new ArrayList<String>();
        Fail.attempt(fx, String.class, fail -> Transaction.run(fx, db,
            sql -> transfer(Sql.logged(sql, log::add), fail, "ann", "bob", 10)));
        assertEquals(List.of(
            "update account set balance = balance - ? where id = ? [10, ann]",
            "select balance from account where id = ? [ann]",
            "update account set balance = balance + ? where id = ? [10, bob]"), log);
    }
}
