package com.thenewmotion.akka

import com.rabbitmq.client.ConnectionFactory

/**
 * @author Yaroslav Klymko
 */
package object rabbitmq {
  implicit def reachConnectionFactory(x: ConnectionFactory) = new {
    def uri: String = "amqp://%s@%s:%d/%s".format(x.getUsername, x.getHost, x.getPort, x.getVirtualHost)
  }
}