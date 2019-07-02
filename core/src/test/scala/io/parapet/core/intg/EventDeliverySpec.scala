package io.parapet.core.intg

import cats.effect.IO
import io.parapet.core.Dsl.WithDsl
import io.parapet.core.Event._
import io.parapet.core.exceptions.EventMatchException
import io.parapet.core.{Event, Process, ProcessRef}
import io.parapet.core.intg.EventDeliverySpec._
import io.parapet.core.processes.DeadLetterProcess
import io.parapet.core.testutils.{EventStore, IntegrationSpec}
import io.parapet.implicits._
import org.scalatest.FlatSpec
import org.scalatest.Matchers.{empty => _, _}
import org.scalatest.OptionValues._

class EventDeliverySpec extends FlatSpec with IntegrationSpec with WithDsl[IO] {

  import flowDsl._
  import effectDsl._

  "Event" should "be sent to correct process" in {
    val eventStore = new EventStore[QualifiedEvent]
    val numOfProcesses = 10
    val processes =
      createProcesses(numOfProcesses, eventStore)

    val events = processes.map(p => p.selfRef -> QualifiedEvent(p.selfRef)).toMap

    val sendEvents = events.foldLeft(empty) {
      case (acc, (pRef, event)) => acc ++ event ~> pRef
    }

    val program = for {
      fiber <- run(sendEvents, processes.toArray).start
      _ <- eventStore.awaitSize(10).guaranteeCase(_ => fiber.cancel)
    } yield ()

    program.unsafeRunSync()

    eventStore.size shouldBe events.size

    events.foreach {
      case (pRef, event) =>
        eventStore.get(pRef).size shouldBe 1
        eventStore.get(pRef).headOption.value shouldBe event
    }

  }

  "Unmatched event" should "be sent to deadletter" in {
    val deadLetterEventStore = new EventStore[DeadLetter]
    val deadLetter = new DeadLetterProcess[IO] {
      def handle: Receive = {
        case f: DeadLetter => eval(deadLetterEventStore.add(selfRef, f))
      }
    }

    val server = new Process[IO] {
      def handle: Receive = {
        case Start => empty
      }
    }

    val client = new Process[IO] {
      def handle: Receive = {
        case Start => UnknownEvent ~> server
      }
    }

    val processes = Array(client, server)

    val program = for {
      fiber <- run(empty, processes, Some(deadLetter)).start
      _ <- deadLetterEventStore.awaitSize(1).guaranteeCase(_ => fiber.cancel)

    } yield ()
    program.unsafeRunSync()

    deadLetterEventStore.size shouldBe 1
    deadLetterEventStore.get(deadLetter.selfRef).headOption.value should matchPattern {
      case DeadLetter(Envelope(client.selfRef, UnknownEvent, server.selfRef), _: EventMatchException) =>
    }
  }

}

object EventDeliverySpec {

  case class QualifiedEvent(pRef: ProcessRef) extends Event

  object UnknownEvent extends Event

  def createProcesses(numOfProcesses: Int, eventStore: EventStore[QualifiedEvent]): Seq[Process[IO]] = {
    (0 until numOfProcesses).map { i =>
      new Process[IO] {

        import effectDsl._

        override val name: String = s"p-$i"
        override def handle: Receive = {
          case e: QualifiedEvent => eval(eventStore.add(selfRef, e))
        }
      }
    }
  }
}