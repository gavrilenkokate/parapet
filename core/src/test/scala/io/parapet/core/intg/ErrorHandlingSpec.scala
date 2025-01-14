package io.parapet.core.intg

import cats.effect.IO
import io.parapet.core.Event._
import io.parapet.core.exceptions.EventHandlingException
import io.parapet.core.intg.ErrorHandlingSpec._
import io.parapet.core.processes.DeadLetterProcess
import io.parapet.core.testutils.{EventStore, IntegrationSpec}
import io.parapet.core.{Event, Process}
import org.scalatest.Matchers.{matchPattern, empty => _, _}
import org.scalatest.OptionValues._
import org.scalatest.WordSpec

class ErrorHandlingSpec extends WordSpec with IntegrationSpec {

  import dsl._

  "System" when {
    "process failed to handle event" should {
      "send Failure event to the sender" in {
        val clientEventStore = new EventStore[Failure]
        val client = new Process[IO] {
          def handle: Receive = {
            case Start => Request ~> faultyServer
            case f: Failure => eval(clientEventStore.add(ref, f))
          }
        }

        val processes = Array(client, faultyServer)

        val program = for {
          fiber <- run(processes).start
          _ <- clientEventStore.awaitSizeOld(1).guaranteeCase(_ => fiber.cancel)

        } yield ()
        program.unsafeRunSync()

        clientEventStore.size shouldBe 1
        clientEventStore.get(client.ref).headOption.value should matchPattern {
          case Failure(Envelope(client.`ref`, Request, faultyServer.`ref`), _: EventHandlingException) =>
        }
      }
    }
  }

  "System" when {
    "process doesn't have error handling" should {
      "send Failure event to dead letter" in {
        val deadLetterEventStore = new EventStore[DeadLetter]
        val deadLetter = new DeadLetterProcess[IO] {
          def handle: Receive = {
            case f: DeadLetter => eval(deadLetterEventStore.add(ref, f))
          }
        }
        val server = new Process[IO] {
          def handle: Receive = {
            case Request => eval(throw new RuntimeException("server is down"))
          }
        }
        val client = new Process[IO] {
          def handle: Receive = {
            case Start => Request ~> server
          }
        }

        val processes = Array(client, server)

        val program = for {
          fiber <- run(processes, unit, Some(deadLetter)).start
          _ <- deadLetterEventStore.awaitSizeOld(1).guaranteeCase(_ => fiber.cancel)

        } yield ()
        program.unsafeRunSync()

        deadLetterEventStore.size shouldBe 1
        deadLetterEventStore.get(deadLetter.ref).headOption.value should matchPattern {
          case DeadLetter(Envelope(client.`ref`, Request, server.`ref`), _: EventHandlingException) =>
        }

      }
    }
  }

  "System" when {
    "process failed to handle Failure event" should {
      "send Failure event to dead letter" in {
        val deadLetterEventStore = new EventStore[DeadLetter]
        val deadLetter = new DeadLetterProcess[IO] {
          def handle: Receive = {
            case f: DeadLetter => eval(deadLetterEventStore.add(ref, f))
          }
        }
        val server = new Process[IO] {
          def handle: Receive = {
            case Request => eval(throw new RuntimeException("server is down"))
          }
        }
        val client = new Process[IO] {
          def handle: Receive = {
            case Start => Request ~> server
            case _: Failure => eval(throw new RuntimeException("client failed to handle error"))
          }
        }

        val processes = Array(client, server)

        val program = for {
          fiber <- run(processes, unit, Some(deadLetter)).start
          _ <- deadLetterEventStore.awaitSizeOld(1).guaranteeCase(_ => fiber.cancel)

        } yield ()
        program.unsafeRunSync()

        deadLetterEventStore.size shouldBe 1
        deadLetterEventStore.get(deadLetter.ref).headOption.value should matchPattern {
          case DeadLetter(Envelope(client.`ref`, Request, server.`ref`), _: EventHandlingException) =>
        }
      }
    }
  }

}

object ErrorHandlingSpec {

  val faultyServer: Process[IO] = new Process[IO] {

    import dsl._

    def handle: Receive = {
      case Request => eval(throw new RuntimeException("server is down"))
    }
  }

  object Request extends Event

}
