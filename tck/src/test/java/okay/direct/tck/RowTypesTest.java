package okay.direct.tck;

import okay.effect.Eff;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** What the row's types refuse: each program here is compiled, and javac must reject it. */
class RowTypesTest {
    private static final String HEADER = """
            import okay.effect.*;
            import okay.direct.*;
            import okay.direct.std.*;
            class Snippet {
                static int step(Fx<? extends Eff<State<Integer>, ? extends Eff<Reader<Integer>, ?>>> fx) {
                    return State.get(Fx.head(fx)) + Reader.ask(Fx.second(fx));
                }
            """;

    static List<String> errors(String body) throws IOException {
        var javac = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var source = new SimpleJavaFileObject(URI.create("string:///Snippet.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return HEADER + body + "\n}\n";
            }
        };
        var out = Files.createTempDirectory("rows");
        var options = new ArrayList<>(List.of("-d", out.toString(), "-cp", System.getProperty("java.class.path")));
        javac.getTask(null, null, diagnostics, options, null, List.of(source)).call();
        return diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> d.getMessage(null))
                .collect(Collectors.toList());
    }

    @Test
    void theRightProgramCompiles() throws IOException {
        var errors = errors("""
                static Object ok(Effects e) { return Fx.run(e, Reader.run(1, State.run(0, Snippet::step))); }
                """);
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // a place read as another effect than the row says
        "static void wrongPlace(Fx<Eff<State<Integer>, Pure>> fx) { Effect<Reader<Integer>> r = Fx.head(fx); }",
        // a place the row has not
        "static void noPlace(Fx<Eff<State<Integer>, Pure>> fx) { Fx.second(fx); }",
        // the empty row has no first place
        "static void empty(Fx<Pure> fx) { Fx.head(fx); }",
        // handlers in another order than the program's row
        "static Object order(Effects e) { return Fx.run(e, State.run(0, Reader.run(1, Snippet::step))); }",
        // a state of another type
        "static Object type(Effects e) { return Fx.run(e, Reader.run(1, State.run(\"\", Snippet::step))); }",
        // a place left unhandled: run takes only a program of Pure
        "static Object unhandled(Effects e) { return Fx.run(e, State.run(0, Snippet::step)); }",
    })
    void theTypesRefuse(String body) throws IOException {
        assertTrue(!errors(body).isEmpty(), "compiled, but should not: " + body);
    }
}
