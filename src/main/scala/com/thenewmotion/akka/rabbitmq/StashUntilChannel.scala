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

  def receiveChannelCreated(stash: Queue[(Any, ActorRef)]): Receive = {
    case ChannelCreated(channel) =>
      channelActor = Some(channel)
      val receive = receiveWithChannel(channel)
      stash.foreach {
        case (msg, originalSender) =>
          val destination = if (receive isDefinedAt msg) self else context.system.deadLetters
          destination.tell(msg, originalSender)
      }
      context become receive

    case x => context become receiveChannelCreated(stash enqueue (x, sender()))
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