rootProject.name = "okay-direct"

// api: perform and handle, of no implementation; std: the standard effects on the api; tck: what every
// implementation does, as tests to extend; loom, threads, espresso (multi-shot): implementations; sql: SQL as an
// effect, a transaction its handler; otel: every operation a span of OpenTelemetry
include("api", "std", "tck", "loom", "threads", "espresso", "sql", "otel")

// interop/: the Scala front ends, and their interop with cats-effect and ZIO — each Scala its own modules. The
// projects keep their names (`:scala`, ...), so the artifacts theirs (okay-direct-scala, ...)
for (module in listOf("scala", "scala2", "scala-cats", "scala-zio", "scala2-cats", "scala2-zio")) {
    include(module)
    project(":$module").projectDir = file("interop/$module")
}
