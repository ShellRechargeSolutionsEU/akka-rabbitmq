package com.thenewmotion.akka.rabbitmq

import com.rabbitmq.client._
import akka.actor.{ActorRef, Props, FSM}
import akka.util.duration._
import akka.util.Duration

/**
 * @author Yaroslav Klymko
 */
private[rabbitmq] object ConnectionActor {
  sealed trait State

  case object Disconnected extends State
  case object Connected extends State

  private[rabbitmq] sealed trait Data
  private[rabbitmq] case object NoConnection extends Data
  private[rabbitmq] case class Connected(conn: Connection) extends Data

  sealed trait Message
  case object CreateChannel extends Message
  case object Connect extends Message

  case class Create(props: Props, name: Option[String] = None)
  case class Created(actor: ActorRef)
}


class ConnectionActor(factory: ConnectionFactory,
                      reconnectionDelay: Duration = 10.seconds,
                      setup: Connection => Any = _ => ())
  extends RabbitMqActor
  with FSM[ConnectionActor.State, ConnectionActor.Data] {
  import ConnectionActor._

  val reconnectTimer = "reconnect"

  startWith(Disconnected, NoConnection)

  when(Disconnected) {
    case Event(Connect, _) =>
      safe(setupConnection).getOrElse {
        log.error("can't connect to {}, retrying in {}", factory.uri, reconnectionDelay)
        setTimer(reconnectTimer, Connect, reconnectionDelay, repeat = true)
      }

    case Event(Create(props, name), _) =>
      val child = newChild(props, name)
      log.debug("creating child {} in disconnected state", child)
      stay replying Created(child)

    case Event(_: AmqpShutdownSignal, _) => stay()

    case Event(CreateChannel, _) =>
      log.debug("can't create channel for {} in disconnected state", sender)
      stay()
  }

  when(Connected) {
    case Event(CreateChannel, Connected(connection)) =>
      safe(connection.createChannel()) match {
        case Some(channel) => stay replying channel
        case None =>
          reconnect(connection)
          goto(Disconnected) using NoConnection
      }

    case Event(Create(props, name), Connected(connection)) =>
      safe(connection.createChannel()) match {
        case Some(channel) =>
          val child = newChild(props, name)
          log.debug("creating child {} with channel {}", child, channel)
          child ! channel
          stay replying Created(child)
        case None =>
          val child = newChild(props, name)
          reconnect(connection)
          log.debug("creating child {} without channel", child)
          goto(Disconnected) using NoConnection replying Created(child)
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

  def setupConnection = {
    val connection = factory.newConnection()
    log.debug("setting up new connection {}", connection)
    connection.addShutdownListener(this)
    cancelTimer(reconnectTimer)
    setup(connection)
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
