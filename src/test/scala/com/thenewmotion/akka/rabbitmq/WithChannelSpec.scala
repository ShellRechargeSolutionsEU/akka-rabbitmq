package com.thenewmotion.akka.rabbitmq

import org.specs2.mutable.SpecificationWithJUnit
import akka.testkit.{TestActorRef, TestProbe, TestKit}
import akka.actor.{ActorRef, Actor, ActorSystem}
import org.specs2.mock.Mockito

/**
 * @author Yaroslav Klymko
 */
class WithChannelSpec extends SpecificationWithJUnit with Mockito {
  "WithChannel" should {
    "retrieve channel on start" in new TestScope {
      connectionProbe.expectMsgType[CreateChannel]
    }
    "handle ChannelCreated message and switch context" in new TestScope {
      actor ! Command
      expectNoMsg()
      actor ! ChannelCreated(testActor)
      actor ! Command
      expectMsg(Reply(testActor))
    }
    "stop channel on stop" in new TestScope {
      actor.stop()
      testActor.isTerminated must beTrue
    }
  }

  abstract class TestScope extends TestKit(ActorSystem()) {
    val actor = TestActorRef[TestWithChannel]
    val connectionProbe = TestProbe()

    object Command
    case class Reply(channelActor: ActorRef)

    class TestWithChannel extends Actor with WithChannel {
      def connectionActor = connectionProbe.ref
      def receiveWithChannel(channelActor: ActorRef) = {
        case Command => sender ! Reply(channelActor)
      }
    }
  }
}
