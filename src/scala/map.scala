package map

import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props}
import akka.pattern.ask
import akka.routing.{Broadcast, ConsistentHashingPool, RoundRobinPool}
import akka.util.Timeout
import reduce.{Done, EngineResult, Flush, MappedPair, ReduceWorkerActor, Reducer}
import sbt._
import sbt.plugins.JvmPlugin

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.reflect.ClassTag

/**
  * Wrapper message delivering a single input record `I` to a [[MapWorkerActor]].
  */
final case class InputRecord[I](value: I)

/**
  * Pluggable map specification transforming an input `I` into intermediate `(K, V)` pairs.
  */
trait Mapper[I, K, V] extends Serializable {
  def map(input: I): Iterable[(K, V)]
}

object Mapper {
  def apply[I, K, V](fn: I => Iterable[(K, V)]): Mapper[I, K, V] = new Mapper[I, K, V] {
    override def map(input: I): Iterable[(K, V)] = fn(input)
  }
}

/**
  * Pluggable specification of a complete MapReduce program combining a [[Mapper]] and a [[Reducer]]:
  *   - `I`: Input record type
  *   - `K`: Intermediate / output key type
  *   - `V`: Intermediate mapped value type
  *   - `R`: Reduced output value type per key `K`
  */
trait MapReduce[I, K, V, R] extends Mapper[I, K, V] with Reducer[K, V, R]

/**
  * Pluggable execution engine capable of executing a [[MapReduce]] program over a sequence of inputs.
  */
trait MapReduceEngine {
  def apply[I: ClassTag, K: ClassTag, V: ClassTag, R: ClassTag](
    program: MapReduce[I, K, V, R]
  )(data: Seq[I]): Future[EngineResult[K, R]]
}

object MapWorkerActor {
  def props[I, K, V](mapper: Mapper[I, K, V], reduceRouter: ActorRef): Props =
    Props(new MapWorkerActor[I, K, V](mapper, reduceRouter))
}

/**
  * Akka worker actor that transforms `InputRecord[I]` messages into `MappedPair[K, V]` messages
  * using a pluggable [[Mapper]] and forwards them to the consistent-hashing reduce router.
  */
class MapWorkerActor[I, K, V](mapper: Mapper[I, K, V], reduceRouter: ActorRef) extends Actor {
  override def receive: Receive = {
    case InputRecord(rawInput) =>
      val input = rawInput.asInstanceOf[I]
      for ((key, value) <- mapper.map(input)) {
        reduceRouter ! MappedPair(key, value)
      }

    case Flush =>
      reduceRouter ! Broadcast(Flush)
  }
}

object MasterCoordinatorActor {
  final case class ProcessInputs[I](inputs: Seq[I])

  def props[I, K, V, R](
    program: MapReduce[I, K, V, R],
    numMappers: Int = 4,
    numReducers: Int = 4
  ): Props = Props(new MasterCoordinatorActor[I, K, V, R](program, numMappers, numReducers))
}

/**
  * Supervises a `RoundRobinPool` of [[MapWorkerActor]]s and a `ConsistentHashingPool`
  * of [[ReduceWorkerActor]]s to execute a [[MapReduce]] program.
  */
class MasterCoordinatorActor[I, K, V, R](
  program: MapReduce[I, K, V, R],
  numMappers: Int = 4,
  numReducers: Int = 4
) extends Actor {
  import MasterCoordinatorActor._

  private val effectiveMappers: Int = math.max(1, numMappers)
  private val effectiveReducers: Int = math.max(1, numReducers)

  private val reduceRouter: ActorRef = context.actorOf(
    ConsistentHashingPool(effectiveReducers, hashMapping = Reducer.hashMapping).props(
      ReduceWorkerActor.props[K, V, R](
        reducer = program,
        numMappers = effectiveMappers,
        listener = Some(self)
      )
    ),
    "reduce-router"
  )

  private val mapRouter: ActorRef = context.actorOf(
    RoundRobinPool(effectiveMappers).props(
      MapWorkerActor.props[I, K, V](program, reduceRouter)
    ),
    "map-router"
  )

  private var pendingReducers: Int = effectiveReducers
  private var aggregated: Map[K, R] = Map.empty[K, R]
  private var listeners: List[ActorRef] = Nil

  override def receive: Receive = {
    case ProcessInputs(inputs) =>
      listeners = sender() :: listeners
      inputs.foreach(item => mapRouter ! InputRecord(item))
      mapRouter ! Broadcast(Flush)

    case EngineResult(partial) =>
      partial.asInstanceOf[Map[K, R]].foreach { case (key, value) =>
        val merged = aggregated.get(key) match {
          case Some(existing) => program.combine(existing, value)
          case None           => value
        }
        aggregated = aggregated.updated(key, merged)
      }

    case Done =>
      pendingReducers -= 1
      if (pendingReducers <= 0) {
        val result = EngineResult(aggregated)
        listeners.foreach(_ ! result)
        listeners = Nil
        pendingReducers = effectiveReducers
      }
  }
}

/**
  * Akka actor-pipeline implementation of [[MapReduceEngine]].
  */
class AkkaMapReduceEngine(
  externalSystem: Option[ActorSystem] = None,
  val numMappers: Int = 4,
  val numReducers: Int = 4,
  val timeoutDuration: FiniteDuration = 10.seconds
) extends MapReduceEngine {

  override def apply[I: ClassTag, K: ClassTag, V: ClassTag, R: ClassTag](
    program: MapReduce[I, K, V, R]
  )(data: Seq[I]): Future[EngineResult[K, R]] = {
    implicit val timeout: Timeout = Timeout(timeoutDuration)
    val ownsSystem = externalSystem.isEmpty
    val system = externalSystem.getOrElse(ActorSystem("MapReducePluginSystem"))
    implicit val ec: ExecutionContext = system.dispatcher

    val coordinator = system.actorOf(
      MasterCoordinatorActor.props(
        program = program,
        numMappers = numMappers,
        numReducers = numReducers
      )
    )

    val resultFuture = (coordinator ? MasterCoordinatorActor.ProcessInputs(data)).mapTo[EngineResult[K, R]]
    resultFuture.andThen {
      case _ =>
        if (ownsSystem) {
          system.terminate()
        } else {
          coordinator ! PoisonPill
        }
    }
  }
}

object AkkaMapReduceEngine {
  def apply(
    system: ActorSystem,
    numMappers: Int = 4,
    numReducers: Int = 4,
    timeoutDuration: FiniteDuration = 10.seconds
  ): AkkaMapReduceEngine =
    new AkkaMapReduceEngine(
      externalSystem = Some(system),
      numMappers = numMappers,
      numReducers = numReducers,
      timeoutDuration = timeoutDuration
    )

  def standalone(
    numMappers: Int = 4,
    numReducers: Int = 4,
    timeoutDuration: FiniteDuration = 10.seconds
  ): AkkaMapReduceEngine =
    new AkkaMapReduceEngine(
      externalSystem = None,
      numMappers = numMappers,
      numReducers = numReducers,
      timeoutDuration = timeoutDuration
    )
}

/**
  * Asynchronous `Future`-based [[MapReduceEngine]] that partitions input records across
  * parallel shards, reduces each shard concurrently, and combines partial shard results.
  */
class AsyncMapReduceEngine(val parallelism: Int = 4)(implicit ec: ExecutionContext) extends MapReduceEngine {

  override def apply[I: ClassTag, K: ClassTag, V: ClassTag, R: ClassTag](
    program: MapReduce[I, K, V, R]
  )(data: Seq[I]): Future[EngineResult[K, R]] = {
    if (data.isEmpty) {
      Future.successful(EngineResult(Map.empty[K, R]))
    } else {
      val effectiveParallelism = math.max(1, parallelism)
      val shardSize = math.max(1, (data.size + effectiveParallelism - 1) / effectiveParallelism)
      val shardFutures = data.grouped(shardSize).toSeq.map { shard =>
        Future {
          shard.foldLeft(Map.empty[K, R]) { (acc, item) =>
            program.map(item).foldLeft(acc) { case (innerAcc, (k, v)) =>
              innerAcc.updated(k, program.accumulate(k, innerAcc.get(k), v))
            }
          }
        }
      }

      Future.sequence(shardFutures).map { partialMaps =>
        val merged = partialMaps.foldLeft(Map.empty[K, R]) { (acc, shardMap) =>
          shardMap.foldLeft(acc) { case (innerAcc, (k, r)) =>
            val next = innerAcc.get(k) match {
              case Some(existing) => program.combine(existing, r)
              case None           => r
            }
            innerAcc.updated(k, next)
          }
        }
        EngineResult(merged)
      }
    }
  }
}

object AsyncMapReduceEngine {
  def apply(parallelism: Int = 4)(implicit ec: ExecutionContext = ExecutionContext.global): AsyncMapReduceEngine =
    new AsyncMapReduceEngine(parallelism)(ec)
}

/**
  * Lightweight synchronous in-memory [[MapReduceEngine]] implementation.
  */
object InMemoryMapReduceEngine extends MapReduceEngine {
  override def apply[I: ClassTag, K: ClassTag, V: ClassTag, R: ClassTag](
    program: MapReduce[I, K, V, R]
  )(data: Seq[I]): Future[EngineResult[K, R]] = {
    val reduced = data.foldLeft(Map.empty[K, R]) { (acc, item) =>
      program.map(item).foldLeft(acc) { case (innerAcc, (k, v)) =>
        innerAcc.updated(k, program.accumulate(k, innerAcc.get(k), v))
      }
    }
    Future.successful(EngineResult(reduced))
  }
}

object MapReduce {

  /**
    * Executes `program` over `data` using the pluggable `engine`.
    */
  def apply[I: ClassTag, K: ClassTag, V: ClassTag, R: ClassTag](
    program: MapReduce[I, K, V, R]
  )(engine: MapReduceEngine)(data: Seq[I]): Future[EngineResult[K, R]] =
    engine(program)(data)

  /**
    * Composes a [[Mapper]] and a [[Reducer]] into a [[MapReduce]] program.
    */
  def fromStages[I, K, V, R](
    mapperStage: Mapper[I, K, V],
    reducerStage: Reducer[K, V, R]
  ): MapReduce[I, K, V, R] = new MapReduce[I, K, V, R] {
    override def map(input: I): Iterable[(K, V)] = mapperStage.map(input)
    override def reduce(key: K, values: Iterable[V]): R = reducerStage.reduce(key, values)
    override def accumulate(key: K, current: Option[R], value: V): R = reducerStage.accumulate(key, current, value)
    override def combine(left: R, right: R): R = reducerStage.combine(left, right)
  }

  /**
    * Creates a [[MapReduce]] program from explicit map, initial, accumulation, and combine functions.
    */
  def define[I, K, V, R](
    mapper: I => Iterable[(K, V)]
  )(
    initial: V => R,
    accumulator: (R, V) => R,
    combiner: (R, R) => R
  ): MapReduce[I, K, V, R] =
    fromStages(Mapper(mapper), Reducer.define(initial, accumulator, combiner))

  /**
    * Creates a [[MapReduce]] program when intermediate values `V` and reduced values `R` are the same type.
    */
  def fromMapperReducer[I, K, V](
    mapper: I => Iterable[(K, V)]
  )(
    reducer: (V, V) => V
  ): MapReduce[I, K, V, V] =
    fromStages(Mapper(mapper), Reducer.fromFunction(reducer))
}

object MapReducePlugin extends AutoPlugin {

  override def requires: Plugins = JvmPlugin
  override def trigger: PluginTrigger = allRequirements

  object autoImport {
    type Mapper[I, K, V] = map.Mapper[I, K, V]
    val Mapper = map.Mapper

    type Reducer[K, V, R] = reduce.Reducer[K, V, R]
    val Reducer = reduce.Reducer

    type MapReduce[I, K, V, R] = map.MapReduce[I, K, V, R]
    val MapReduce = map.MapReduce

    type MapReduceEngine = map.MapReduceEngine
    type EngineResult[K, R] = reduce.EngineResult[K, R]
    val EngineResult = reduce.EngineResult

    val AkkaMapReduceEngine = map.AkkaMapReduceEngine
    val AsyncMapReduceEngine = map.AsyncMapReduceEngine
    val InMemoryMapReduceEngine = map.InMemoryMapReduceEngine

    val mapReduceNumMappers = settingKey[Int]("Number of Map worker actors used by the default AkkaMapReduceEngine")
    val mapReduceNumReducers = settingKey[Int]("Number of Reduce worker actors used by the default AkkaMapReduceEngine")
    val mapReduceEngine = settingKey[MapReduceEngine]("Pluggable MapReduceEngine used to execute MapReduce programs")
  }

  import autoImport.{mapReduceEngine, mapReduceNumMappers, mapReduceNumReducers}

  override lazy val projectSettings: Seq[Def.Setting[_]] = Seq(
    mapReduceNumMappers := 4,
    mapReduceNumReducers := 4,
    mapReduceEngine := AkkaMapReduceEngine.standalone(
      numMappers = mapReduceNumMappers.value,
      numReducers = mapReduceNumReducers.value
    )
  )

  def apply[I: ClassTag, K: ClassTag, V: ClassTag, R: ClassTag](
    program: MapReduce[I, K, V, R]
  )(engine: MapReduceEngine)(data: Seq[I]): Future[EngineResult[K, R]] =
    MapReduce(program)(engine)(data)

  def runProgram[I: ClassTag, K: ClassTag, V: ClassTag, R: ClassTag](
    program: MapReduce[I, K, V, R],
    data: Seq[I],
    engine: MapReduceEngine = InMemoryMapReduceEngine,
    timeoutDuration: FiniteDuration = 10.seconds
  ): EngineResult[K, R] =
    Await.result(apply(program)(engine)(data), timeoutDuration)
}
