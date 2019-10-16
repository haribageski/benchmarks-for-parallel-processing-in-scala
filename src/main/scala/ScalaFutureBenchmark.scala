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


  val combinator = new Monoid[Long] {
    override def empty = 0l
    override def combine(x: Long, y: Long) = {
      x
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
    performance of "map then flatMap over each Future in an Iterable" config(
      Key.exec.minWarmupRuns -> 4,
      Key.exec.maxWarmupRuns -> 4,
      Key.exec.benchRuns -> 10,
      Key.verbose -> true
    ) in {
      using(Gen.crossProduct(running, iterable)) in {
        r: (Long, Iterable[Int]) =>
          val futures = r._2.map(_ => Future(timeConsumingOperation(r._1)))
            .map(_.map(_ => timeConsumingOperation(r._1)))
            .map(_.flatMap(_ => Future(timeConsumingOperation(r._1))))
          Await.result(
            Future.sequence(futures),
            Duration.Inf
          )
      }
    }
  }

  performance of "Monix Task" in {
    performance of "map then flatMap over each Task in an Iterable using gather" config(
      Key.exec.minWarmupRuns -> 4,
      Key.exec.maxWarmupRuns -> 4,
      Key.exec.benchRuns -> 10,
      Key.verbose -> true
    ) in {
      using(Gen.crossProduct(running, iterable)) in {
        r: (Long, Iterable[Int]) =>
          val tasks = r._2.map(_ => Task.eval(timeConsumingOperation(r._1)))
            .map(_.map(_ => timeConsumingOperation(r._1)))
            .map(_.flatMap(_ => Task.eval(timeConsumingOperation(r._1))))
          Task.gather(tasks).runSyncUnsafe()
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

}