# Akka RabbitMQ client [![Build Status](https://secure.travis-ci.org/thenewmotion/akka-rabbitmq.png)](http://travis-ci.org/thenewmotion/akka-rabbitmq)

This small library allows you use [RabbitMQ client](http://www.rabbitmq.com/java-client.html) via [Akka Actors](http://akka.io).
The main idea implemented in library is to survive losing connection with RabbitMQ server

It gives you two actors `ConnectionActor` and `ChannelActor`

### ConnectionActor
* handles connection failures and notifies children
* keep trying to reconnect if connection lost
* provides children with new channels when needed

### ChannelActor
* may store messages in memory if channel lost
* send stored messages as soon as new channel received
* retrieve new channel if current is broken

## Tutorial in comparisons
### Create connection

Default approach:
```scala
    val factory = new ConnectionFactory()
    val connection: Connection = factory.newConnection()
```

Actor style:
```scala
    val factory = new ConnectionFactory()
    val connectionActor: ActorRef = system.actorOf(Props(new ConnectionActor(factory)))
```

Let's name it:
```scala
    system.actorOf(Props(new ConnectionActor(factory)), "my-connection")
```

How often will it reconnect?
```scala
    import akka.util.duration._
    system.actorOf(Props(new ConnectionActor(factory, reconnectionDelay = 10.seconds)), "my-connection")
```

### Create channel

That's plain option:
```scala
    val channel: Channel = connection.createChannel()
```

But we can do better. Asynchronously:
```scala
    connectionActor ! Create(Props(new ChannelActor()))
```

Synchronously:
```scala
    import com.thenewmotion.akka.rabbitmq.reachConnectionActor

    val channelActor: ActorRef = connectionActor.createChannel(Props(new ChannelActor()))
```

Maybe give it a name:
```scala
    connectionActor.createChannel(Props(new ChannelActor()), Some("my-channel"))
```

What's about custom actor:
```scala
    connectionActor.createChannel(Props(new Actor {
      def receive = {
        case channel: Channel =>
      }
    }))
```

### Setup channel
```scala
    channel.queueDeclare("queue_name", false, false, false, null)
```

Actor style:
```scala
    // this function will be called each time new channel received
    def setupChannel(channel: Channel) {
      channel.queueDeclare("queue_name", false, false, false, null)
    }
    val channelActor: ActorRef = connectionActor.createChannel(Props(new ChannelActor(setupChannel)))
```

### Use channel
```scala
    channel.basicPublish("", "queue_name", null, "Hello world".getBytes)
```

Using our `channelActor`:
```scala
    def publish(channel: Channel) {
      channel.basicPublish("", "queue_name", null, "Hello world".getBytes)
    }
    channelActor ! ChannelMessage(publish)
```

But I don't want to lose messages when connection is lost:
```scala
    channelActor ! ChannelMessage(publish, dropIfNoChannel = false)
```

### Close channel
```scala
    channel.close()
```
VS
```scala
    system stop channelActor
```

### Close connection

```scala
    connection.close()
```
VS
```scala
    system stop connectionActor // will close all channels associated with this connection
```

You can shutdown `ActorSystem`, this will close all connections as well as channels:
```scala
    system.shutdown()
```

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
        <version>0.0.2</version>
    </dependency>
```
