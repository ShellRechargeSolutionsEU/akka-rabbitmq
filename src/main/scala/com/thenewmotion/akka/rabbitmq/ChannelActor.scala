package com.thenewmotion.akka.rabbitmq

import akka.actor.{ ActorRef, FSM, Props }
import com.thenewmotion.akka.rabbitmq.BlockedConnectionHandler.{ QueueBlocked, QueueUnblocked }
import com.thenewmotion.akka.rabbitmq.ConnectionActor.ProvideChannel

import scala.collection.immutable.Queue

/**
 * @author Yaroslav Klymko
 */
object ChannelActor {
  private[rabbitmq] sealed trait State
  private[rabbitmq] case object Disconnected extends State
  private[rabbitmq] case object Connected extends State
  private[rabbitmq] case object Blocked extends State

  private[rabbitmq] sealed trait Data
  private[rabbitmq] case class InMemory(queue: Queue[OnChannel] = Queue()) extends Data
  private[rabbitmq] case class Connected(channel: Channel) extends Data
  private[rabbitmq] case class Blocked(channel: Channel, waiting: Queue[OnChannel] = Queue()) extends Data

  @deprecated("Use com.thenewmotion.akka.rabbitmq.ChannelMessage instead", "0.3")
  type ChannelMessage = com.thenewmotion.akka.rabbitmq.ChannelMessage
  @deprecated("Use com.thenewmotion.akka.rabbitmq.ChannelMessage instead", "0.3")
  val ChannelMessage = com.thenewmotion.akka.rabbitmq.ChannelMessage

  trait MessageQueued
  trait FailureQueued extends MessageQueued
  case object SuccessfullyQueued extends MessageQueued
  case object NotQueued extends FailureQueued
  case object ConnectionIsBlocked extends FailureQueued

  def props(setupChannel: (Channel, ActorRef) => Any = (_, _) => ()): Props =
    Props(classOf[ChannelActor], setupChannel)
}

class ChannelActor(setupChannel: (Channel, ActorRef) => Any)
    extends RabbitMqActor
    with FSM[ChannelActor.State, ChannelActor.Data] {

  import ChannelActor._

  type ThisState = FSM.State[ChannelActor.State, Data]

  def sendQueuedMsgs(channel: Channel)(xs: List[OnChannel]): State = xs match {
    case Nil => goto(Connected) using Connected(channel)
    case (h :: t) => safe(h(channel)) match {
      case Some(_) => sendQueuedMsgs(channel)(t)
      case None =>
        reconnect(channel)
        goto(Disconnected) using InMemory(Queue(t: _*))
    }
  }

  startWith(Disconnected, InMemory())

  when(Disconnected) {
    case Event(channel: Channel, InMemory(queue)) =>
      setup(channel)
      if (queue.nonEmpty) log.debug("processing queued messages {}", queue.mkString("\n", "\n", ""))
      sendQueuedMsgs(channel)(queue.toList)

    case Event(ChannelMessage(onChannel, dropIfNoChannel), InMemory(queue)) =>
      if (dropIfNoChannel) {
        log.debug("dropping message {} in disconnected state", onChannel)
        stay()
      } else {
        log.debug("queueing message {} in disconnected state", onChannel)
        stay using InMemory(queue enqueue onChannel)
      }

    case Event(_: ShutdownSignal, _) => stay()
  }
  when(Connected) {
    case Event(newChannel: Channel, Connected(channel)) =>
      log.debug("closing unexpected channel {}", channel)
      closeIfOpen(channel)
      stay using Connected(setup(newChannel))

    case Event(_: ShutdownSignal, Connected(channel)) =>
      reconnect(channel)
      goto(Disconnected) using InMemory()

    case Event(ChannelMessage(f, _), Connected(channel)) =>
      safe(f(channel)) match {
        case None =>
          // Note that we do *not* retry f in this case because its failure might be due to some inherent problem with
          // f itself, and in that case a whole application might get stuck in a retry loop.
          reconnect(channel)
          goto(Disconnected) using InMemory()
        case _ => stay()
      }

    case Event(blocked: QueueBlocked, Connected(channel)) =>
      log.warning(s"connection is blocked")
      goto(Blocked) using Blocked(channel)
  }

  when(Blocked) {
    case Event(QueueUnblocked, Blocked(channel, waiting)) =>
      log.info(s"connection is unblocked")
      if (waiting.nonEmpty) log.debug("processing queued messages {}", waiting.mkString("\n", "\n", ""))
      sendQueuedMsgs(channel)(waiting.toList)

    case Event(ChannelMessage(f, _), Blocked(channel, waiting)) =>
      stay() using Blocked(channel, waiting enqueue f)

    // new state will be Connected => after queuing some messages message, rabbit executes block callback
    case Event(newChannel: Channel, Blocked(channel, waiting)) =>
      log.debug("closing unexpected channel {}", channel)
      closeIfOpen(channel)
      sendQueuedMsgs(setup(newChannel))(waiting.toList)

    case Event(_: ShutdownSignal, Blocked(channel, waiting)) =>
      reconnect(channel)
      goto(Disconnected) using InMemory(waiting)
  }
  onTransition {
    case Disconnected -> Connected => log.info("{} connected", self.path)
    case Connected -> Disconnected => log.warning("{} disconnected", self.path)
  }
  onTermination {
    case StopEvent(_, Connected, Connected(channel)) =>
      log.debug("closing channel {}", channel)
      closeIfOpen(channel)
  }
  initialize()

  def setup(channel: Channel): Channel = {
    log.debug("setting up new channel {}", channel)
    channel.addShutdownListener(this)
    setupChannel(channel, self)
    channel
  }

  def reconnect(broken: Channel) {
    log.debug("closing broken channel {}", broken)
    closeIfOpen(broken)
    askForChannel()
  }

  def askForChannel() {
    log.debug("asking for new channel")
    connectionActor ! ProvideChannel
  }

  def connectionActor = context.parent
}
