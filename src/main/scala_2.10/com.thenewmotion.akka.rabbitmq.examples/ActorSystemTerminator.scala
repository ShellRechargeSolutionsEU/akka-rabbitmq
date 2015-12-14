package com.thenewmotion.akka.rabbitmq.examples

import akka.actor.ActorSystem
import scala.concurrent.Future

trait ActorSystemTerminator {
  def terminateActorSystem(actorSystem: ActorSystem): Future[Unit] = Future.successful(actorSystem.shutdown())
}
