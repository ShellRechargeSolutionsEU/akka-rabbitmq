package com.thenewmotion.akka

import akka.actor.{ Props, ActorRef }
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Await
import com.rabbitmq.{ client => rabbit }

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

  implicit class RichConnectionFactory(val self: ConnectionFactory) extends AnyVal {
    def uri: String = "amqp://%s@%s:%d/%s".format(self.getUsername, self.getHost, self.getPort, self.getVirtualHost)
  }

  implicit class RichConnectionActor(val self: ActorRef) extends AnyVal {
    def createChannel(props: Props, name: Option[String] = None)(implicit timeout: Timeout = Timeout(2.seconds)): ActorRef = {
      import akka.pattern.ask
      val future = self ? CreateChannel(props, name)
      Await.result(future, timeout.duration).asInstanceOf[ChannelCreated].channel
    }
  }
}
