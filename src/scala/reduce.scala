package reduce

import akka.actor.{Actor, ActorRef, Props}
import akka.routing.ConsistentHashingRouter.ConsistentHashMapping

/**
  * Result container produced by a MapReduce execution engine.
  */
final case class EngineResult[K, R](data: Map[K, R]) {
  def apply(key: K): R = data(key)
  def get(key: K): Option[R] = data.get(key)
  def getOrElse(key: K, default: => R): R = data.getOrElse(key, default)
  def contains(key: K): Boolean = data.contains(key)
  def size: Int = data.size
  def isEmpty: Boolean = data.isEmpty
  def nonEmpty: Boolean = data.nonEmpty
  def toMap: Map[K, R] = data
}

/**
  * Intermediate key-value pair emitted during the Map phase and routed to a [[ReduceWorkerActor]].
  */
final case class MappedPair[K, V](key: K, value: V)

/**
  * Control messages for synchronizing map and reduce stages.
  */
case object Flush
case object Done
case object GetPartialResult

/**
  * Pluggable reduction specification for reducing intermediate values `V` per key `K` into `R`.
  */
trait Reducer[K, V, R] extends Serializable {

  /**
    * Reduces a non-empty collection of intermediate values `V` for `key` into a reduced value `R`.
    */
  def reduce(key: K, values: Iterable[V]): R

  /**
    * Combines two partial reduced results `R` for the same key across partitions.
    */
  def combine(left: R, right: R): R

  /**
    * Incrementally folds a single intermediate value `V` into an optional existing accumulator `R`.
    */
  def accumulate(key: K, current: Option[R], value: V): R = current match {
    case Some(existing) => combine(existing, reduce(key, Seq(value)))
    case None           => reduce(key, Seq(value))
  }
}

object Reducer {

  def hashMapping: ConsistentHashMapping = {
    case MappedPair(key, _) => key
  }

  /**
    * Constructs a [[Reducer]] from explicit initial conversion, accumulation, and combine functions.
    */
  def define[K, V, R](
    initial: V => R,
    accumulator: (R, V) => R,
    combiner: (R, R) => R
  ): Reducer[K, V, R] = new Reducer[K, V, R] {
    override def reduce(key: K, values: Iterable[V]): R = {
      val iter = values.iterator
      val first = initial(iter.next())
      iter.foldLeft(first)(accumulator)
    }

    override def accumulate(key: K, current: Option[R], value: V): R = current match {
      case Some(existing) => accumulator(existing, value)
      case None           => initial(value)
    }

    override def combine(left: R, right: R): R = combiner(left, right)
  }

  /**
    * Constructs a [[Reducer]] when the intermediate value `V` and reduced value `R` are the same type.
    */
  def fromFunction[K, V](fn: (V, V) => V): Reducer[K, V, V] =
    define[K, V, V](
      initial = identity,
      accumulator = fn,
      combiner = fn
    )
}

object ReduceWorkerActor {
  def props[K, V, R](
    reducer: Reducer[K, V, R],
    numMappers: Int = 1,
    listener: Option[ActorRef] = None
  ): Props = Props(new ReduceWorkerActor[K, V, R](reducer, numMappers, listener))
}

/**
  * Akka worker actor that accumulates `MappedPair[K, V]` messages using a pluggable [[Reducer]]
  * and emits its partitioned [[EngineResult]] once all mappers have sent [[Flush]].
  */
class ReduceWorkerActor[K, V, R](
  reducer: Reducer[K, V, R],
  numMappers: Int = 1,
  listener: Option[ActorRef] = None
) extends Actor {

  private var remainingMappers: Int = math.max(1, numMappers)
  private var partitionState: Map[K, R] = Map.empty[K, R]

  override def receive: Receive = {
    case MappedPair(rawKey, rawValue) =>
      val key = rawKey.asInstanceOf[K]
      val value = rawValue.asInstanceOf[V]
      val next = reducer.accumulate(key, partitionState.get(key), value)
      partitionState = partitionState.updated(key, next)

    case GetPartialResult =>
      sender() ! EngineResult(partitionState)

    case Flush =>
      remainingMappers -= 1
      if (remainingMappers <= 0) {
        val target = listener.getOrElse(context.parent)
        target ! EngineResult(partitionState)
        target ! Done
        remainingMappers = math.max(1, numMappers)
      }
  }
}
