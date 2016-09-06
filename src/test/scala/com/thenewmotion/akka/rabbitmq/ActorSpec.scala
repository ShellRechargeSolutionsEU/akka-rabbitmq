package com.thenewmotion.akka.rabbitmq

import org.specs2.mutable.Specification
import org.specs2.specification.Scope
import org.specs2.specification.core.Fragments
import akka.actor.ActorSystem
import akka.testkit.{ ImplicitSender, TestKit }

abstract class ActorSpec extends Specification {
  implicit val system = ActorSystem()

  override def map(fs: => Fragments) = super.map(fs) ^ step(TestKit.shutdownActorSystem(system))

  abstract class ActorScope extends TestKit(system) with ImplicitSender with Scope
}
