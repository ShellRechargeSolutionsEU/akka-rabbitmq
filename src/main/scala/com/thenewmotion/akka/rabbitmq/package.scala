package com.thenewmotion.akka

import akka.actor.{Props, ActorRef}
import akka.util.Timeout
import concurrent.duration._
import concurrent.Await
import com.rabbitmq.{client => rabbit}

/**
 * @author Yaroslav Klymko
 */
package object rabbitmq {
  type Connection = rabbit.Connection
  type Channel = rabbit.Channel
  type ConnectionFactory = rabbit.ConnectionFactory
  type BasicProperties = rabbit.AMQP.BasicProperties
  type Envelope = rabbit.Envelope
  type DefaultConsumer = rabbit.DefaultConsumer

  type OnChannel = Channel => Any

  case class CreateChannel(props: Props, name: Option[String] = None)
  case class ChannelCreated(channel: ActorRef)

  case class ChannelMessage(onChannel: OnChannel, dropIfNoChannel: Boolean = true)

  implicit def reachConnectionFactory(x: ConnectionFactory) = new {
    def uri: String = "amqp://%s@%s:%d/%s".format(x.getUsername, x.getHost, x.getPort, x.getVirtualHost)
  }

  implicit def reachConnectionActor(connection: ActorRef) = new {
    def createChannel(props: Props, name: Option[String] = None)
                     (implicit timeout: Timeout = Timeout(2 seconds)): ActorRef = {
      import akka.pattern.ask
      val future = connection ? CreateChannel(props, name)
      Await.result(future, timeout.duration).asInstanceOf[ChannelCreated].channel
    }
  }
}