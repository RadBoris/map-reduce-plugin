package map

object MapReducePlugin extends sbt.AutoPlugin {
  // override def requires = Web && Javascript
  // override def trigger = allRequirements
  // override def projectSettings = Seq(...)

  object autoImport {
    //lazy val obfuscate = taskKey[Seq[File]]("Obfuscates the source.")
    	println("I am loaded");
  }
}

// object MapReduce extends AutoPlugin {
// 	println("I am loaded");

//   // def apply[I: ClassTag, K: ClassTag, V: ClassTag, R: ClassTag]
//   // (program: MapReduce[I, K, V, R])(engine: MapReduceEngine)(data: Seq[I]): Future[EngineResult[K, R]] = engine(program)(data)

// }
