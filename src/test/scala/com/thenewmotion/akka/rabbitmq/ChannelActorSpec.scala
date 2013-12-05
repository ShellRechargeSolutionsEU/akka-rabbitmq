package com.thenewmotion.akka.rabbitmq

import org.specs2.mutable.SpecificationWithJUnit
import org.specs2.specification.Scope
import org.specs2.mock.Mockito
import akka.testkit.{ TestFSMRef, TestKit }
import akka.actor.{ ActorRef, ActorSystem }
import ChannelActor._
import com.rabbitmq.client.ShutdownSignalException
import collection.immutable.Queue
import java.io.IOException
import ConnectionActor.ProvideChannel

/**
 * @author Yaroslav Klymko
 */
class ChannelActorSpec extends SpecificationWithJUnit with Mockito {
  "ChannelActor" should {
    "setup channel when channel received" in new TestScope {
      actorRef ! channel
      state mustEqual connected()
      there was one(setupChannel).apply(channel, actorRef)
      there was one(channel).addShutdownListener(actor)
    }
    "close old channel if new one received" in new TestScope {
      actorRef.setState(Connected, Connected(channel))
      val newChannel = mock[Channel]
      actorRef ! newChannel
      there was one(channel).close()
      state mustEqual connected(newChannel)
      there was one(setupChannel).apply(newChannel, actorRef)
      there was one(newChannel).addShutdownListener(actor)
    }
    "process message if has channel" in new TestScope {
      actorRef.setState(Connected, Connected(channel))
      actorRef ! ChannelMessage(onChannel)
      there was one(onChannel).apply(channel)
      state mustEqual connected()
    }
    "process message if has channel and reconnect if failed" in new TestScope {
      actorRef.setState(Connected, Connected(channel))
      actorRef ! ChannelMessage(onChannelFailure)
      state mustEqual disconnected()
      expectMsg(ProvideChannel)
    }
    "collect channel message if no channel" in new TestScope {
      actorRef ! ChannelMessage(onChannel, dropIfNoChannel = false)
      state mustEqual disconnected(onChannel)
    }
    "drop channel message if no channel and allowed to drop" in new TestScope {
      actorRef ! ChannelMessage(onChannel)
      state mustEqual disconnected()
    }
    "leave channel if told by parent" in new TestScope {
      actorRef.setState(Connected, Connected(channel))
      actorRef ! AmqpShutdownSignal(shutdownSignal)
      state mustEqual disconnected()
      expectMsg(ProvideChannel)
    }
    "leave channel on ShutdownSignal" in new TestScope {
      actorRef.setState(Connected, Connected(channel))
      actor.shutdownCompleted(shutdownSignal)
      state mustEqual disconnected()
      expectMsg(ProvideChannel)
    }
    "aks for channel on ShutdownSignal" in new TestScope {
      actorRef.setState(Connected, Connected(channel))
      actor.shutdownCompleted(shutdownSignal)
      state mustEqual disconnected()
      expectMsg(ProvideChannel)
    }
    "process queued channel messages when channel received" in new TestScope {
      actorRef.setState(Disconnected, InMemory(Queue(onChannel, onChannel)))
      actorRef ! channel
      there was two(onChannel).apply(channel)
      state mustEqual connected()
    }
    "process queued channel messages when channel received and failed" in new TestScope {
      val last = mock[OnChannel]
      actorRef.setState(Disconnected, InMemory(Queue(onChannel, onChannelFailure, last)))
      actorRef ! channel
      there was one(onChannel).apply(channel)
      state mustEqual disconnected(last)
    }
  }

  private abstract class TestScope extends TestKit(ActorSystem()) with Scope {
    val setupChannel = mock[(Channel, ActorRef) => Unit]
    val onChannel = mock[OnChannel]
    val channel = {
      val channel = mock[Channel]
      channel.isOpen returns true
      channel
    }
    val shutdownSignal = mock[ShutdownSignalException]
    val actorRef = TestFSMRef(new TestChannelActor)

    def actor = actorRef.underlyingActor.asInstanceOf[ChannelActor]
    def state: (State, Data) = actorRef.stateName -> actorRef.stateData
    def disconnected(xs: OnChannel*) = Disconnected -> InMemory(Queue(xs: _*))
    def connected(x: Channel = channel) = Connected -> Connected(x)
    def onChannelFailure(channel: Channel): Any = throw new IOException()

    class TestChannelActor extends ChannelActor(setupChannel) {
      override def connectionActor = testActor
    }
  }
}