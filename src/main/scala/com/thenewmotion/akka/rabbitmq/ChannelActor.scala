package com.thenewmotion.akka.rabbitmq

import akka.actor.{ Props, ActorRef, FSM }
import collection.immutable.Queue
import ConnectionActor.ProvideChannel

/**
 * @author Yaroslav Klymko
 */
object ChannelActor {
  sealed trait State
  case object Disconnected extends State
  case object Connected extends State

  case object GetState

  private[rabbitmq] sealed trait Data
  private[rabbitmq] case class InMemory(queue: Queue[OnChannel] = Queue()) extends Data
  private[rabbitmq] case class Connected(channel: Channel) extends Data

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
      def loop(qs: Queue[OnChannel]): State = qs.headOption match {
        case None => goto(Connected) using Connected(channel)
        case Some(onChannel) =>
          val res = safeWithRetry(channel, onChannel)
          log.debug("{} queued message {} resulted in {}", header(Disconnected, channel), onChannel, res)
          res match {
            case ProcessSuccess(_) => loop(qs.tail)
            case ProcessFailureRetry(retry) =>
              dropChannelAndRequestNewChannel(channel)
              stay using InMemory(retry +: qs.tail)
            case ProcessFailureDrop =>
              dropChannelAndRequestNewChannel(channel)
              stay using InMemory(qs.tail)
          }
      }
      if (queue.nonEmpty) log.debug("{} processing queued messages {}",
        header(Disconnected, channel), queue.mkString("\n", "\n", ""))
      loop(queue)

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

    case Event(msg: ShutdownSignal, Connected(channel)) =>
      if (shutdownSignalAppliesToChannel(msg, channel)) {
        log.debug("{} shutdown", header(Connected, msg))
        dropChannelAndRequestNewChannel(channel)
        goto(Disconnected) using InMemory()
      } else stay()

    case Event(cm @ ChannelMessage(f, _), Connected(channel)) =>
      val res = safeWithRetry(channel, f)
      log.debug("{} received channel message resulted in {}", header(Connected, cm), res)
      res match {
        case ProcessSuccess(_) => stay()
        case ProcessFailureRetry(retry) if !cm.dropIfNoChannel =>
          dropChannelAndRequestNewChannel(channel)
          goto(Disconnected) using InMemory(Queue(retry))
        case _ =>
          dropChannelAndRequestNewChannel(channel)
          goto(Disconnected) using InMemory()
      }
  }
  whenUnhandled {
    case Event(GetState, _) =>
      sender ! stateName
      stay
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

  def dropChannelAndRequestNewChannel(broken: Channel) {
    log.debug("{} closing broken channel {}", self.path, broken)
    closeIfOpen(broken)
    askForChannel()
  }

  def askForChannel() {
    log.debug("{} asking for new channel", self.path)
    connectionActor ! ProvideChannel
  }

  def connectionActor = context.parent

  def shutdownSignalAppliesToChannel(shutdownSignal: ShutdownSignal, channel: Channel): Boolean =
    shutdownSignal match {
      case ParentShutdownSignal           => true

      case amqpSignal: AmqpShutdownSignal => amqpSignal.appliesTo(channel)
    }

  @scala.throws[Exception](classOf[Exception])
  override def postRestart(reason: Throwable) {
    super.postRestart(reason)
    askForChannel()
  }
}
