package com.thenewmotion.akka.rabbitmq

import org.specs2.mutable.SpecificationWithJUnit
import akka.actor.{ActorRef, Props, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit}
import org.specs2.specification.Scope

/**
 * @author Yaroslav Klymko
 */
class PublishSubscribeSpec extends SpecificationWithJUnit {
  "PublishSubscribe" should {

    "Publish and Subscribe" in new TestScope {
      val factory = new ConnectionFactory()
      val connection = system.actorOf(Props(new ConnectionActor(factory)), "rabbitmq")
      val exchange = "amq.fanout"

      def setupPublisher(channel: Channel, self: ActorRef) {
        val queue = channel.queueDeclare().getQueue
        channel.queueBind(queue, exchange, "")
      }

      connection ! CreateChannel(Props(new ChannelActor(setupPublisher)), Some("publisher"))
      val ChannelCreated(publisher) = expectMsgType[ChannelCreated]


      def setupSubscriber(channel: Channel, self: ActorRef) {
        val queue = channel.queueDeclare().getQueue
        channel.queueBind(queue, exchange, "")
        val consumer = new DefaultConsumer(channel) {
          override def handleDelivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]) {
            testActor ! fromBytes(body)
          }
        }
        channel.basicConsume(queue, true, consumer)
      }

      connection ! CreateChannel(Props(new ChannelActor(setupSubscriber)), Some("subscriber"))
      val ChannelCreated(subscriber) = expectMsgType[ChannelCreated]


      val msgs = (0L to 100)
      msgs.foreach(x =>
        publisher ! ChannelMessage(_.basicPublish(exchange, "", null, toBytes(x)), dropIfNoChannel = false))

      expectMsgAllOf(msgs: _*)

      def fromBytes(x: Array[Byte]) = new String(x, "UTF-8").toLong
      def toBytes(x: Long) = x.toString.getBytes("UTF-8")
    }

  }

  private abstract class TestScope extends TestKit(ActorSystem()) with ImplicitSender with Scope
}
