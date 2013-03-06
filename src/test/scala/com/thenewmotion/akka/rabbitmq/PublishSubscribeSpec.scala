package com.thenewmotion.akka.rabbitmq

import org.specs2.mutable.SpecificationWithJUnit
import com.thenewmotion.akka.rabbitmq.ChannelActor.ChannelMessage
import akka.actor.{Props, ActorSystem}
import com.rabbitmq.client._
import com.thenewmotion.akka.rabbitmq.ConnectionActor.{Created, Create}
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

      def setupPublisher(channel: Channel) {
        val queue = channel.queueDeclare().getQueue
        channel.queueBind(queue, exchange, "")
      }

      connection ! Create(Props(new ChannelActor(setupPublisher)), Some("publisher"))
      val Created(publisher) = expectMsgType[Created]


      def setupSubscriber(channel: Channel) {
        val queue = channel.queueDeclare().getQueue
        channel.queueBind(queue, exchange, "")
        val consumer = new DefaultConsumer(channel) {
          override def handleDelivery(consumerTag: String, envelope: Envelope, properties: AMQP.BasicProperties, body: Array[Byte]) {
            testActor ! fromBytes(body)
          }
        }
        channel.basicConsume(queue, true, consumer)
      }

      connection ! Create(Props(new ChannelActor(setupSubscriber)), Some("subscriber"))
      val Created(subscriber) = expectMsgType[Created]


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
