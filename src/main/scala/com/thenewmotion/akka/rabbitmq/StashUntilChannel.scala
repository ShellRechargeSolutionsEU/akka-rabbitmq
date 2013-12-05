package com.thenewmotion.akka.rabbitmq

import akka.actor.{ Stash, ActorRef }

/**
 * @author Yaroslav Klymko
 */
trait StashUntilChannel extends Stash {
  this: Stash =>

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
      unstashAll()
      context become receiveWithChannel(channel)

    case x => stash()
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