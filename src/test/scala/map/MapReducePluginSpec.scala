package map

import akka.actor.ActorSystem
import akka.routing.Broadcast
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import reduce.{Done, EngineResult, Flush, GetPartialResult, MappedPair, ReduceWorkerActor, Reducer}

class MapReducePluginSpec
    extends TestKit(ActorSystem("MapReducePluginSpecSystem"))
    with ImplicitSender
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with ScalaFutures {

  implicit val defaultPatience: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(50, Millis))

  import system.dispatcher

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  final case class Summary(count: Int, total: Int)

  "Mapper and MapWorkerActor" should {
    "transform InputRecord messages into MappedPair messages and broadcast Flush" in {
      val probe = TestProbe()
      val mapper = Mapper[String, String, Int] { line =>
        line.split("\\s+").filter(_.nonEmpty).map(w => w -> 1)
      }
      val worker = system.actorOf(MapWorkerActor.props(mapper, probe.ref))

      worker ! InputRecord("scala akka")
      probe.expectMsg(MappedPair("scala", 1))
      probe.expectMsg(MappedPair("akka", 1))

      worker ! Flush
      probe.expectMsg(Broadcast(Flush))
    }
  }

  "Reducer and ReduceWorkerActor" should {
    "accumulate MappedPair messages and emit EngineResult + Done once all mappers Flush" in {
      val listener = TestProbe()
      val reducer = Reducer.fromFunction[String, Int](_ + _)
      val worker = system.actorOf(
        ReduceWorkerActor.props(reducer, numMappers = 2, listener = Some(listener.ref))
      )

      worker ! MappedPair("scala", 2)
      worker ! MappedPair("scala", 3)
      worker ! MappedPair("sbt", 1)

      worker ! GetPartialResult
      expectMsg(EngineResult(Map("scala" -> 5, "sbt" -> 1)))

      worker ! Flush
      listener.expectNoMessage()

      worker ! Flush
      listener.expectMsg(EngineResult(Map("scala" -> 5, "sbt" -> 1)))
      listener.expectMsg(Done)
    }
  }

  "Pluggable MapReduceEngine implementations" should {
    "produce identical results across AkkaMapReduceEngine, AsyncMapReduceEngine, and InMemoryMapReduceEngine" in {
      final case class Order(region: String, amount: Int)

      val program: MapReduce[Order, String, Int, Summary] =
        MapReduce.define[Order, String, Int, Summary](
          order => Seq(order.region -> order.amount)
        )(
          initial = amount => Summary(1, amount),
          accumulator = (acc, amount) => Summary(acc.count + 1, acc.total + amount),
          combiner = (left, right) => Summary(left.count + right.count, left.total + right.total)
        )

      val orders = Seq(
        Order("NA", 100),
        Order("EU", 250),
        Order("NA", 150),
        Order("APAC", 300),
        Order("EU", 50)
      )

      val expected = Map(
        "NA" -> Summary(2, 250),
        "EU" -> Summary(2, 300),
        "APAC" -> Summary(1, 300)
      )

      val akkaEngine = AkkaMapReduceEngine(system, numMappers = 2, numReducers = 2)
      val asyncEngine = AsyncMapReduceEngine(parallelism = 2)

      val akkaResult = MapReduce(program)(akkaEngine)(orders).futureValue
      akkaResult.data shouldBe expected
      akkaResult("NA") shouldBe Summary(2, 250)

      val asyncResult = MapReduce(program)(asyncEngine)(orders).futureValue
      asyncResult.data shouldBe expected

      val inMemoryResult = MapReducePlugin(program)(InMemoryMapReduceEngine)(orders).futureValue
      inMemoryResult.data shouldBe expected
    }

    "support word-count style programs via MapReduce.fromMapperReducer and MapReducePlugin.runProgram" in {
      val wordCount = MapReduce.fromMapperReducer[String, String, Int] { line =>
        line.split("\\s+").filter(_.nonEmpty).map(w => w.toLowerCase -> 1)
      }(_ + _)

      val lines = Seq("Scala Akka Scala", "Sbt Scala Akka")
      val result = MapReducePlugin.runProgram(wordCount, lines, AkkaMapReduceEngine(system, 2, 2))

      result.toMap shouldBe Map(
        "scala" -> 3,
        "akka" -> 2,
        "sbt" -> 1
      )
    }
  }
}
