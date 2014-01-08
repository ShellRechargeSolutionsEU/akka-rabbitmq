package com.thenewmotion.akka.rabbitmq

import akka.actor.{ Actor, ActorRef }
import scala.collection.immutable.Queue

/**
 * @author Yaroslav Klymko
 */
trait StashUntilChannel {
  this: Actor =>

  var channelActor: Option[ActorRef] = None

  def connectionActor: ActorRef

  def receiveWithChannel(channelActor: ActorRef): Receive

  def setupChannel(channel: Channel, channelActor: ActorRef) {}

  def createChannel() {
    connectionActor ! CreateChannel(ChannelActor.props(setupChannel))
  }

  def receiveChannelCreated(stash: Queue[Any]): Receive = {
    case ChannelCreated(channel) =>
      channelActor = Some(channel)
      val receive = receiveWithChannel(channel)
      stash.foreach {
        x =>
          if (receive isDefinedAt x) receive apply x
          else context.system.deadLetters ! x
      }
      context become receive

    case x => context become receiveChannelCreated(stash enqueue x)
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