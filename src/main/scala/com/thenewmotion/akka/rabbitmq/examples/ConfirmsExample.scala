package com.thenewmotion.akka.rabbitmq
package examples

import akka.actor.{ ActorRef, ActorSystem }
import akka.pattern.ask
import akka.util.Timeout
import com.rabbitmq.client.{ MessageProperties, ConfirmListener }
import scala.collection.mutable
import scala.concurrent.{ Await, ExecutionContext }
import scala.concurrent.duration._

/**
 * The ConfirmTrackLostMessages application shows how you can use publisher confirms to detect if a message is not
 * successfully delivered to RabbitMQ. See https://www.rabbitmq.com/confirms.html for more information on publisher
 * confirms.
 */
object ConfirmsExample extends App with ActorSystemTerminator {

  /* --- Some things shared by publisher and consumer --- */

  val system = ActorSystem()

  implicit val timeout = Timeout(2.seconds)
  implicit val executionContext: ExecutionContext = system.dispatcher

  val queueName = "test-messages"

  /* A set of all messages that we've tried to publish but have not yet been confirmed */
  val unconfirmed = mutable.Set.empty[Long]
  /* A variable to keep track of the latest sequence number that has been explicitly confirmed or disconfirmed */
  var confirmedUpTo: Long = -1

  /* --- Setting up the publisher --- */

  val connFactory = new ConnectionFactory()
  val pubConnActor = system.actorOf(ConnectionActor.props(connFactory), "publisher-connection")

  /* Method to be used by the publishing ChannelActor to set up the channel */
  def setupConfirmingPublisher(ch: Channel, self: ActorRef) {
    ch.queueDeclare(queueName, true, false, true, null)
    ch.confirmSelect()
    ch.addConfirmListener(confirmListener)
    System.out.println("Publisher channel set up")
  }

  /* Tries to publish the given message to RabbitMQ and stores the message ID RabbitMQ will use to confirm it received
   * the message */
  def tryPublish(ch: Channel, message: String) = {
    val seqNo = ch.getNextPublishSeqNo
    ch.basicPublish("", queueName, MessageProperties.PERSISTENT_BASIC, message.getBytes("UTF-8"))
    unconfirmed += seqNo
    System.out.println(s"Published message $seqNo")
  }

  (pubConnActor ? CreateChannel(ChannelActor.props(setupConfirmingPublisher), Some("channel"))).mapTo[ChannelCreated] map {
    case ChannelCreated(chActor) =>
      System.out.println("Publisher channel created")
      system.scheduler.schedule(0.seconds, 2.millis, chActor, ChannelMessage {
        ch =>
          tryPublish(ch, "nop")
      })
  }

  /* --- Setting up the consumer --- */

  /* A ConfirmListener that will be called every time RabbitMQ confirms that it received one or more of our messages */
  val confirmListener = new ConfirmListener {
    override def handleAck(seqNo: Long, multiple: Boolean) =
      if (!multiple) {
        System.out.println(s"Message with ID $seqNo acknowledged")
        unconfirmed -= seqNo
      } else {
        System.out.println(s"Messages with IDs up to $seqNo acknowledged")
        unconfirmed --= unconfirmed.filter(elem => elem <= seqNo && elem > confirmedUpTo)
        confirmedUpTo = seqNo
      }

    // RabbitMQ documentation is itself unclear here on the meaning of the 'multiple' parameter, but we
    // can be conservative and see all messages up to N as not confirmed if message N gets NACK'ed.
    override def handleNack(seqNo: Long, multiple: Boolean) = {
      System.out.println(s"Message(s) with ID(s up to) $seqNo not handled")
      confirmedUpTo = seqNo
    }
  }

  val consumerConnActor = system.actorOf(ConnectionActor.props(connFactory), "consumer-connection")

  /* Method to be used by the consumer channel actor to set up the channel */
  def setupConsumer(ch: Channel, self: ActorRef): Unit = {
    ch.queueDeclare(queueName, true, false, true, null)
    ch.basicConsume(queueName, false /* no autoack */ , new DefaultConsumer(ch) {
      override def handleDelivery(tag: String, env: Envelope, props: BasicProperties, body: Array[Byte]): Unit = {
        System.out.println(s"Consuming msg ${body.map(_.toInt).mkString(",")} with tag $tag")
        ch.basicAck(env.getDeliveryTag, false /* just this one message */ )
      }
    })
  }
  consumerConnActor ! CreateChannel(ChannelActor.props(setupConsumer), Some("channel"))

  /* --- Letting the app run --- */

  Thread.sleep(7000)
  Await.result(terminateActorSystem(system), 1.second)

  System.out.println(s"Unconfirmed messages: ${unconfirmed.mkString(", ")}")
}
