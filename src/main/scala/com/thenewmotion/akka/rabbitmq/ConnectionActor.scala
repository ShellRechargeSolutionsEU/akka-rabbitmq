package com.thenewmotion.akka.rabbitmq

import akka.actor.{ActorRef, Props, FSM}
import concurrent.duration._

/**
 * @author Yaroslav Klymko
 */
object ConnectionActor {
  private[rabbitmq] sealed trait State
  private[rabbitmq] case object Disconnected extends State
  private[rabbitmq] case object Connected extends State

  private[rabbitmq] sealed trait Data
  private[rabbitmq] case object NoConnection extends Data
  private[rabbitmq] case class Connected(conn: Connection) extends Data

  sealed trait Message
  case object ProvideChannel extends Message
  case object Connect extends Message
}


class ConnectionActor(factory: ConnectionFactory,
                      reconnectionDelay: FiniteDuration = 10.seconds,
                      setupConnection: (Connection, ActorRef) => Any = (_, _) => ())
  extends RabbitMqActor
  with FSM[ConnectionActor.State, ConnectionActor.Data] {
  import ConnectionActor._

  val reconnectTimer = "reconnect"

  startWith(Disconnected, NoConnection)

  when(Disconnected) {
    case Event(Connect, _) =>
      safe(setup).getOrElse {
        log.error("can't connect to {}, retrying in {}", factory.uri, reconnectionDelay)
        setTimer(reconnectTimer, Connect, reconnectionDelay, repeat = false)
        stay()
      }

    case Event(CreateChannel(props, name), _) =>
      val child = newChild(props, name)
      log.debug("creating child {} in disconnected state", child)
      stay replying ChannelCreated(child)

    case Event(_: AmqpShutdownSignal, _) => stay()

    case Event(ProvideChannel, _) =>
      log.debug("can't create channel for {} in disconnected state", sender)
      stay()
  }
  when(Connected) {
    case Event(ProvideChannel, Connected(connection)) =>
      safe(connection.createChannel()) match {
        case Some(channel) => stay replying channel
        case None =>
          reconnect(connection)
          goto(Disconnected) using NoConnection
      }

    case Event(CreateChannel(props, name), Connected(connection)) =>
      safe(connection.createChannel()) match {
        case Some(channel) =>
          val child = newChild(props, name)
          log.debug("creating child {} with channel {}", child, channel)
          child ! channel
          stay replying ChannelCreated(child)
        case None =>
          val child = newChild(props, name)
          reconnect(connection)
          log.debug("creating child {} without channel", child)
          goto(Disconnected) using NoConnection replying ChannelCreated(child)
      }

    case Event(AmqpShutdownSignal(cause), Connected(connection)) =>
      if (!cause.isInitiatedByApplication) reconnect(connection)
      goto(Disconnected) using NoConnection
  }
  onTransition {
    case Connected -> Disconnected => log.warning("lost connection to {}", factory.uri)
    case Disconnected -> Connected => log.info("connected to {}", factory.uri)
  }
  onTermination {
    case StopEvent(_, Connected, Connected(connection)) =>
      log.info("closing connection to {}", factory.uri)
      closeIfOpen(connection)
  }
  initialize

  def reconnect(broken: Connection) {
    log.debug("closing broken connection {}", broken)
    closeIfOpen(broken)
    self ! Connect
    children.foreach(_ ! ParentShutdownSignal)
  }

  def setup = {
    val connection = factory.newConnection()
    log.debug("setting up new connection {}", connection)
    connection.addShutdownListener(this)
    cancelTimer(reconnectTimer)
    setupConnection(connection, self)
    children.foreach(_ ! connection.createChannel())
    goto(Connected) using Connected(connection)
  }

  def children = context.children

  def newChild(props: Props, name: Option[String]) = name match {
    case Some(x) => context.actorOf(props, x)
    case None => context.actorOf(props)
  }

  override def preStart() {
    self ! Connect
  }
}
