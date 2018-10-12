package com.newmotion.akka.rabbitmq

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
    factory:           ConnectionFactory,
    reconnectionDelay: FiniteDuration                = 10.seconds,
    setupConnection:   (Connection, ActorRef) => Any = (_, _) => ()): Props =
    Props(classOf[ConnectionActor], factory, reconnectionDelay, setupConnection)
}

class ConnectionActor(
    factory:           ConnectionFactory,
    reconnectionDelay: FiniteDuration,
    setupConnection:   (Connection, ActorRef) => Any)
  extends RabbitMqActor
  with FSM[ConnectionActor.State, ConnectionActor.Data] {
  import ConnectionActor._

  val reconnectTimer = "reconnect"

  startWith(Disconnected, NoConnection)

  private def header(state: ConnectionActor.State, msg: Any) = s"${self.path} in $state received $msg:"

  when(Disconnected) {
    case Event(Connect, _) =>
      setup() match {
        case None =>
          log.error(
            "{} can't connect to {}, retrying in {}",
            header(Disconnected, Connect), factory.uri, reconnectionDelay)
          setTimer(reconnectTimer, Connect, reconnectionDelay, repeat = false)
          stay()
        case Some(connection) =>
          goto(Connected) using Connected(connection)
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
      safeCreateChannel(connection) match {
        case Some(channel) =>
          log.debug("{} channel acquired", header(Connected, ProvideChannel))
          stay replying channel
        case None =>
          log.debug("{} no channel acquired. ", header(Connected, ProvideChannel))
          dropConnectionAndNotifyChildren(connection)
          self ! Connect
          goto(Disconnected) using NoConnection
      }

    case Event(msg @ CreateChannel(props, name), Connected(connection)) =>
      safeCreateChannel(connection) match {
        case Some(channel) =>
          val child = newChild(props, name)
          log.debug("{} creating child {} with channel {}", header(Connected, msg), child, channel)
          child ! channel
          stay replying ChannelCreated(child)
        case None =>
          val child = newChild(props, name)
          dropConnectionAndNotifyChildren(connection)
          self ! Connect
          log.debug("{} creating child {} without channel", header(Connected, msg), child)
          goto(Disconnected) using NoConnection replying ChannelCreated(child)
      }

    case Event(msg @ AmqpShutdownSignal(cause), Connected(connection)) =>
      // It is important that we check if a shutdown signal pertains to the current connection.
      if (msg.appliesTo(connection)) {
        log.debug("{} shutdown (initiated by app {})", header(Connected, msg), cause.isInitiatedByApplication)
        dropConnectionAndNotifyChildren(connection)
        self ! Connect
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
      close(connection)
  }

  initialize()

  private def dropConnectionAndNotifyChildren(brokenConnection: Connection) {
    log.debug("{} closing broken connection {}", self.path, brokenConnection)
    close(brokenConnection)

    children.foreach(_ ! ParentShutdownSignal)
  }

  /**
   * As connection recovery at this level does not play well
   * with [[http://www.rabbitmq.com/api-guide.html#recovery native recovery]]
   * factory settings are changed to disable it even if it was enabled
   * to ensure correctness of operations.
   */
  private def setup(): Option[Connection] = {
    factory.setAutomaticRecoveryEnabled(false)
    safe(factory.newConnection()).flatMap { connection =>
      log.debug("setting up new connection {}", connection)
      connection.addShutdownListener(this)
      cancelTimer(reconnectTimer)
      safe(setupConnection(connection, self)).flatMap { _ =>
        children.foldLeft[Option[Connection]](Some(connection)) {
          case (Some(conn), child) =>
            safeCreateChannel(conn) match {
              case Some(channel) =>
                child ! channel
                Some(conn)
              case None =>
                dropConnectionAndNotifyChildren(conn)
                None
            }
          case (None, _) =>
            None
        }
      }
    }
  }

  private def safeCreateChannel(connection: Connection): Option[Channel] = {
    safe(connection.createChannel()).map { channel =>
      if (channel == null) {
        log.warning("{} no channels available on connection {}", self.path, connection)
      }
      channel
    }
  }

  private[rabbitmq] def children = context.children

  private[rabbitmq] def newChild(props: Props, name: Option[String]) = name match {
    case Some(x) => context.actorOf(props, x)
    case None    => context.actorOf(props)
  }

  override def preStart() {
    self ! Connect
  }
}
