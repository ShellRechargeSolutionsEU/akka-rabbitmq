package com.thenewmotion.akka.rabbitmq

import akka.actor.{ Props, ActorRef, Actor }

/**
 * @author Yaroslav Klymko
 */
@deprecated("1.0.0", "use StashUntilChannel instead")
trait WithChannel {
  this: Actor =>

  var channelActor: Option[ActorRef] = None

  def connectionActor: ActorRef
  def receiveWithChannel(channelActor: ActorRef): Receive
  def setupChannel(channel: Channel, channelActor: ActorRef) {}

  def createChannel() {
    connectionActor ! CreateChannel(ChannelActor.props(setupChannel))
  }

  def receiveChannelCreated: Receive = {
    case ChannelCreated(channel) =>
      channelActor = Some(channel)
      context become receiveWithChannel(channel)
  }

  def closeChannel() {
    channelActor.foreach(context.stop)
  }

  override def preStart() {
    createChannel()
  }

  def receive = receiveChannelCreated

  override def postStop() {
    closeChannel()
  }
}
