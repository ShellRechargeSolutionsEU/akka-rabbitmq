package com.thenewmotion.akka.rabbitmq

import akka.actor.ActorRef
import com.rabbitmq.client.BlockedListener
import com.thenewmotion.akka.rabbitmq.BlockedConnectionHandler.{ QueueBlocked, QueueUnblocked }

/**
 * @author Mateusz Jaje
 */

/**
 * Support for blocked connections for publishing
 * More info <a href="https://www.rabbitmq.com/connection-blocked.html">https://www.rabbitmq.com/connection-blocked.html</a>
 */
object BlockedConnectionSupport {
  def setupConnection(connection: Connection, connectionMaintainer: ActorRef) = {
    connection.addBlockedListener(new BlockedConnectionHandler(connectionMaintainer))
  }
}

object BlockedConnectionHandler {
  case class QueueBlocked(reason: String)
  case object QueueUnblocked
}

class BlockedConnectionHandler(listener: ActorRef) extends BlockedListener {
  override def handleUnblocked(): Unit = listener ! QueueUnblocked

  override def handleBlocked(reason: String): Unit = listener ! QueueBlocked(reason)
}
