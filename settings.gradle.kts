rootProject.name = "okay-direct"

// api: perform and handle, of no implementation; std: State, Reader, Fail, Choose on the api; tck: what every
// implementation does, as tests to extend; loom, threads: implementations
include("api", "std", "tck", "loom", "threads", "espresso", "scala", "scala2")

// interop of the front ends: cats-effect and ZIO, for each Scala
include("scala-cats", "scala-zio", "scala2-cats", "scala2-zio")
