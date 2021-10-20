package com.newmotion.akka.rabbitmq

import scala.util._
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

  def shutdownCompleted(cause: ShutdownSignalException): Unit = {
    log.debug("on shutdownCompleted {}", cause)
    self ! AmqpShutdownSignal(cause)
  }

  def close(x: AutoCloseable): Unit = Try {
    x.close()
  } match {
    case Success(_) =>
      log.debug("close success")
    case Failure(throwable) =>
      log.error("close {}", throwable)
  }

  def safe[T](f: => T): Option[T] = Try {
    f
  } match {
    case Success(result)                     => Some(result)
    case Failure(_: IOException)             => None
    case Failure(_: ShutdownSignalException) => None
    case Failure(_: TimeoutException)        => None
    case Failure(throwable)                  => throw throwable
  }
}

sealed trait ShutdownSignal
case class AmqpShutdownSignal(cause: ShutdownSignalException) extends ShutdownSignal {
  def appliesTo(x: AnyRef): Boolean = cause.getReference eq x
}
case object ParentShutdownSignal extends ShutdownSignal
