package com.thenewmotion.akka.rabbitmq
package examples

import akka.actor.{ Actor, ActorRef, Props, ActorSystem }
import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * @author Yaroslav Klymko
 */
class TutorialInComparisons(implicit system: ActorSystem) extends ActorSystemTerminator {

  val connection = {
    val factory = new ConnectionFactory()
    val connection: Connection = factory.newConnection()
    connection
  }

  val connectionActor = {
    val factory = new ConnectionFactory()
    val connectionActor: ActorRef = system.actorOf(ConnectionActor.props(factory))

    system.actorOf(ConnectionActor.props(factory), "my-connection")

    import concurrent.duration._
    system.actorOf(ConnectionActor.props(factory, reconnectionDelay = 10.seconds), "my-connection")

    connectionActor
  }

  val channel = {
    val channel: Channel = connection.createChannel()
    channel
  }

  val channelActor = {
    val channelActor: ActorRef = connectionActor.createChannel(ChannelActor.props())

    connectionActor.createChannel(ChannelActor.props(), Some("my-channel"))

    connectionActor ! CreateChannel(ChannelActor.props())

    connectionActor.createChannel(Props(new Actor {
      def receive = {
        case channel: Channel =>
      }
    }))
    channelActor
  }

  {
    channel.queueDeclare("queue_name", false, false, false, null)
  }

  {
    // this function will be called each time new channel received
    def setupChannel(channel: Channel, self: ActorRef) {
      channel.queueDeclare("queue_name", false, false, false, null)
    }
    val channelActor: ActorRef = connectionActor.createChannel(ChannelActor.props(setupChannel))
  }

  {
    channel.basicPublish("", "queue_name", null, "Hello world".getBytes)
  }

  {
    def publish(channel: Channel) {
      channel.basicPublish("", "queue_name", null, "Hello world".getBytes)
    }
    channelActor ! ChannelMessage(publish)
    channelActor ! ChannelMessage(publish, dropIfNoChannel = false)
  }

  {
    channel.close()
  }

  Await.result({
    system stop channelActor
    system stop connectionActor // will close all channels associated with this connections
    terminateActorSystem(system)
  }, 5.seconds)
}
