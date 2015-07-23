package com.thenewmotion.akka.rabbitmq

import scala.language.reflectiveCalls
import akka.actor.Actor
import akka.event.LoggingAdapter
import com.rabbitmq.client.{ ShutdownListener, ShutdownSignalException }
import java.io.IOException

/**
 * @author Yaroslav Klymko
 */
trait RabbitMqActor extends Actor with ShutdownListener {

  def log: LoggingAdapter

  def shutdownCompleted(cause: ShutdownSignalException) {
    log.debug("on shutdownCompleted {}", cause)
    self ! AmqpShutdownSignal(cause)
  }

  type ChannelOrConnection = {
    def isOpen(): Boolean
    def close(): Unit
  }

  def closeIfOpen(x: ChannelOrConnection) {
    if (x.isOpen()) x.close()
  }

  def safe[T](f: => T): Option[T] = try Some(f) catch {
    case _: IOException             => None
    case _: ShutdownSignalException => None
  }
}

sealed trait ShutdownSignal
case class AmqpShutdownSignal(cause: ShutdownSignalException) extends ShutdownSignal {
  def appliesTo(x: AnyRef) = cause.getReference eq x
}
case object ParentShutdownSignal extends ShutdownSignal