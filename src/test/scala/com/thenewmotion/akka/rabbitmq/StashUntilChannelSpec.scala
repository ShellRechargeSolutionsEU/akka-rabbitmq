package com.thenewmotion.akka.rabbitmq

import akka.actor.{ Props, Actor, ActorRef, Terminated }
import akka.testkit._

class StashUntilChannelSpec extends ActorSpec {
  "StashUntilChannel" should {

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

    "process messages that came in when there was no channel as soon as a channel is set up" in new TestScope {
      val commandSender = TestProbe()
      commandSender.send(actor, Command)

      actor ! ChannelCreated(testActor)

      commandSender.expectMsg(Reply(testActor))
    }

    "process messages that came in when there was no channel before any message that came in after the CreateChannel" in new TestScope {
      val numMessagesBeforeChannelCreated = 4000
      class OrderTestStashUntil extends Actor with StashUntilChannel {
        def connectionActor = connectionProbe.ref
        def receiveWithChannel(channelActor: ActorRef) = {
          case msg: Integer => sender() ! msg
        }
      }
      // we cannot use TestActorRef here because TestActorRef processes messages synchronously
      val orderTestActor = system.actorOf(Props(new OrderTestStashUntil))

      1.to(numMessagesBeforeChannelCreated).foreach(n => orderTestActor ! n)
      orderTestActor ! ChannelCreated(testActor)
      orderTestActor ! (numMessagesBeforeChannelCreated + 1)

      1.to(numMessagesBeforeChannelCreated + 1).foreach(expectMsg[Int])
    }
  }

  abstract class TestScope extends ActorScope {
    val connectionProbe = TestProbe()
    val actor = TestActorRef(new TestStashUntil)

    object Command
    case class Reply(channelActor: ActorRef)

    class TestStashUntil extends Actor with StashUntilChannel {
      def connectionActor = connectionProbe.ref
      def receiveWithChannel(channelActor: ActorRef) = {
        case Command => sender ! Reply(channelActor)
      }
    }
  }
}
