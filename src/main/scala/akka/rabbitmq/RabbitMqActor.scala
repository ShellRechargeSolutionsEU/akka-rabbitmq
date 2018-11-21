package com.newmotion.akka.rabbitmq

import scala.util.control.NonFatal
import akka.actor.Actor
import akka.event.LoggingAdapter
import com.rabbitmq.client.{ ShutdownListener, ShutdownSignalException }
import java.io.IOException
import java.util.concurrent.TimeoutException

/**
 * @author Yaroslav Klymko
 */
trait RabbitMqActor extends Actor with ShutdownListener {
  def log: LoggingAdapter

  def shutdownCompleted(cause: ShutdownSignalException) {
    log.debug("on shutdownCompleted {}", cause)
    self ! AmqpShutdownSignal(cause)
  }

  def close(x: AutoCloseable): Unit = try x.close() catch {
    case NonFatal(e) => log.error("close {}", e)
  }

  def safe[T](f: => T): Option[T] = try Some(f) catch {
    case _: IOException             => None
    case _: ShutdownSignalException => None
    case _: TimeoutException        => None
  }
}

sealed trait ShutdownSignal
case class AmqpShutdownSignal(cause: ShutdownSignalException) extends ShutdownSignal {
  def appliesTo(x: AnyRef) = cause.getReference eq x
}
case object ParentShutdownSignal extends ShutdownSignal
