package dotty.tools
package dotc
package core

import Types._
import Flags._
import Contexts._
import util.{SimpleIdentityMap, SimpleIdentitySet, DotClass}
import reporting._
import printing.{Showable, Printer}
import printing.Texts._
import config.Config
import collection.mutable
import java.lang.ref.WeakReference
import Decorators._

object TyperState {
  @sharable private var nextId: Int = 0
}

class TyperState(previous: TyperState /* | Null */) {

  val id = TyperState.nextId
  TyperState.nextId += 1

  private[this] var myReporter =
    if (previous == null) new ConsoleReporter() else previous.reporter

  def reporter: Reporter = myReporter

  /** A fresh type state with the same constraint as this one and the given reporter */
  def setReporter(reporter: Reporter): this.type = { myReporter = reporter; this }

  private[this] var myConstraint: Constraint =
    if (previous == null) new OrderingConstraint(SimpleIdentityMap.Empty, SimpleIdentityMap.Empty, SimpleIdentityMap.Empty)
    else previous.constraint

  def constraint = myConstraint
  def constraint_=(c: Constraint)(implicit ctx: Context) = {
    if (Config.debugCheckConstraintsClosed && isGlobalCommittable) c.checkClosed()
    myConstraint = c
  }

  /** Reset constraint to `c` and mark current constraint as retracted if it differs from `c` */
  def resetConstraintTo(c: Constraint) = {
    if (c `ne` myConstraint) myConstraint.markRetracted()
    myConstraint = c
  }

  private val previousConstraint =
    if (previous == null) constraint else previous.constraint

  private[this] var myIsCommittable = true

  def isCommittable = myIsCommittable

  def setCommittable(committable: Boolean): this.type = { this.myIsCommittable = committable; this }

  def isGlobalCommittable: Boolean =
    isCommittable && (previous == null || previous.isGlobalCommittable)

  private[this] var isShared = false

  /** Mark typer state as shared (typically because it is the typer state of
   *  the creation context of a source definition that potentially still needs
   *  to be completed). Members of shared typer states are never overwritten in `test`.
   */
  def markShared(): Unit = isShared = true

  private[this] var isCommitted = false

  /** A fresh typer state with the same constraint as this one. */
  def fresh(): TyperState =
    new TyperState(this).setReporter(new StoreReporter(reporter)).setCommittable(isCommittable)

  /** The uninstantiated variables */
  def uninstVars = constraint.uninstVars

  /** The set of uninstantiated type variables which have this state as their owning state */
  private[this] var myOwnedVars: TypeVars = SimpleIdentitySet.empty
  def ownedVars = myOwnedVars
  def ownedVars_=(vs: TypeVars): Unit = myOwnedVars = vs

  /** Gives for each instantiated type var that does not yet have its `inst` field
   *  set, the instance value stored in the constraint. Storing instances in constraints
   *  is done only in a temporary way for contexts that may be retracted
   *  without also retracting the type var as a whole.
   */
  def instType(tvar: TypeVar)(implicit ctx: Context): Type = constraint.entry(tvar.origin) match {
    case _: TypeBounds => NoType
    case tp: TypeParamRef =>
      var tvar1 = constraint.typeVarOfParam(tp)
      if (tvar1.exists) tvar1 else tp
    case tp => tp
  }

  /** The closest ancestor of this typer state (including possibly this typer state itself)
   *  which is not yet committed, or which does not have a parent.
   */
  def uncommittedAncestor: TyperState =
    if (isCommitted) previous.uncommittedAncestor else this

  private[this] var testReporter: TestReporter = null

  /** Test using `op`. If current typerstate is shared, run `op` in a fresh exploration
   *  typerstate. If it is unshared, run `op` in current typerState, restoring typerState
   *  to previous state afterwards.
   */
  def test[T](op: Context => T)(implicit ctx: Context): T =
    if (isShared)
      op(ctx.fresh.setExploreTyperState())
    else {
      val savedConstraint = myConstraint
      val savedReporter = myReporter
      val savedCommittable = myIsCommittable
      val savedCommitted = isCommitted
      myIsCommittable = false
      myReporter = {
        if (testReporter == null || testReporter.inUse) {
          testReporter = new TestReporter(reporter)
        } else {
          testReporter.reset()
        }
        testReporter.inUse = true
        testReporter
      }
      try op(ctx)
      finally {
        testReporter.inUse = false
        resetConstraintTo(savedConstraint)
        myReporter = savedReporter
        myIsCommittable = savedCommittable
        isCommitted = savedCommitted
      }
    }

  /** Commit typer state so that its information is copied into current typer state
   *  In addition (1) the owning state of undetermined or temporarily instantiated
   *  type variables changes from this typer state to the current one. (2) Variables
   *  that were temporarily instantiated in the current typer state are permanently
   *  instantiated instead.
   *
   *  A note on merging: An interesting test case is isApplicableSafe.scala. It turns out that this
   *  requires a context merge using the new `&' operator. Sequence of actions:
   *  1) Typecheck argument in typerstate 1.
   *  2) Cache argument.
   *  3) Evolve same typer state (to typecheck other arguments, say)
   *     leading to a different constraint.
   *  4) Take typechecked argument in same state.
   *
   * It turns out that the merge is needed not just for
   * isApplicableSafe but also for (e.g. erased-lubs.scala) as well as
   * many parts of dotty itself.
   */
  def commit()(implicit ctx: Context) = {
    val targetState = ctx.typerState
    assert(isCommittable)
    targetState.constraint =
      if (targetState.constraint eq previousConstraint) constraint
      else targetState.constraint & constraint
    constraint foreachTypeVar { tvar =>
      if (tvar.owningState.get eq this) tvar.owningState = new WeakReference(targetState)
    }
    targetState.ownedVars ++= ownedVars
    targetState.gc()
    reporter.flush()
    isCommitted = true
  }

  /** Make type variable instances permanent by assigning to `inst` field if
   *  type variable instantiation cannot be retracted anymore. Then, remove
   *  no-longer needed constraint entries.
   */
  def gc()(implicit ctx: Context): Unit = {
    val toCollect = new mutable.ListBuffer[TypeLambda]
    constraint foreachTypeVar { tvar =>
      if (!tvar.inst.exists) {
        val inst = instType(tvar)
        if (inst.exists && (tvar.owningState.get eq this)) {
          tvar.inst = inst
          val lam = tvar.origin.binder
          if (constraint.isRemovable(lam)) toCollect += lam
        }
      }
    }
    for (poly <- toCollect)
      constraint = constraint.remove(poly)
  }

  override def toString: String = s"TS[$id]"

  def stateChainStr: String = s"$this${if (previous == null) "" else previous.stateChainStr}"
}

/** Temporary, reusable reporter used in TyperState#test */
private class TestReporter(outer: Reporter) extends StoreReporter(outer) {
  /** Is this reporter currently used in a test? */
  var inUse = false

  def reset() = {
    assert(!inUse, s"Cannot reset reporter currently in use: $this")
    infos = null
  }
}
