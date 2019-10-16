import cats.Monoid
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.{Consumer, Observable}
import org.scalameter.Key
import org.scalameter.api._
import org.scalameter.execution.LocalExecutor
import org.scalameter.picklers.Implicits._

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.reflect.ClassTag

object ScalaFutureBenchmark extends Bench.LocalTime {

  implicit val scheduler =
    Scheduler.computation()

  override val aggregator = Aggregator.average
  override val executor = LocalExecutor(
    new Executor.Warmer.Default,
    Aggregator.average,
    new Measurer.Default)

  val sizes = Gen.enumeration("size")(10, 100, 1000, 10000, 50000)
  val iterable = for {
    size <- sizes
  } yield Iterable.range(0, size)

  val running = Gen.enumeration("running")(1000l, 10000l, 100000l, 300000l)

  def timeConsumingOperation(i: Long): Long = {
    var x = 1l
    while (x < i) {
      x = x + 1
    }
    i
  }

  def parFold[A: ClassTag](iterable: Iterable[A], monoid: Monoid[A]): Task[A] = {
    val array: Array[A] = iterable.toArray

    def stackSafeParFold(from: Int, to: Int): Task[A] = {
      if (from == to) {
        Task.now(array(from))
      } else {
        Task.defer {
          Task.parMap2(stackSafeParFold(from, (from + to) / 2), stackSafeParFold((from + to) / 2 + 1, to))(monoid.combine)
        }
      }
    }

    if (array.isEmpty) {
      Task.now(monoid.empty)
    } else {
      stackSafeParFold(0, array.length - 1)
    }

  }

  val combinator = new Monoid[Long] {
    override def empty = 0l
    override def combine(x: Long, y: Long) = {
      timeConsumingOperation(100000l)
      x - y
    }
  }

  //------------------------ benchmarking ------------------------------------
  performance of "TimeConsumingOperation" in {
    measure method "timeConsumingOperation" config(
      Key.exec.minWarmupRuns -> 4,
      Key.exec.maxWarmupRuns -> 4,
      Key.exec.benchRuns -> 10,
      Key.verbose -> false
    ) in {
      using(running) in {
        r =>
          timeConsumingOperation(r)
      }
    }
  }

  performance of "Scala Future" in {
    performance of "traverse then twice flatMap" config(
      Key.exec.minWarmupRuns -> 4,
      Key.exec.maxWarmupRuns -> 4,
      Key.exec.benchRuns -> 10,
      Key.verbose -> true
    ) in {
      using(Gen.crossProduct(running, iterable)) in {
        r: (Long, Iterable[Int]) =>
          Await.result(
            Future.traverse(r._2)(_ => Future(timeConsumingOperation(r._1)))
              .flatMap(iter => Future.traverse(r._2)(_ => Future(timeConsumingOperation(r._1))))
              .flatMap(iter => Future.traverse(r._2)(_ => Future(timeConsumingOperation(r._1)))),
            Duration.Inf
          )
      }
    }
  }

  performance of "Monix Task" in {
    performance of "wander then twice flatMap" config(
      Key.exec.minWarmupRuns -> 4,
      Key.exec.maxWarmupRuns -> 4,
      Key.exec.benchRuns -> 10,
      Key.verbose -> true
    ) in {
      using(Gen.crossProduct(running, iterable)) in {
        r: (Long, Iterable[Int]) =>
          Task.wander(r._2)(_ => Task.eval(timeConsumingOperation(r._1)))
            .flatMap(Task.wander(_)(_ => Task.eval(timeConsumingOperation(r._1))))
            .flatMap(Task.wander(_)(_ => Task.eval(timeConsumingOperation(r._1))))
            .runSyncUnsafe()
      }
    }
  }

  performance of "ParIterable" in {
    measure method "map" config(
      Key.exec.minWarmupRuns -> 4,
      Key.exec.maxWarmupRuns -> 4,
      Key.exec.benchRuns -> 10,
      Key.verbose -> true
    ) in {
      using(Gen.crossProduct(running, iterable)) in {
        r: (Long, Iterable[Int]) =>
          r._2.par
            .map(_ => timeConsumingOperation(r._1))
            .map(_ => timeConsumingOperation(r._1))
            .map(_ => timeConsumingOperation(r._1))
      }
    }
  }

  performance of "Monix Observable" in {
    measure method "mapParallelOrdered" config(
      Key.exec.minWarmupRuns -> 4,
      Key.exec.maxWarmupRuns -> 4,
      Key.exec.benchRuns -> 10,
      Key.verbose -> true
    ) in {
      using(Gen.crossProduct(running, iterable)) in {
        r: (Long, Iterable[Int]) =>
          Observable.fromIterable(r._2)
            .mapParallelOrdered(16)(_ => Task.eval(timeConsumingOperation(r._1)))
            .mapParallelOrdered(16)(_ => Task.eval(timeConsumingOperation(r._1)))
            .mapParallelOrdered(16)(_ => Task.eval(timeConsumingOperation(r._1)))
            .consumeWith(Consumer.complete)
            .runSyncUnsafe()
      }
    }
  }

  performance of "ParIterable" in {
    measure method "fold" config(
      Key.exec.minWarmupRuns -> 2,
      Key.exec.maxWarmupRuns -> 2,
      Key.exec.benchRuns -> 3,
      Key.verbose -> true
    ) in {
      using(running) in {
        r: (Long) =>
          Iterable.range(0l, 100000l).par.fold(0l)((x, y) => {
            timeConsumingOperation(100000l)
            x - y
          })
      }
    }
  }

  performance of "Monix Observable" in {
    measure method "fold" config(
      Key.exec.minWarmupRuns -> 2,
      Key.exec.maxWarmupRuns -> 2,
      Key.exec.benchRuns -> 3,
      Key.verbose -> true
    ) in {
      using(running) in {
        r: (Long) =>
          Await.result(
            Observable.range(0, 100000)
              .fold(combinator)
              .runAsyncGetFirst,
            Duration.Inf
          )
      }
    }
  }

  val combinerMonoid = new Monoid[Long] {
    override def empty = 0l
    override def combine(x: Long, y: Long) = {
      timeConsumingOperation(100000l)
      x - y
    }
  }

  performance of "Monix Task" in {
    measure method "parFolding" config(
      Key.exec.minWarmupRuns -> 2,
      Key.exec.maxWarmupRuns -> 2,
      Key.exec.benchRuns -> 3,
      Key.verbose -> true
    ) in {
      using(running) in {
        r: (Long) =>
          parFold(
            Iterable.range(0l, 100000l),
            combinerMonoid
          ).runSyncUnsafe()
      }
    }
  }

}