/**
 * Copyright 2011-2016 GatlingCorp (http://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.core.action

import scala.annotation.tailrec

import io.gatling.commons.stats.KO
import io.gatling.commons.util.TimeHelper.nowMillis
import io.gatling.core.session._
import io.gatling.core.stats.StatsEngine

/**
 * Describes an interruption to be performed.
 *
 * @param nextAction    the action to execute next, instead of following the regular workflow.
 * @param nextSession   the new Session to be sent to nextActor
 * @param groupsToClose the groups to be closed as we bypass the regular GroupEnd from the regular flow
 */
case class BlockExit(nextAction: Action, nextSession: Session, groupsToClose: List[GroupBlock]) {

  def exitBlock(statsEngine: StatsEngine): Unit = {
    val now = nowMillis
    groupsToClose.foreach(statsEngine.logGroupEnd(nextSession, _, now))
    nextAction.execute(nextSession)
  }
}

object BlockExit {

  /**
   * Recursively loop on a block List until a stop point and build the Interruption to execute.
   *
   * @param blocks        the blocks to scan
   * @param until         the exit point of the loop
   * @param action        the next action to chain when resolving the Interruption
   * @param session       the session so far
   * @param groupsToClose the groups so far to close when resolving the Interruption
   * @return the Interruption to process
   */
  @tailrec
  private def blockExit(blocks: List[Block], until: Block, action: Action, session: Session, groupsToClose: List[GroupBlock]): BlockExit = blocks match {

    case Nil => BlockExit(action, session, groupsToClose)

    case head :: tail => head match {
      case `until`               => BlockExit(action, session, groupsToClose)
      case group: GroupBlock     => blockExit(tail, until, action, session.exitGroup, group :: groupsToClose)
      case tryMap: TryMaxBlock   => blockExit(tail, until, action, session.exitTryMax, groupsToClose)
      case counter: CounterBlock => blockExit(tail, until, action, session.exitLoop, groupsToClose)
    }
  }

  /**
   * Scan the block stack for ExitAsap loops.
   * Scan is performed from right to left (right is deeper) = normal.
   *
   * @param session the session
   * @return the potential Interruption to process
   */
  private def exitAsapLoop(session: Session): Option[BlockExit] = {

      @tailrec
      def exitAsapLoopRec(leftToRightBlocks: List[Block]): Option[BlockExit] = leftToRightBlocks match {
        case Nil => None

        case head :: tail => head match {

          case ExitAsapLoopBlock(_, condition, loopActor) if !LoopBlock.continue(condition, session) =>
            val exit = blockExit(session.blockStack, head, loopActor, session, Nil)
            Some(exit)

          case _ => exitAsapLoopRec(tail)
        }
      }

    exitAsapLoopRec(session.blockStack.reverse)
  }

  /**
   * Scan the block stack for TryMax loops.
   * Scan is performed from right to left (right is deeper) = normal.
   *
   * @param session the session
   * @return the potential Interruption to process
   */
  private def exitTryMax(session: Session): Option[BlockExit] = {

      @tailrec
      def exitTryMaxRec(stack: List[Block]): Option[BlockExit] = stack match {
        case Nil => None

        case head :: tail => head match {

          case TryMaxBlock(_, tryMaxActor, KO) =>
            val exit = blockExit(session.blockStack, head, tryMaxActor, session, Nil)
            Some(exit)

          case _ => exitTryMaxRec(tail)
        }
      }

    exitTryMaxRec(session.blockStack)
  }

  def noBlockExitTriggered(session: Session, statsEngine: StatsEngine): Boolean =
    exitAsapLoop(session).orElse(exitTryMax(session)) match {
      case None => true
      case Some(blockExit) =>
        blockExit.exitBlock(statsEngine)
        false
    }
}

/**
 * An Action that can trigger a forced exit and bypass regular workflow.
 */
trait ExitableAction extends ChainableAction {

  def statsEngine: StatsEngine

  abstract override def !(session: Session): Unit =
    if (BlockExit.noBlockExitTriggered(session, statsEngine)) {
      super.!(session)
    }
}
