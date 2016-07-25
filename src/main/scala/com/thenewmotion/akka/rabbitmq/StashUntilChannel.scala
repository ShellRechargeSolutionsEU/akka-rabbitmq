package com.thenewmotion.akka.rabbitmq

import akka.actor.{ Actor, ActorRef }
import scala.collection.immutable.Queue

/**
 * The StashUntilChannel trait makes an actor have a RabbitMQ channel set up with the setupChannel method which should
 * be provided in the actor implementation.
 * The actor can be used immediately. While the channel setup is in progress, incoming messages will be queued and they
 * will be processed by the time the channel is set up.
 *
 * The actor behavior in a StashUntilChannel instance must be defined in the receiveWithChannel method instead of the
 * receive method.
 */
trait StashUntilChannel {
  this: Actor =>

  var channelActor: Option[ActorRef] = None

  private[rabbitmq] case class QueuedMsg(msg: Any, originalSender: ActorRef)

  def connectionActor: ActorRef

  def receiveWithChannel(channelActor: ActorRef): Receive

  private[rabbitmq] def aroundReceiveWithChannel(channelActor: ActorRef): Receive = {
    case item @ QueuedMsg(msg, originalSender) =>
      if (receiveWithChannel(channelActor) isDefinedAt msg)
        receiveWithChannel(channelActor)(msg)
      else
        context.system.deadLetters.tell(msg, originalSender)
    case x => self.tell(QueuedMsg(x, sender()), sender())
  }

  def setupChannel(channel: Channel, channelActor: ActorRef) {}

  def createChannel() {
    connectionActor ! CreateChannel(ChannelActor.props(setupChannel))
  }

  private def receiveChannelCreated(stash: Queue[QueuedMsg]): Receive = {
    case ChannelCreated(channel) =>
      channelActor = Some(channel)
      stash.foreach {
        case item @ QueuedMsg(_, originalSender) =>
          self.tell(item, originalSender)
      }
      context become aroundReceiveWithChannel(channel)

    case x => context become receiveChannelCreated(stash enqueue QueuedMsg(x, sender()))
  }

  def closeChannel() {
    channelActor.foreach(context.stop)
  }

  override def preStart() {
    createChannel()
  }

  def receive = receiveChannelCreated(Queue())

  override def postStop() {
    closeChannel()
  }
}
