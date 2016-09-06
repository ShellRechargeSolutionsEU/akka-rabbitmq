package com.thenewmotion.akka.rabbitmq

import org.specs2.mock.Mockito
import akka.testkit.TestFSMRef
import akka.actor.{ ActorRef, Props }
import ConnectionActor._
import com.rabbitmq.client.ShutdownSignalException
import scala.concurrent.duration._
import scala.collection.immutable.Iterable
import java.io.IOException

/**
 * @author Yaroslav Klymko
 */
class ConnectionActorSpec extends ActorSpec with Mockito {

  "ConnectionActor" should {

    "try to connect on startup" in new TestScope {
      actorRef ! Connect
      state mustEqual connectedAfterRecovery
      val order = inOrder(factory, recoveredConnection, setup, actor)
      there was one(factory).newConnection()
      there was one(recoveredConnection).addShutdownListener(any)
      there was one(setup).apply(recoveredConnection, actorRef)
    }

    "not reconnect if has connection" in new TestScope {
      actorRef.setState(Connected, Connected(initialConnection))
      actorRef ! Connect
      state mustEqual connectedInitially
    }

    "try to reconnect if failed to connect" in new TestScope {
      factory.newConnection throws new IOException
      actorRef ! Connect
      state mustEqual disconnected
    }

    "try to reconnect if can't create new channel" in new TestScope {
      initialConnection.createChannel() throws new IOException
      recoveredConnection.createChannel() throws new IOException
      actorRef.setState(Connected, Connected(initialConnection))
      actorRef ! create
      state mustEqual disconnected
    }

    "attempt to connect on Connect message" in new TestScope {
      factory.newConnection throws new IOException thenReturns recoveredConnection
      actorRef ! Connect
      state mustEqual disconnected
      actorRef ! Connect
      state mustEqual connectedAfterRecovery
    }

    "reconnect on ShutdownSignalException from server" in new TestScope {
      actorRef.setState(Connected, Connected(initialConnection))
      actor.shutdownCompleted(shutdownSignal())
      state mustEqual connectedAfterRecovery
    }

    "keep trying to reconnect on ShutdownSignalException from server" in new TestScope {
      actorRef.setState(Connected, Connected(initialConnection))
      factory.newConnection throws new IOException thenThrow new IOException thenReturns recoveredConnection
      actor.shutdownCompleted(shutdownSignal())
      state mustEqual disconnected
    }

    "not reconnect on ShutdownSignalException for different connection" in new TestScope {
      actorRef.setState(Connected, Connected(initialConnection))
      actor.shutdownCompleted(shutdownSignal(reference = mock[Connection]))
      there was no(factory).newConnection()
    }

    "create children actor with channel" in new TestScope {
      actorRef.setState(Connected, Connected(initialConnection))
      actorRef ! create
      expectMsg(channel)
      expectMsg(ChannelCreated(testActor))
    }

    "create children actor without channel if failed to create new channel" in new TestScope {
      initialConnection.createChannel() throws new IOException
      recoveredConnection.createChannel() throws new IOException
      actorRef.setState(Connected, Connected(initialConnection))
      actorRef ! create
      expectMsg(ParentShutdownSignal)
      expectMsg(ChannelCreated(testActor))
      state mustEqual disconnected
    }

    "create children actor without channel" in new TestScope {
      actorRef ! create
      expectMsg(ChannelCreated(testActor))
    }

    "notify children if connection lost" in new TestScope {
      actorRef.setState(Connected, Connected(initialConnection))
      actor.shutdownCompleted(shutdownSignal())
      expectMsg(ParentShutdownSignal)
    }

    "notify children when connection established" in new TestScope {
      actorRef ! Connect
      expectMsg(channel)
    }

    "close connection on shutdown" in new TestScope {
      actorRef.setState(Connected, Connected(initialConnection))
      actorRef.stop()
      there was one(initialConnection).close()
    }

    "not become Disconnected when getting an AmqpShutdownSignal because of its own reconnection procedure" in new TestScope {
      // connection actor starts out connected
      actorRef.setState(Connected, Connected(initialConnection))

      // give it a channel actor
      actorRef ! create

      // so the connection actor will send a channel to the newly created channel actor, and a ChannelCreated to the
      // sender of CreateChannel. Both the recipients are testActor here.
      expectMsg(channel)
      expectMsg(ChannelCreated(testActor))

      // tell the actor the connection went away
      actor.shutdownCompleted(shutdownSignal())

      // give connection actor the time to close and reconnect
      expectMsg(ParentShutdownSignal)
      there was one(initialConnection).close()
      expectMsg(channel)

      // now because of this close, RabbitMQ may tell the actor that the previous connection was shut down by the app
      actor.shutdownCompleted(shutdownSignal(initiatedByApplication = true, reference = initialConnection))

      // now, let's see if the ConnectionActor stays responsive
      actorRef ! ProvideChannel
      expectMsg(channel)
    }

    "respond to GetState message" in new TestScope {
      actorRef ! GetState
      expectMsg(Disconnected)
      actorRef.setState(Connected, Connected(initialConnection))
      actorRef ! GetState
      expectMsg(Connected)
    }
  }

  private abstract class TestScope extends ActorScope {
    val channel = mock[Channel]

    def createMockConnection() = {
      val connection = mock[Connection]
      connection.isOpen returns true
      connection.createChannel() returns channel
      connection
    }

    val initialConnection = createMockConnection()
    val recoveredConnection = createMockConnection()

    val factory = {
      val factory = mock[ConnectionFactory]
      factory.newConnection() returns recoveredConnection
      factory
    }
    val create = CreateChannel(null)
    val reconnectionDelay = FiniteDuration(10, SECONDS)
    val setup = mock[(Connection, ActorRef) => Any]
    val actorRef = TestFSMRef(new TestConnectionActor)

    def actor = actorRef.underlyingActor.asInstanceOf[ConnectionActor]
    def state: (State, Data) = actorRef.stateName -> actorRef.stateData
    def disconnected = Disconnected -> NoConnection
    def connectedInitially = Connected -> Connected(initialConnection)
    def connectedAfterRecovery = Connected -> Connected(recoveredConnection)

    def shutdownSignal(initiatedByApplication: Boolean = false, reference: AnyRef = initialConnection) = {
      val shutdownSignal = mock[ShutdownSignalException]
      shutdownSignal.isInitiatedByApplication returns initiatedByApplication
      shutdownSignal.getReference returns reference
      shutdownSignal
    }

    class TestConnectionActor extends ConnectionActor(factory, reconnectionDelay, setup) {
      override def children = Iterable(testActor)
      override def newChild(props: Props, name: Option[String]) = testActor
      override def preStart() {}
    }
  }
}
