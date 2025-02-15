package io.parapet.core

import cats.InjectK
import cats.free.Free

import scala.concurrent.duration.FiniteDuration

object Dsl {

  type Dsl[F[_], A] = FlowOp[F, A]
  type DslF[F[_], A] = Free[Dsl[F, ?], A]

  // <----------- Flow ADT ----------->
  sealed trait FlowOp[F[_], A]

  case class UnitFlow[F[_]]() extends FlowOp[F, Unit]

  case class Send[F[_]](e: Event, receivers: Seq[ProcessRef]) extends FlowOp[F, Unit]

  case class Forward[F[_]](e: Event, receivers: Seq[ProcessRef]) extends FlowOp[F, Unit]

  case class Par[F[_], G[_]](flow: Free[G, Unit]) extends FlowOp[F, Unit]

  case class Delay[F[_], G[_]](duration: FiniteDuration, flow: Option[Free[G, Unit]]) extends FlowOp[F, Unit]

  case class WithSender[F[_], G[_]](f: ProcessRef => Free[G, Unit]) extends FlowOp[F, Unit]

  case class Invoke[F[_], G[_]](caller: ProcessRef, body: Free[G, Unit], callee: ProcessRef) extends FlowOp[F, Unit]

  case class Fork[F[_], G[_]](flow: Free[G, Unit]) extends FlowOp[F, Unit]

  case class Register[F[_]](parent: ProcessRef, child: Process[F]) extends FlowOp[F, Unit]

  case class Race[F[_], C[_]](first: Free[C, Unit], second: Free[C, Unit]) extends FlowOp[F, Unit]

  case class Suspend[F[_], C[_], A](thunk: () => F[A], bind: Option[A => Free[C, Unit]]) extends FlowOp[F, Unit]

  case class Eval[F[_], C[_], A](thunk: () => A, bind: Option[A => Free[C, Unit]]) extends FlowOp[F, Unit]

  /**
    * Smart constructors for FlowOp[F, _].
    *
    * @param I an injection from type constructor `F` into type constructor `C`
    * @tparam F an effect type
    * @tparam C a coproduct of FlowOp and other algebras
    */
  class FlowOps[F[_], C[_]](implicit I: InjectK[FlowOp[F, ?], C]) {

    /**
      * Semantically this operator is equivalent with `Monad.unit` and obeys the same laws.
      *
      * The following expressions are equivalent:
      * {{{
      *   event ~> process <-> unit ++ event ~> process
      *   event ~> process <-> event ~> process ++ unit
      * }}}
      */
    val unit: Free[C, Unit] = Free.inject[FlowOp[F, ?], C](UnitFlow())

    /**
      * Suspends the given flow. Semantically this operator is equivalent with `suspend` for effects.
      * This is useful for recursive flows.
      *
      * Recursive flow example for some `F[_]`:
      *
      * {{{
      *  def times[F[_]](n: Int) = {
      *    def step(remaining: Int): DslF[F, Unit] = flow {
      *      if (remaining == 0) unit
      *      else eval(print(remaining)) ++ step(remaining - 1)
      *    }
      *
      *    step(n)
      *  }
      *
      *  val process = Process[F](_ => {
      *    case Start => times(5)
      *  })
      *
      * }}}
      *
      * The code above will print {{{ 12345 }}}
      *
      * Note: it's strongly not recommended to perform any side effects within `flow` operator:
      *
      * NOT RECOMMENDED:
      *
      * {{{
      * def print(str: String) = flow {
      *   println(str)
      *   unit
      * }
      * }}}
      *
      * RECOMMENDED:
      * {{{
      * def print(str: String) = flow {
      *   eval(println(str))
      * }
      * }}}
      *
      * @param f a flow to suspend
      * @return Unit
      */
    def flow(f: => Free[C, Unit]): Free[C, Unit] = evalWith(())(_ => f)

    /**
      * Sends an event to one or more receivers.
      * Event must be delivered to all receivers in the specified order.
      *
      * Example:
      *
      * {{{
      *   send(Ping, processA, processB, processC)
      * }}}
      *
      * `Ping` event will be sent to the `processA` then `processB` and finally `processC`.
      * It's not guaranteed that `processA` will receive `Ping` event before `processC`
      * as it depends on it's processing speed and current workload.
      *
      * @param e        event to send
      * @param receiver the receiver
      * @param other    optional receivers
      * @return Unit
      */
    def send(e: Event, receiver: ProcessRef, other: ProcessRef*): Free[C, Unit] =
      Free.inject[FlowOp[F, ?], C](Send(e, receiver +: other))

    /**
      * Sends an event to the receiver using original sender reference.
      * This is useful for implementing a proxy process.
      *
      * Proxy example for some `F[_]`:
      *
      * {{{
      * val server = Process[F](_ => {
      *   case Request(body) => withSender(sender => eval(println(s"$sender-$body")))
      * })
      *
      * val proxy = Process[F](_ => {
      *   case Request(body) => forward(Request(s"proxy-$body"), server.ref)
      * })
      *
      * val client = Process.builder[F](_ => {
      *   case Start => Request("ping") ~> proxy
      * }).ref(ProcessRef("client")).build
      * }}}
      *
      * The code above will print: `client-proxy-ping`
      *
      * @param e        the event to send
      * @param receiver the receiver
      * @param other    optional receivers
      * @return Unit
      */
    def forward(e: Event, receiver: ProcessRef, other: ProcessRef*): Free[C, Unit] =
      Free.inject[FlowOp[F, ?], C](Forward(e, receiver +: other))

    /**
      * Executes operations from the given flow in parallel.
      *
      * Example:
      *
      * {{{ par(eval(print(1)) ++ eval(print(2))) }}}
      *
      * possible outputs: {{{ 12 or 21 }}}
      *
      * @param flow the flow which operations should be executed in parallel.
      * @return Unit
      */
    def par(flow: Free[C, Unit]): Free[C, Unit] = Free.inject[FlowOp[F, ?], C](Par(flow))

    /**
      * Delays every operation in the given flow for the given duration.
      *
      * For sequential flows the flowing expressions are semantically equivalent:
      * {{{
      *   delay(duration, x~>p ++ y~>p) <-> delay(duration, x~>p) ++ delay(duration, y~>p)
      *   delay(duration, x~>p ++ y~>p) <-> delay(duration) ++ x~>p ++ delay(duration) ++ y~>p
      * }}}
      *
      * For parallel flows:
      *
      * {{{
      *    delay(duration, par(x~>p ++ y~>p)) <-> delay(duration) ++ par(x~>p ++ y~>p)
      * }}}
      *
      * Note: since the following flow will be executed in parallel the second operation won't be delayed
      *
      * {{{
      *    par(delay(duration) ++ eval(print(1)))
      * }}}
      *
      * Instead use {{{ par(delay(duration, eval(print(1)))) }}}
      *
      * @param duration is the time span to wait before executing flow operations
      * @param flow     the flow which operations should be delayed
      * @return Unit
      */
    def delay(duration: FiniteDuration, flow: Free[C, Unit]): Free[C, Unit] =
      Free.inject[FlowOp[F, ?], C](Delay(duration, Some(flow)))

    /**
      * Delays any operation that follows this operation.
      *
      * Example:
      *
      * {{{
      *   delay(duration) ++ eval(println("hello from the future"))
      * }}}
      *
      * @param duration is the time span to wait before executing next operation
      * @return Unit
      */
    def delay(duration: FiniteDuration): Free[C, Unit] = Free.inject[FlowOp[F, ?], C](Delay(duration, None))

    /**
      * Accepts a callback function that takes a sender reference and produces a new flow.
      *
      * The code below will print `client says hello` :
      * {{{
      *
      * val server = Process[F](_ => {
      *   case Request(data) => withSender(sender => eval(print(s"$sender says $data")))
      * })
      *
      * val client = Process.builder[F](_ => {
      *   case Start => Request("hello") ~> server
      * }).ref(ProcessRef("client")).build
      *
      * }}}
      *
      * @param f a callback function
      * @return Unit
      */
    def withSender(f: ProcessRef => Free[C, Unit]): Free[C, Unit] = Free.inject[FlowOp[F, ?], C](WithSender(f))

    /**
      * Internal operator that allows to invoke processes explicitly.
      * See [[Process.apply(event, caller)]].
      *
      * Example:
      *
      * {{{
      * val server = Process[F](_ => {
      *   case Request(data) => withSender(sender => Response(s"echo-$data") ~> sender)
      * })
      *
      * val client = Process[F](ref => {
      *   case Start => server(ref, Request("hello"))
      *   case res: Response => eval(println(res))
      * })
      * }}}
      *
      * The code above will print: {{{ echo-hello }}}
      *
      * @param caller the caller process
      * @param body   the flow that is produced by callee process
      * @param callee the callee process
      * @return Unit
      */
    private[core] def invoke(caller: ProcessRef, body: Free[C, Unit], callee: ProcessRef): Free[C, Unit] =
      Free.inject[FlowOp[F, ?], C](Invoke(caller, body, callee))

    /**
      * Executes the given flow concurrently.
      *
      * Example:
      *
      * {{{
      * val process = Process[F](_ => {
      *   case Start => fork(eval(print(1))) ++ fork(eval(print(2)))
      * })
      * }}}
      *
      * Possible outputs: {{{  12 or 21  }}}
      *
      * @param flow the flow to run concurrently
      * @return Unit
      */
    def fork(flow: Free[C, Unit]): Free[C, Unit] = Free.inject[FlowOp[F, ?], C](Fork(flow))

    /**
      * Registers a child process in the parapet context.
      *
      * @param parent the parent process
      * @param child  the child process
      * @return Unit
      */
    def register(parent: ProcessRef, child: Process[F]): Free[C, Unit] =
      Free.inject[FlowOp[F, ?], C](Register(parent, child))

    /**
      * Runs two flows concurrently. The loser of the race is canceled.
      *
      * Example:
      *
      * {{{
      * val forever = eval(while (true) {})
      *
      * val process: Process[F] = Process[F](_ => {
      *   case Start => race(forever, eval(println("winner")))
      * })
      * }}}
      *
      * Output: `winner`
      *
      * @param first  the first flow
      * @param second the second flow
      * @return Unit
      */
    def race(first: Free[C, Unit], second: Free[C, Unit]): Free[C, Unit] =
      Free.inject[FlowOp[F, ?], C](Race(first, second))

    /**
      * Adds an effect which produces `F` to the current flow.
      *
      * {{{ suspend(IO(print("hi"))) }}}
      *
      * @param thunk an effect
      * @tparam A value type
      * @return Unit
      */
    def suspend[A](thunk: => F[A]): Free[C, Unit] = Free.inject[FlowOp[F, ?], C](Suspend(() => thunk, Option.empty))

    /**
      * Suspends an effect which produces `F` and then feeds that into a function that takes
      * normal value and returns a new flow. All operations from a produced flow added to the current flow.
      *
      * {{{ suspend(IO.pure(1))) { i => eval(print(i)) } }}}
      *
      * @param thunk an effect which produces `F`
      * @param bind  a function that takes a value of type `A` and produces a new flow
      * @tparam A value type
      * @return Unit
      */
    def suspendWith[A](thunk: => F[A])(bind: A => Free[C, Unit]): Free[C, Unit] =
      Free.inject[FlowOp[F, ?], C](Suspend(() => thunk, Option(bind)))

    /**
      * Suspends a side effect in `F` and then adds that to the current flow.
      *
      * @param thunk a side effect
      * @tparam A value type
      * @return Unit
      */
    def eval[A](thunk: => A): Free[C, Unit] = Free.inject[FlowOp[F, ?], C](Eval(() => thunk, Option.empty))

    /**
      * Suspends a side effect in `F` and then feeds that into a function that takes
      * normal value and returns a new flow. All operations from a produced flow added to the current flow.
      *
      * @param thunk a side effect
      * @param bind  a function that takes a value of type `A` and produces a new flow
      * @tparam A value type
      * @return Unit
      */
    def evalWith[A](thunk: => A)(bind: A => Free[C, Unit]): Free[C, Unit] =
      Free.inject[FlowOp[F, ?], C](Eval(() => thunk, Option(bind)))

  }

  object FlowOps {
    implicit def flowOps[F[_], G[_]](implicit I: InjectK[FlowOp[F, ?], G]): FlowOps[F, G] = new FlowOps[F, G]
  }

  trait WithDsl[F[_]] {
    protected val dsl: FlowOps[F, Dsl[F, ?]] = implicitly[FlowOps[F, Dsl[F, ?]]]
  }

}
