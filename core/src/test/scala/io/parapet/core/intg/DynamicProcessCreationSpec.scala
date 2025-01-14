package io.parapet.core.intg

import cats.effect.IO
import cats.implicits._
import io.parapet.core.Event.Start
import io.parapet.core.intg.DynamicProcessCreationSpec._
import io.parapet.core.testutils.{EventStore, IntegrationSpec}
import io.parapet.core.{Event, Process, ProcessRef}
import org.scalatest.FunSuite
import org.scalatest.Matchers._

class DynamicProcessCreationSpec extends FunSuite with IntegrationSpec {

  test("create child process") {

    val eventStore = new EventStore[Event]

    val workersCount = 5
    val tasksCount = 5
    val db = new Database
    val server = new Server(workersCount, db.ref, tasksCount, eventStore)

    val program = for {
      fiber <- run(Array[Process[IO]](db, server)).start
      _ <- eventStore.awaitSizeOld(workersCount * tasksCount).guaranteeCase(_ => fiber.cancel)
    } yield ()
    program.unsafeRunSync()

    val expectedEvents = (tasksCount to 1 by -1).map(i => Ack(i))

    eventStore.size shouldBe workersCount * tasksCount

    (0 until workersCount).foreach { i =>
      eventStore.get(ProcessRef(s"worker-$i")) shouldBe expectedEvents
    }

  }

}

object DynamicProcessCreationSpec {

  class Worker(id: Int,
               db: ProcessRef,
               tasksCount: Int,
               eventStore: EventStore[Event]) extends Process[IO] {

    import dsl._

    override val name: String = s"worker-$id"
    override val ref: ProcessRef = ProcessRef(s"worker-$id")

    override def handle: Receive = {
      case Start => Persist(tasksCount) ~> db
      case a@Ack(i) => if (i == 0) unit // done
      else eval(eventStore.add(ref, a)) ++ Persist(i - 1) ~> db
    }
  }

  class Database extends Process[IO] {

    import dsl._

    override def handle: Receive = {
      case Persist(id) => withSender(sender => Ack(id) ~> sender)
    }
  }

  class Server(workersCount: Int,
               db: ProcessRef,
               tasksCount: Int,
               eventStore: EventStore[Event]) extends Process[IO] {

    import dsl._

    override def handle: Receive = {
      case Start =>
        (0 until workersCount).map(i => register(ref, new Worker(i, db, tasksCount, eventStore)).void).fold(unit)(_ ++ _)
    }
  }

  case class Persist(id: Int) extends Event

  case class Ack(id: Int) extends Event

}