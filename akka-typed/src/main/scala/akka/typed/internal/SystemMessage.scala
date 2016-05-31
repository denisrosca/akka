/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed
package internal

import scala.annotation.tailrec

/**
 * INTERNAL API
 *
 * Helper companion object for [[akka.dispatch.sysmsg.LatestFirstSystemMessageList]] and
 * [[akka.dispatch.sysmsg.EarliestFirstSystemMessageList]]
 */
private[typed] object SystemMessageList {
  final val LNil: LatestFirstSystemMessageList = new LatestFirstSystemMessageList(null)
  final val ENil: EarliestFirstSystemMessageList = new EarliestFirstSystemMessageList(null)

  @tailrec
  private[internal] def sizeInner(head: SystemMessage, acc: Int): Int = if (head eq null) acc else sizeInner(head.next, acc + 1)

  @tailrec
  private[internal] def reverseInner(head: SystemMessage, acc: SystemMessage): SystemMessage = {
    if (head eq null) acc else {
      val next = head.next
      head.next = acc
      reverseInner(next, head)
    }
  }
}

/**
 *
 * INTERNAL API
 *
 * Value class supporting list operations on system messages. The `next` field of [[SystemMessage]]
 * is hidden, and can only accessed through the value classes [[akka.dispatch.sysmsg.LatestFirstSystemMessageList]] and
 * [[akka.dispatch.sysmsg.EarliestFirstSystemMessageList]], abstracting over the fact that system messages are the
 * list nodes themselves. If used properly, this stays a compile time construct without any allocation overhead.
 *
 * This list is mutable.
 *
 * The type of the list also encodes that the messages contained are in reverse order, i.e. the head of the list is the
 * latest appended element.
 *
 */
private[typed] class LatestFirstSystemMessageList(val head: SystemMessage) extends AnyVal {
  import SystemMessageList._

  /**
   * Indicates if the list is empty or not. This operation has constant cost.
   */
  final def isEmpty: Boolean = head eq null

  /**
   * Indicates if the list has at least one element or not. This operation has constant cost.
   */
  final def nonEmpty: Boolean = head ne null

  /**
   * Indicates if the list is empty or not. This operation has constant cost.
   */
  final def size: Int = sizeInner(head, 0)

  /**
   * Gives back the list containing all the elements except the first. This operation has constant cost.
   *
   * *Warning:* as the underlying list nodes (the [[SystemMessage]] instances) are mutable, care
   * should be taken when passing the tail to other methods. [[akka.dispatch.sysmsg.SystemMessage#unlink]] should be
   * called on the head if one wants to detach the tail permanently.
   */
  final def tail: LatestFirstSystemMessageList = new LatestFirstSystemMessageList(head.next)

  /**
   * Reverses the list. This operation mutates the underlying list. The cost of the call to reverse is linear in the
   * number of elements.
   *
   * The type of the returned list is of the opposite order: [[akka.dispatch.sysmsg.EarliestFirstSystemMessageList]]
   */
  final def reverse: EarliestFirstSystemMessageList = new EarliestFirstSystemMessageList(reverseInner(head, null))

  /**
   * Attaches a message to the current head of the list. This operation has constant cost.
   */
  final def ::(msg: SystemMessage): LatestFirstSystemMessageList = {
    assert(msg ne null)
    msg.next = head
    new LatestFirstSystemMessageList(msg)
  }

}

/**
 *
 * INTERNAL API
 *
 * Value class supporting list operations on system messages. The `next` field of [[SystemMessage]]
 * is hidden, and can only accessed through the value classes [[akka.dispatch.sysmsg.LatestFirstSystemMessageList]] and
 * [[akka.dispatch.sysmsg.EarliestFirstSystemMessageList]], abstracting over the fact that system messages are the
 * list nodes themselves. If used properly, this stays a compile time construct without any allocation overhead.
 *
 * This list is mutable.
 *
 * This list type also encodes that the messages contained are in reverse order, i.e. the head of the list is the
 * latest appended element.
 *
 */
private[typed] class EarliestFirstSystemMessageList(val head: SystemMessage) extends AnyVal {
  import SystemMessageList._

  /**
   * Indicates if the list is empty or not. This operation has constant cost.
   */
  final def isEmpty: Boolean = head eq null

  /**
   * Indicates if the list has at least one element or not. This operation has constant cost.
   */
  final def nonEmpty: Boolean = head ne null

  /**
   * Indicates if the list is empty or not. This operation has constant cost.
   */
  final def size: Int = sizeInner(head, 0)

  /**
   * Gives back the list containing all the elements except the first. This operation has constant cost.
   *
   * *Warning:* as the underlying list nodes (the [[SystemMessage]] instances) are mutable, care
   * should be taken when passing the tail to other methods. [[akka.dispatch.sysmsg.SystemMessage#unlink]] should be
   * called on the head if one wants to detach the tail permanently.
   */
  final def tail: EarliestFirstSystemMessageList = new EarliestFirstSystemMessageList(head.next)

  /**
   * Reverses the list. This operation mutates the underlying list. The cost of the call to reverse is linear in the
   * number of elements.
   *
   * The type of the returned list is of the opposite order: [[akka.dispatch.sysmsg.LatestFirstSystemMessageList]]
   */
  final def reverse: LatestFirstSystemMessageList = new LatestFirstSystemMessageList(reverseInner(head, null))

  /**
   * Attaches a message to the current head of the list. This operation has constant cost.
   */
  final def ::(msg: SystemMessage): EarliestFirstSystemMessageList = {
    assert(msg ne null)
    msg.next = head
    new EarliestFirstSystemMessageList(msg)
  }

  /**
   * Prepends a list in a reversed order to the head of this list. The prepended list will be reversed during the process.
   *
   * Example: (3, 4, 5) reversePrepend (2, 1, 0) == (0, 1, 2, 3, 4, 5)
   *
   * The cost of this operation is linear in the size of the list that is to be prepended.
   */
  final def reverse_:::(other: LatestFirstSystemMessageList): EarliestFirstSystemMessageList = {
    var remaining = other
    var result = this
    while (remaining.nonEmpty) {
      val msg = remaining.head
      remaining = remaining.tail
      result ::= msg
    }
    result
  }

}

/**
 * System messages are handled specially: they form their own queue within
 * each actor’s mailbox. This queue is encoded in the messages themselves to
 * avoid extra allocations and overhead. The next pointer is a normal var, and
 * it does not need to be volatile because in the enqueuing method its update
 * is immediately succeeded by a volatile write and all reads happen after the
 * volatile read in the dequeuing thread. Afterwards, the obtained list of
 * system messages is handled in a single thread only and not ever passed around,
 * hence no further synchronization is needed.
 *
 * INTERNAL API
 *
 * <b>NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS</b>
 */
private[typed] sealed trait SystemMessage extends Serializable {
  // Next fields are only modifiable via the SystemMessageList value class
  @transient
  private[internal] var next: SystemMessage = _

  def unlink(): Unit = next = null

  def unlinked: Boolean = next eq null
}

/**
 * INTERNAL API
 */
private[typed] trait StashWhenWaitingForChildren

/**
 * INTERNAL API
 */
private[typed] trait StashWhenFailed

/**
 * INTERNAL API
 */
@SerialVersionUID(1L)
private[typed] final case class Create() extends SystemMessage // sent to self from Dispatcher.register
/**
 * INTERNAL API
 */
@SerialVersionUID(1L)
private[typed] final case class Recreate(cause: Throwable) extends SystemMessage with StashWhenWaitingForChildren // sent to self from ActorCell.restart
/**
 * INTERNAL API
 */
@SerialVersionUID(1L)
private[typed] final case class Suspend() extends SystemMessage with StashWhenWaitingForChildren // sent to self from ActorCell.suspend
/**
 * INTERNAL API
 */
@SerialVersionUID(1L)
private[typed] final case class Resume(causedByFailure: Throwable) extends SystemMessage with StashWhenWaitingForChildren // sent to self from ActorCell.resume
/**
 * INTERNAL API
 */
@SerialVersionUID(1L)
private[typed] final case class Terminate() extends SystemMessage // sent to child from ActorCell.stop

/**
 * INTERNAL API
 */
@SerialVersionUID(1L)
private[typed] final case class Watch(watchee: ActorRef[Nothing], watcher: ActorRef[Nothing]) extends SystemMessage // sent to establish a DeathWatch
/**
 * INTERNAL API
 */
@SerialVersionUID(1L) // Watch and Unwatch have different signatures, but this can't be changed without breaking serialization compatibility
private[typed] final case class Unwatch(watchee: ActorRef[Nothing], watcher: ActorRef[Nothing]) extends SystemMessage // sent to tear down a DeathWatch
/**
 * INTERNAL API
 */
@SerialVersionUID(1L)
private[akka] case object NoMessage extends SystemMessage // switched into the mailbox to signal termination

/**
 * INTERNAL API
 */
@SerialVersionUID(1L)
private[akka] final case class Failed(child: ActorRef[Nothing], cause: Throwable, uid: Int) extends SystemMessage
  with StashWhenFailed
  with StashWhenWaitingForChildren

@SerialVersionUID(1L)
private[akka] final case class DeathWatchNotification(
  actor: ActorRef[Nothing],
  addressTerminated: Boolean) extends SystemMessage
