# Akka RabbitMQ client [![Build Status](https://secure.travis-ci.org/thenewmotion/akka-rabbitmq.png)](http://travis-ci.org/thenewmotion/akka-rabbitmq)

This small library allows you use [rabbitmq client](http://www.rabbitmq.com/java-client.html) via [Akka Actors](http://akka.io).

Basically it gives you two actors `ConnectionActor` and `ChannelActor`

### ConnectionActor
* handles connection failures and notifies children
* keep trying to reconnect if connection lost
* provides children with new channels when needed

### ChannelActor
* may store messages in memory if channel lost
* send stored messages as soon as new channel received
* retrieve new channel if current is broken

## Examples:

### Publish/Subscribe

Here is [RabbitMQ Publish/Subscribe](http://www.rabbitmq.com/tutorials/tutorial-three-java.html) in actors style

```scala
object PublishSubscribe extends App {
  implicit val system = ActorSystem()
  val factory = new ConnectionFactory()
  val connection = system.actorOf(Props(new ConnectionActor(factory)), "rabbitmq")
  val exchange = "amq.fanout"


  def setupPublisher(channel: Channel) {
    val queue = channel.queueDeclare().getQueue
    channel.queueBind(queue, exchange, "")
  }
  connection ! Create(Props(new ChannelActor(setupPublisher)), Some("publisher"))


  def setupSubscriber(channel: Channel) {
    val queue = channel.queueDeclare().getQueue
    channel.queueBind(queue, exchange, "")
    val consumer = new DefaultConsumer(channel) {
      override def handleDelivery(consumerTag: String, envelope: Envelope, properties: AMQP.BasicProperties, body: Array[Byte]) {
        println("received: " + fromBytes(body))
      }
    }
    channel.basicConsume(queue, true, consumer)
  }
  connection ! Create(Props(new ChannelActor(setupSubscriber)), Some("subscriber"))


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
```

## Setup

1. Add this repository to your pom.xml:
```xml
    <repository>
        <id>thenewmotion</id>
        <name>The New Motion Repository</name>
        <url>http://nexus.thenewmotion.com/content/repositories/releases-public</url>
    </repository>
```

2. Add dependency to your pom.xml:
```xml
    <dependency>
        <groupId>com.thenewmotion.akka</groupId>
        <artifactId>akka-rabbitmq_2.9.2</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </dependency>
```