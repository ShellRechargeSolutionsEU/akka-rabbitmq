package com.thenewmotion.akka.rabbitmq

import akka.actor.{ ActorRef, Props, FSM }
import concurrent.duration._

/**
 * @author Yaroslav Klymko
 */
object ConnectionActor {
  sealed trait State
  case object Disconnected extends State
  case object Connected extends State

  case object GetState

  private[rabbitmq] sealed trait Data
  private[rabbitmq] case object NoConnection extends Data
  private[rabbitmq] case class Connected(conn: Connection) extends Data

  sealed trait Message
  case object ProvideChannel extends Message
  case object Connect extends Message

  def props(
    factory: ConnectionFactory,
    reconnectionDelay: FiniteDuration = 10.seconds,
    setupConnection: (Connection, ActorRef) => Any = (_, _) => ()): Props =
    Props(classOf[ConnectionActor], factory, reconnectionDelay, setupConnection)
}

class ConnectionActor(
  factory: ConnectionFactory,
  reconnectionDelay: FiniteDuration,
  setupConnection: (Connection, ActorRef) => Any)
    extends RabbitMqActor
    with FSM[ConnectionActor.State, ConnectionActor.Data] {
  import ConnectionActor._

  val reconnectTimer = "reconnect"

  startWith(Disconnected, NoConnection)

  private def header(state: ConnectionActor.State, msg: Any) = s"${self.path} in $state received $msg:"

  when(Disconnected) {
    case Event(Connect, _) =>
      safe(setup).getOrElse {
        log.error("{} can't connect to {}, retrying in {}",
          header(Disconnected, Connect), factory.uri, reconnectionDelay)
        setTimer(reconnectTimer, Connect, reconnectionDelay, repeat = false)
        stay()
      }

    case Event(msg @ CreateChannel(props, name), _) =>
      val child = newChild(props, name)
      log.debug("{} creating child {} in disconnected state", header(Disconnected, msg), child)
      stay replying ChannelCreated(child)

    case Event(_: AmqpShutdownSignal, _) => stay()

    case Event(ProvideChannel, _) =>
      log.debug("{} can't create channel for {} in disconnected state", header(Disconnected, ProvideChannel), sender())
      stay()
  }
  when(Connected) {
    case Event(ProvideChannel, Connected(connection)) =>
      safe(connection.createChannel()) match {
        case Some(channel) =>
          log.debug("{} channel acquired", header(Connected, ProvideChannel))
          stay replying channel
        case None =>
          log.debug("{} no channel acquired. ", header(Connected, ProvideChannel))
          dropConnectionAndInitiateReconnect(connection)
          goto(Disconnected) using NoConnection
      }

    case Event(msg @ CreateChannel(props, name), Connected(connection)) =>
      safe(connection.createChannel()) match {
        case Some(channel) =>
          val child = newChild(props, name)
          log.debug("{} creating child {} with channel {}", header(Connected, msg), child, channel)
          child ! channel
          stay replying ChannelCreated(child)
        case None =>
          val child = newChild(props, name)
          dropConnectionAndInitiateReconnect(connection)
          log.debug("{} creating child {} without channel", header(Connected, msg), child)
          goto(Disconnected) using NoConnection replying ChannelCreated(child)
      }

    case Event(msg @ AmqpShutdownSignal(cause), Connected(connection)) =>
      // It is important that we check if a shutdown signal pertains to the current connection.
      // This actor explicitly close the connection ourselves before reconnecting. So if this actor starts to reconnect
      // in a situation where the connection is still open, it closes it and RabbitMQ will send the actor a
      // ShutdownSignal. This ShutdownSignal may reach this actor when it already has a new connection, in which case
      // the actor should of course not act upon it.
      if (msg.appliesTo(connection)) {
        log.debug("{} shutdown (initiated by app {})", header(Connected, msg), cause.isInitiatedByApplication)
        dropConnectionAndInitiateReconnect(connection)
        goto(Disconnected) using NoConnection
      } else stay()
  }

  whenUnhandled {
    case Event(GetState, _) =>
      sender ! stateName
      stay
  }

  onTransition {
    case Connected -> Disconnected => log.warning("{} lost connection to {}", self.path, factory.uri)
    case Disconnected -> Connected => log.info("{} connected to {}", self.path, factory.uri)
  }

  onTermination {
    case StopEvent(_, Connected, Connected(connection)) =>
      log.info("closing connection to {}", factory.uri)
      closeIfOpen(connection)
  }

  initialize()

  def dropConnectionAndInitiateReconnect(connection: Connection) {
    log.debug("{} closing broken connection {}", self.path, connection)
    closeIfOpen(connection)

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
    case None    => context.actorOf(props)
  }

  override def preStart() {
    self ! Connect
  }
}
