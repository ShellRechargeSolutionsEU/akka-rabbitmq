package com.thenewmotion.akka.rabbitmq

import akka.actor.{ Props, ActorRef, FSM }
import collection.immutable.Queue
import ConnectionActor.ProvideChannel

/**
 * @author Yaroslav Klymko
 */
object ChannelActor {
  private[rabbitmq] sealed trait State
  private[rabbitmq] case object Disconnected extends State
  private[rabbitmq] case object Connected extends State

  private[rabbitmq] sealed trait Data
  private[rabbitmq] case class InMemory(queue: Queue[OnChannel] = Queue()) extends Data
  private[rabbitmq] case class Connected(channel: Channel) extends Data

  @deprecated("Use com.thenewmotion.akka.rabbitmq.ChannelMessage instead", "0.3")
  type ChannelMessage = com.thenewmotion.akka.rabbitmq.ChannelMessage
  @deprecated("Use com.thenewmotion.akka.rabbitmq.ChannelMessage instead", "0.3")
  val ChannelMessage = com.thenewmotion.akka.rabbitmq.ChannelMessage

  def props(setupChannel: (Channel, ActorRef) => Any = (_, _) => ()): Props =
    Props(classOf[ChannelActor], setupChannel)

  private[rabbitmq] case class Retrying(retries: Int, onChannel: OnChannel) extends OnChannel {
    def apply(channel: Channel) = onChannel(channel)
  }
}

class ChannelActor(setupChannel: (Channel, ActorRef) => Any)
    extends RabbitMqActor
    with FSM[ChannelActor.State, ChannelActor.Data] {

  import ChannelActor._

  startWith(Disconnected, InMemory())

  private sealed trait ProcessingResult {}
  private case class ProcessSuccess(m: Any) extends ProcessingResult
  private case class ProcessFailureRetry(onChannel: Retrying) extends ProcessingResult
  private case object ProcessFailureDrop extends ProcessingResult

  private def header(state: ChannelActor.State, msg: Any) = s"in $state received $msg:"

  private def safeWithRetry(channel: Channel, fn: OnChannel): ProcessingResult = {
    safe(fn(channel)) match {
      case Some(r) =>
        ProcessSuccess(r)

      case None if channel.isOpen() =>
        /* if the function failed, BUT the channel is still open, we know that the problem was with f, and not the
         channel state.

         Therefore we do *not* retry f in this case because its failure might be due to some inherent problem with f
         itself, and in that case a whole application might get stuck in a retry loop.
         */
        ProcessFailureDrop

      case None =>
        /*
         The channel is closed, but the actor state believed it was open; There is a small window between a disconnect, sending an AmqpShutdownSignal, and processing that signal
         Just because our ChannelMessage was processed in this window does not mean we should ignore the intent of dropIfNoChannel (because there was, in fact, no channel)
         */
        fn match {
          case Retrying(retries, _) if retries == 0 =>
            ProcessFailureDrop
          case Retrying(retries, onChannel) =>
            ProcessFailureRetry(Retrying(retries - 1, onChannel))
          case _ =>
            ProcessFailureRetry(Retrying(3, fn))
        }
    }
  }

  when(Disconnected) {
    case Event(channel: Channel, InMemory(queue)) =>
      setup(channel)
      def loop(xs: List[OnChannel]): State = xs match {
        case Nil => goto(Connected) using Connected(channel)
        case (h :: t) =>
          val res = safeWithRetry(channel, h)
          log.debug("{} queued message {} resulted in {}", header(Disconnected, channel), h, res)
          res match {
            case ProcessSuccess(_) => loop(t)
            case ProcessFailureRetry(retry) =>
              reconnect(channel)
              stay using InMemory(Queue(retry :: t: _*))
            case ProcessFailureDrop =>
              reconnect(channel)
              stay using InMemory(Queue(t: _*))
          }
      }
      if (queue.nonEmpty) log.debug("{} processing queued messages {}",
        header(Disconnected, channel), queue.mkString("\n", "\n", ""))
      loop(queue.toList)

    case Event(msg @ ChannelMessage(onChannel, dropIfNoChannel), InMemory(queue)) =>
      if (dropIfNoChannel) {
        log.debug("{} dropping message {}", header(Disconnected, msg), onChannel)
        stay()
      } else {
        log.debug("{} queueing message {}", header(Disconnected, msg), onChannel)
        stay using InMemory(queue enqueue onChannel)
      }

    case Event(_: ShutdownSignal, _) => stay()
  }
  when(Connected) {
    case Event(newChannel: Channel, Connected(channel)) =>
      log.debug("{} closing unexpected channel {}", header(Connected, newChannel), channel)
      closeIfOpen(channel)
      stay using Connected(setup(newChannel))

    case Event(msg: ShutdownSignal, Connected(channel)) if shutdownSignalAppliesToChannel(msg) =>
      log.debug("{} shutdown", header(Connected, msg))
      reconnect(channel)
      goto(Disconnected) using InMemory()

    case Event(cm @ ChannelMessage(f, _), Connected(channel)) =>
      val res = safeWithRetry(channel, f)
      log.debug("{} received channel message resulted in {}", header(Connected, cm), res)
      res match {
        case ProcessSuccess(_) => stay()
        case ProcessFailureRetry(retry) if !cm.dropIfNoChannel =>
          reconnect(channel)
          goto(Disconnected) using InMemory(Queue(retry))
        case _ =>
          reconnect(channel)
          goto(Disconnected) using InMemory()
      }
  }
  onTransition {
    case Disconnected -> Connected => log.info("{} connected", self.path)
    case Connected -> Disconnected => log.warning("{} disconnected", self.path)
  }
  onTermination {
    case StopEvent(_, Connected, Connected(channel)) =>
      log.debug("{} closing channel {}", self.path, channel)
      closeIfOpen(channel)
  }
  initialize()

  def setup(channel: Channel): Channel = {
    log.debug("{} setting up new channel {}", self.path, channel)
    channel.addShutdownListener(this)
    setupChannel(channel, self)
    channel
  }

  def reconnect(broken: Channel) {
    log.debug("{} closing broken channel {}", self.path, broken)
    closeIfOpen(broken)
    askForChannel()
  }

  def askForChannel() {
    log.debug("{} asking for new channel", self.path)
    connectionActor ! ProvideChannel
  }

  def connectionActor = context.parent

  val shutdownSignalAppliesToChannel: ShutdownSignal => Boolean = {
    case ParentShutdownSignal      => true

    // connection-level ("hard") errors should be handled by the COnnection Actor, which will send this actor a
    // ParentShutdownSignal when this actor is supposed to do something
    case AmqpShutdownSignal(cause) => !cause.isHardError
  }
}
