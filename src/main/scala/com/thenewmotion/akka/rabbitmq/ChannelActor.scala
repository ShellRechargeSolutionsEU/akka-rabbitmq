package com.thenewmotion.akka.rabbitmq

import akka.actor.FSM
import com.rabbitmq.client._
import collection.immutable.Queue
import com.thenewmotion.akka.rabbitmq.ConnectionActor.CreateChannel

/**
 * @author Yaroslav Klymko
 */
object ChannelActor {
  type OnChannel = Channel => Any
  private[rabbitmq] sealed trait State
  private[rabbitmq] case object Disconnected extends State
  private[rabbitmq] case object Connected extends State

  private[rabbitmq] sealed trait Data
  private[rabbitmq] case class InMemory(queue: Queue[OnChannel] = Queue()) extends Data
  private[rabbitmq] case class Connected(channel: Channel) extends Data

  case class ChannelMessage(onChannel: OnChannel, dropIfNoChannel: Boolean = true)
}


class ChannelActor(setupChannel: Channel => Unit = _ => ())
  extends RabbitMqActor
  with FSM[ChannelActor.State, ChannelActor.Data] {
  import ChannelActor._

  startWith(Disconnected, InMemory())

  when(Disconnected) {
    case Event(channel: Channel, InMemory(queue)) =>
      setup(channel)
      def loop(xs: List[OnChannel]): State = xs match {
        case Nil => goto(Connected) using Connected(channel)
        case (h :: t) => safe(h(channel)) match {
          case Some(_) => loop(t)
          case None =>
            reconnect(channel)
            stay using InMemory(Queue(t: _*))
        }
      }
      if (queue.nonEmpty) log.debug("processing queued messages {}", queue.mkString("\n", "\n", ""))
      loop(queue.toList)

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
          reconnect(channel)
          goto(Disconnected) using InMemory()
        case _ => stay()
      }
  }
  onTransition {
    case Disconnected -> Connected => log.info("connected")
    case Connected -> Disconnected => log.warning("disconnected")
  }
  onTermination {
    case StopEvent(_, Connected, Connected(channel)) =>
      log.debug("closing channel {}", channel)
      closeIfOpen(channel)
  }
  initialize

  def setup(channel: Channel): Channel = {
    log.debug("setting up new channel {}", channel)
    channel.addShutdownListener(this)
    setupChannel(channel)
    channel
  }

  def reconnect(broken: Channel) {
    log.debug("closing broken channel {}", broken)
    closeIfOpen(broken)
    askForChannel()
  }

  def askForChannel() {
    log.debug("asking for new channel")
    connectionActor ! CreateChannel
  }

  def connectionActor = context.parent
}
