package com.newmotion.akka.rabbitmq

import java.util.concurrent.TimeUnit
import akka.actor.ActorRef
import ChannelActor.Connected
import ChannelActor.GetState
import com.rabbitmq.client.AMQP.Queue
import com.typesafe.config.Config

import scala.collection.immutable.NumericRange
import scala.concurrent.duration.FiniteDuration

/**
 * @author Yaroslav Klymko
 */
class PublishSubscribeSpec extends ActorSpec {
  "PublishSubscribe" should {

    "Publish and Subscribe" in new TestScope {
      val factory = new ConnectionFactory()
      val config: Config = com.typesafe.config.ConfigFactory.load().getConfig("akka-rabbitmq")
      factory.setHost(config.getString("host"))
      factory.setPort(config.getInt("port"))
      factory.setUsername(config.getString("username"))
      factory.setPassword(config.getString("password"))

      val connection: ActorRef = system.actorOf(ConnectionActor.props(factory), "akka-rabbitmq")
      val exchange = "amq.fanout"

      def setupPublisher(channel: Channel, self: ActorRef): Queue.BindOk = {
        val queue = channel.queueDeclare().getQueue
        channel.queueBind(queue, exchange, "")
      }

      connection ! CreateChannel(ChannelActor.props(setupPublisher), Some("publisher"))
      val ChannelCreated(publisher) = expectMsgType[ChannelCreated]

      def setupSubscriber(channel: Channel, self: ActorRef): String = {
        val queue = channel.queueDeclare().getQueue
        channel.queueBind(queue, exchange, "")
        val consumer = new DefaultConsumer(channel) {
          override def handleDelivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]): Unit = {
            testActor ! fromBytes(body)
          }
        }
        channel.basicConsume(queue, true, consumer)
      }

      connection ! CreateChannel(ChannelActor.props(setupSubscriber), Some("subscriber"))
      val ChannelCreated(subscriber) = expectMsgType[ChannelCreated]

      awaitAssert {
        subscriber ! GetState
        expectMsg(Connected)
      }

      val msgs: NumericRange.Inclusive[Long] = 0L to 33L
      msgs.foreach(x =>
        publisher ! ChannelMessage(_.basicPublish(exchange, "", null, toBytes(x)), dropIfNoChannel = false))

      expectMsgAllOf(FiniteDuration(33, TimeUnit.SECONDS), msgs: _*)

      def fromBytes(x: Array[Byte]): Long = new String(x, "UTF-8").toLong
      def toBytes(x: Long): Array[Byte] = x.toString.getBytes("UTF-8")
    }

  }

  private abstract class TestScope extends ActorScope
}
