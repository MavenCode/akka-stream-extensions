package com.mfglabs.stream.internals.source

import akka.pattern.pipe
import akka.actor.{Props, Status, ActorLogging}
import akka.stream.actor.ActorPublisher

import scala.concurrent.Future

class BulkPullerAsync[A](offset: Long)(f: (Long, Int) => Future[(Seq[A], Boolean)]) extends ActorPublisher[A] with ActorLogging {
  import akka.stream.actor.ActorPublisherMessage._
  implicit val ec = context.dispatcher

  def receive = waitingForDownstreamReq(offset, Vector.empty, stopAfterBuf = false)

  case object Pull

  def waitingForDownstreamReq(s: Long, buf: Seq[A], stopAfterBuf: Boolean): Receive = {
    case Request(_) | Pull =>
      if (totalDemand > 0 && isActive) {
        nextElements(s, totalDemand.toInt, buf, stopAfterBuf).pipeTo(self)
        context.become(waitingForFut(s, buf, totalDemand))
      }

    case Cancel => context.stop(self)
  }

  private def nextElements(s: Long, n: Int, buf: Seq[A], stopAfterBuf: Boolean): Future[(Seq[A], Boolean)] =
    if (buf.nonEmpty && (buf.size >= n || stopAfterBuf)) Future.successful((Seq.empty, stopAfterBuf))
    else f(s, n)

  def waitingForFut(s: Long, buf: Seq[A], beforeFutDemand: Long): Receive = {
    case (as: Seq[A], stop: Boolean) =>
      val (requestedAs, keep) = (buf ++ as).splitAt(beforeFutDemand.toInt)
      requestedAs.foreach(onNext)
      if (keep.isEmpty && stop) {
        onComplete()
      } else {
        if (totalDemand > 0) self ! Pull
        context.become(waitingForDownstreamReq(s + as.length, keep, stop))
      }

    case Request(_) | Pull => // ignoring until we receive the future response

    case Status.Failure(err) =>
      context.become(waitingForDownstreamReq(s, Seq.empty, stopAfterBuf = false))
      if (totalDemand > 0) self ! Pull
      onError(err)

    case Cancel => context.stop(self)
  }

}

object BulkPullerAsync {
  def props[A](offset: Long)(f: (Long, Int) => Future[(Seq[A], Boolean)]) = Props(new BulkPullerAsync[A](offset)(f))
}



class BulkPullerAsyncWithErrorMgt[A]
  (offset: Long)(f: (Long, Int) => Future[(Seq[A], Boolean)])
                (contErrFn: (Throwable, Int) => Boolean)
  extends ActorPublisher[A]
  with ActorLogging {
  
  import akka.stream.actor.ActorPublisherMessage._
  implicit val ec = context.dispatcher

  def receive = waitingForDownstreamReq(offset, Vector.empty, stopAfterBuf = false, 0)

  case object Pull

  def waitingForDownstreamReq(s: Long, buf: Seq[A], stopAfterBuf: Boolean, retryNb: Int): Receive = {
    case Request(_) | Pull =>
      if (totalDemand > 0 && isActive) {
        nextElements(s, totalDemand.toInt, buf, stopAfterBuf).pipeTo(self)
        context.become(waitingForFut(s, buf, totalDemand, retryNb))
      }

    case Cancel => context.stop(self)
  }

  private def nextElements(s: Long, n: Int, buf: Seq[A], stopAfterBuf: Boolean): Future[(Seq[A], Boolean)] =
    if (buf.nonEmpty && (buf.size >= n || stopAfterBuf)) Future.successful((Seq.empty, stopAfterBuf))
    else f(s, n)

  def waitingForFut(s: Long, buf: Seq[A], beforeFutDemand: Long, retryNb: Int): Receive = {
    case (as: Seq[A], stop: Boolean) =>
      val (requestedAs, keep) = (buf ++ as).splitAt(beforeFutDemand.toInt)
      requestedAs.foreach(onNext)
      if (keep.isEmpty && stop) {
        onComplete()
      } else {
        if (totalDemand > 0) self ! Pull
        // we have received data so it means, the retry succeeded so reset retryNb to 0
        context.become(waitingForDownstreamReq(s + as.length, keep, stop, 0))
      }

    case Request(_) | Pull => // ignoring until we receive the future response

    case Status.Failure(err) =>
      // context.become(waitingForDownstreamReq(s, Seq.empty, stopAfterBuf = false))
      if (!contErrFn(err, retryNb)) {
        onError(err)
      }
      else {
        if (totalDemand > 0) self ! Pull
        context.become(waitingForDownstreamReq(s, Seq.empty, stopAfterBuf = false, retryNb + 1))
      }

    case Cancel => context.stop(self)
  }

}



object BulkPullerAsyncWithErrorMgt {
  def props[A](offset: Long)(f: (Long, Int) => Future[(Seq[A], Boolean)])(contFn: (Throwable, Int) => Boolean) = Props(new BulkPullerAsyncWithErrorMgt[A](offset)(f)(contFn))
}