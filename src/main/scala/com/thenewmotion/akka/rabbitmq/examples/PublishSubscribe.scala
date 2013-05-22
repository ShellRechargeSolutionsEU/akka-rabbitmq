package com.thenewmotion.akka.rabbitmq
package examples

import akka.actor.{Props, ActorSystem}
import concurrent.Future
import concurrent.ExecutionContext.Implicits.global

/**
 * @author Yaroslav Klymko
 */
object PublishSubscribe extends App {
  implicit val system = ActorSystem()
  val factory = new ConnectionFactory()
  val connection = system.actorOf(Props(new ConnectionActor(factory)), "rabbitmq")
  val exchange = "amq.fanout"


  def setupPublisher(channel: Channel) {
    val queue = channel.queueDeclare().getQueue
    channel.queueBind(queue, exchange, "")
  }
  connection ! CreateChannel(Props(new ChannelActor(setupPublisher)), Some("publisher"))


  def setupSubscriber(channel: Channel) {
    val queue = channel.queueDeclare().getQueue
    channel.queueBind(queue, exchange, "")
    val consumer = new DefaultConsumer(channel) {
      override def handleDelivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]) {
        println("received: " + fromBytes(body))
      }
    }
    channel.basicConsume(queue, true, consumer)
  }
  connection ! CreateChannel(Props(new ChannelActor(setupSubscriber)), Some("subscriber"))


  Future {
    def loop(n: Long) {
      val publisher = system.actorFor("/user/rabbitmq/publisher")

      def publish(channel: Channel) {
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
