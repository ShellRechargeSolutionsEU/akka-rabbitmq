package com.thenewmotion.akka.rabbitmq.examples

import akka.actor.{Terminated, ActorSystem}

import scala.concurrent.Future

trait ActorSystemTerminator {
  def terminateActorSystem(actorSystem: ActorSystem): Future[Terminated] = actorSystem.terminate()
}
