package com.thenewmotion.akka.rabbitmq

import org.specs2.mutable.SpecificationWithJUnit
import akka.testkit.{ ImplicitSender, TestActorRef, TestProbe, TestKit }
import akka.actor.{ Terminated, ActorRef, Actor, ActorSystem }
import org.specs2.mock.Mockito
import org.specs2.specification.Scope

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
      val probe = TestProbe()
      probe watch testActor
      actor ! ChannelCreated(testActor)
      actor.stop()
      probe.expectMsgPF() {
        case Terminated(`testActor`) => true
      }
    }
  }

  abstract class TestScope extends TestKit(ActorSystem()) with ImplicitSender with Scope {
    val connectionProbe = TestProbe()
    val actor = TestActorRef(new TestWithChannel)

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
