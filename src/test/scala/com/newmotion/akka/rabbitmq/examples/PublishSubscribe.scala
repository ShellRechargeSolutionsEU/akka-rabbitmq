package com.newmotion.akka.rabbitmq
package examples

import akka.actor.{ ActorRef, ActorSystem }

import concurrent.Future
import concurrent.ExecutionContext.Implicits.global
import scala.annotation.tailrec

/**
 * @author Yaroslav Klymko
 */
object PublishSubscribe extends App {
  implicit val system: ActorSystem = ActorSystem()
  val factory = new ConnectionFactory()
  val connection = system.actorOf(ConnectionActor.props(factory), "akka-rabbitmq")
  val exchange = "amq.fanout"

  def setupPublisher(channel: Channel, self: ActorRef) = {
    val queue = channel.queueDeclare().getQueue
    channel.queueBind(queue, exchange, "")
  }
  connection ! CreateChannel(ChannelActor.props(setupPublisher), Some("publisher"))

  def setupSubscriber(channel: Channel, self: ActorRef) = {
    val queue = channel.queueDeclare().getQueue
    channel.queueBind(queue, exchange, "")
    val consumer = new DefaultConsumer(channel) {
      override def handleDelivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]): Unit = {
        println("received: " + fromBytes(body))
      }
    }
    channel.basicConsume(queue, true, consumer)
  }
  connection ! CreateChannel(ChannelActor.props(setupSubscriber), Some("subscriber"))

  Future {
    @tailrec
    def loop(n: Long): Unit = {
      val publisher = system.actorSelection("/user/rabbitmq/publisher")

      def publish(channel: Channel): Unit = {
        channel.basicPublish(exchange, "", null, toBytes(n))
      }
      publisher ! ChannelMessage(publish, dropIfNoChannel = false)

      Thread.sleep(1000)
      loop(n + 1)
    }
    loop(0)
  }

  def fromBytes(x: Array[Byte]) = new String(x, "UTF-8")
  def toBytes(x: Long) = x.toString.getBytes("UTF-8")
}
