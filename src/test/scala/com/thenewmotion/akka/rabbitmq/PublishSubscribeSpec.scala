package com.thenewmotion.akka.rabbitmq

import java.util.concurrent.TimeUnit

import akka.actor.ActorRef
import akka.testkit.TestProbe
import com.thenewmotion.akka.rabbitmq.ChannelActor.SuccessfullyQueued

import scala.concurrent.duration.FiniteDuration

/**
 * @author Yaroslav Klymko
 */
class PublishSubscribeSpec extends ActorSpec {
  "PublishSubscribe" should {

    "Publish and Subscribe" in new TestScope {
      val factory = new ConnectionFactory()
      val connection = system.actorOf(ConnectionActor.props(factory), "rabbitmq")
      val exchange = "amq.direct"

      def setupPublisher(channel: Channel, self: ActorRef) {
        val queue = channel.queueDeclare().getQueue
        channel.queueBind(queue, exchange, "")
      }

      connection ! CreateChannel(ChannelActor.props(setupPublisher), Some("publisher"))
      val ChannelCreated(publisher) = expectMsgType[ChannelCreated]

      def setupSubscriber(channel: Channel, self: ActorRef) {
        val queue = channel.queueDeclare().getQueue
        channel.queueBind(queue, exchange, "")
        val consumer = new DefaultConsumer(channel) {
          override def handleDelivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]) {
            messageCollector.ref ! fromBytes(body)
          }
        }
        channel.basicConsume(queue, true, consumer)
      }

      connection ! CreateChannel(ChannelActor.props(setupSubscriber), Some("subscriber"))
      val ChannelCreated(subscriber) = expectMsgType[ChannelCreated]

      val msgs = 1 to 33
      msgs.foreach { x =>
        publisher ! ChannelMessage(ch => {
          ch.basicPublish(exchange, "", null, toBytes(x))
          testActor ! SuccessfullyQueued
        }, dropIfNoChannel = false)
      }

      expectMsgAllOf(FiniteDuration(33, TimeUnit.SECONDS), msgs.map(_ => SuccessfullyQueued): _*)
      messageCollector.expectMsgAllOf(FiniteDuration(33, TimeUnit.SECONDS), msgs: _*)

      def fromBytes(x: Array[Byte]) = new String(x, "UTF-8").toLong

      def toBytes(x: Long) = x.toString.getBytes("UTF-8")
    }

  }

  private abstract class TestScope extends ActorScope {
    val messageCollector = TestProbe()
  }
}
