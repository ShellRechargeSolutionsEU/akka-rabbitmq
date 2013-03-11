package com.thenewmotion.akka

import com.rabbitmq.client.ConnectionFactory
import akka.actor.{Props, ActorRef}
import rabbitmq.ConnectionActor.{Created, Create}
import akka.util.Timeout
import akka.util.duration._

/**
 * @author Yaroslav Klymko
 */
package object rabbitmq {
  implicit def reachConnectionFactory(x: ConnectionFactory) = new {
    def uri: String = "amqp://%s@%s:%d/%s".format(x.getUsername, x.getHost, x.getPort, x.getVirtualHost)
  }

  implicit def reachConnectionActor(connection: ActorRef) = new {
    def createChannel(props: Props, name: Option[String] = None)
                     (implicit timeout: Timeout = Timeout(2 seconds)): ActorRef = {
      import akka.dispatch.Await
      import akka.pattern.ask
      val future = connection ? Create(props, name)
      Await.result(future, timeout.duration).asInstanceOf[Created].channel
    }
  }
}