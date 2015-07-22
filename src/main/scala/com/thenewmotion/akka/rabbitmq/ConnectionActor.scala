package com.thenewmotion.akka.rabbitmq

import akka.actor.{ ActorRef, Props, FSM }
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

  private def header(state: ConnectionActor.State, msg: Any) = s"${self.path} in $state recieved $msg:"

  when(Disconnected) {
    case Event(Connect, _) =>
      safe(setup).getOrElse {
        log.error("{} can't connect to {}, retrying in {}",
          header(Disconnected, Connect), factory.uri, reconnectionDelay)
        setTimer(reconnectTimer, Connect, reconnectionDelay, repeat = false)
        stay()
      }

    case Event(msg@CreateChannel(props, name), _) =>
      val child = newChild(props, name)
      log.debug("{} creating child {} in disconnected state", header(Disconnected, msg), child)
      stay replying ChannelCreated(child)

    case Event(_: AmqpShutdownSignal, _) => stay()

    case Event(msg@ProvideChannel, _) =>
      log.debug("{} can't create channel for {} in disconnected state", header(Disconnected, msg), sender())
      stay()
  }
  when(Connected) {
    case Event(msg@ProvideChannel, Connected(connection)) =>
      safe(connection.createChannel()) match {
        case Some(channel) =>
          log.debug("{} channel acquired", header(Connected, msg))
          stay replying channel
        case None =>
          log.debug("{} no channel acquired. ", header(Connected, msg))
          dropCurrentConnectionAndInitiateReconnect(connection)
          goto(Disconnected) using NoConnection
      }

    case Event(msg@CreateChannel(props, name), Connected(connection)) =>
      safe(connection.createChannel()) match {
        case Some(channel) =>
          val child = newChild(props, name)
          log.debug("{} creating child {} with channel {}", header(Connected, msg), child, channel)
          child ! channel
          stay replying ChannelCreated(child)
        case None =>
          val child = newChild(props, name)
          dropCurrentConnectionAndInitiateReconnect(connection)
          log.debug("{} creating child {} without channel", header(Connected, msg), child)
          goto(Disconnected) using NoConnection replying ChannelCreated(child)
      }

    // we ignore shutdowns by application. It's either this actor itself, in which case it has a plan and all is fine,
    // or some hooligan who is deliberately causing trouble. In the latter case we will be notified again when a
    // subsequent ChannelMessage using this connection fails, and we can go to Disconnected and reconnect then.
    case Event(msg@AmqpShutdownSignal(cause), Connected(connection)) if !cause.isInitiatedByApplication =>
      log.debug("{} shutdown (initiated by app {})", header(Connected, msg), cause.isInitiatedByApplication)
      dropCurrentConnectionAndInitiateReconnect(connection)
      goto(Disconnected) using NoConnection
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

  def dropCurrentConnectionAndInitiateReconnect(current: Connection) {
    log.debug("{} closing broken connection {}", self.path, current)
    closeIfOpen(current)

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
