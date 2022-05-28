package com.phaller.blocks

import scala.annotation.targetName

import upickle.default.*

import scala.quoted.*


/**
  * The type of a *block*, a special kind of closure with an explicit
  * environment. The environment of a block is a single, internal
  * value or reference whose type is indicated by the `Block` trait's
  * `Env` type member.
  *
  * Blocks are created in one of two ways: either using the factory
  * methods of the `Block` companion object, or using a *block
  * builder*. Block builders are top-level objects extending either
  * [[Builder]] or [[Block.Builder]].
  *
  * Like a regular function type, the type of blocks is contravariant
  * in its parameter type and covariant in its result type.
  *
  * @tparam T the parameter type
  * @tparam R the result type
  */
sealed trait Block[-T, +R] extends (T => R) {

  /** The type of the block's environment.
    */
  type Env

  /** Applies the block to the given argument.
    *
    * @param x the argument of the block application
    */
  def apply(x: T): R

  private[blocks] def applyInternal(x: T)(env: Env): R =
    throw new Exception("Method must be overridden")

  private[blocks] def envir: Env

}

sealed class CheckedFunction[T, R] private[blocks] (val body: T => R)

private[blocks] def createChecked[T, R](body: T => R) =
  new CheckedFunction[T, R](body)

/** Checks that the argument function is a function literal that does
  * not capture any variable of an enclosing scope.
  *
  * @param  fun  the function
  * @tparam T    the function's parameter type
  * @tparam R    the function's result type
  * @return a `CheckedFunction` instance compatible with a block builder
  */
inline def checked[T, R](inline fun: T => R): CheckedFunction[T, R] =
  ${ checkedFunctionCode('fun) }

def checkedFunctionCode[T, R](funExpr: Expr[T => R])(using Type[T], Type[R], Quotes): Expr[CheckedFunction[T, R]] = {
  Block.checkBodyExpr(funExpr)

  '{ createChecked[T, R]($funExpr) }
}

class Builder[T, R](checkedFun: CheckedFunction[T, R]) extends TypedBuilder[Nothing, T, R] {

  private[blocks] def createBlock(envOpt: Option[String]): Block[T, R] =
    apply() // envOpt is empty

  def apply[E](): Block[T, R] { type Env = E } =
    new Block[T, R] {
      type Env = E
      def apply(x: T): R =
        checkedFun.body(x)
      override private[blocks] def applyInternal(x: T)(env: Env): R =
        checkedFun.body(x)
      private[blocks] def envir =
        throw new Exception("block does not have an environment")
    }

}

trait TypedBuilder[E, T, R] extends PackedBuilder[T, R]

trait PackedBuilder[T, R] {
  private[blocks] def createBlock(envOpt: Option[String]): Block[T, R]
}

/** The `Block` companion object provides factory methods as well as
  * the [[Block.Builder]] class for creating block builders.
  */
object Block {

  /** Applies a block with parameter type `Unit`.
    */
  extension [R](block: Block[Unit, R])
    def apply(): R = block.apply(())

  sealed class CheckedClosure[E, T, R] private[blocks] (val body: T => E => R)

  private[blocks] def createChecked[E, T, R](fun: T => E => R) =
    new CheckedClosure[E, T, R](fun)

  /** Checks that the argument function is a function literal that does
    * not capture any variable of an enclosing scope. Returns a
    * `CheckedClosure` instance for creating a block with non-empty
    * environment of type `E`.
    *
    * @param  fun  the function
    * @tparam E    the environment type of the corresponding block
    * @tparam T    the function's parameter type
    * @tparam R    the function's result type
    * @return a `CheckedClosure` instance compatible with a block builder
    */
  inline def checked[E, T, R](inline fun: T => E => R): CheckedClosure[E, T, R] =
    ${ checkedClosureCode('fun) }

  def checkedClosureCode[E, T, R](funExpr: Expr[T => E => R])(using Type[E], Type[T], Type[R], Quotes): Expr[CheckedClosure[E, T, R]] = {
    checkBodyExpr(funExpr)

    '{ createChecked[E, T, R]($funExpr) }
  }

  class Builder[E, T, R](checkedFun: CheckedClosure[E, T, R])(using ReadWriter[E]) extends TypedBuilder[E, T, R] {

    private[blocks] def createBlock(envOpt: Option[String]): Block[T, R] = {
      // actually creates a Block[T, R] { type Env = E }
      // envOpt is non-empty
      val env = read[E](envOpt.get)
      apply(env)
    }

    def apply(env: E): Block[T, R] { type Env = E } =
      new Block[T, R] {
        type Env = E
        def apply(x: T): R =
          checkedFun.body(x)(env)
        override private[blocks] def applyInternal(x: T)(y: Env): R =
          checkedFun.body(x)(y)
        private[blocks] val envir =
          env
      }

  }

  given [E: Duplicable, A, B]: Duplicable[Block[A, B] { type Env = E }] =
    new Duplicable[Block[A, B] { type Env = E }] {
      def duplicate(fun: Block[A, B] { type Env = E }) = {
        val env = summon[Duplicable[E]].duplicate(fun.envir)
        new Block[A, B] {
          type Env = E
          def apply(x: A): B =
            fun.applyInternal(x)(env)
          override private[blocks] def applyInternal(x: A)(y: Env): B =
            fun.applyInternal(x)(y)
          private[blocks] val envir = env
        }
      }
    }

  // how to duplicate a block without environment
  given [A, B]: Duplicable[Block[A, B] { type Env = Nothing }] =
    new Duplicable[Block[A, B] { type Env = Nothing }] {
      def duplicate(fun: Block[A, B] { type Env = Nothing }) = {
        new Block[A, B] {
          type Env = Nothing
          def apply(x: A): B =
            fun.apply(x) // ignore environment
          override private[blocks] def applyInternal(x: A)(y: Nothing): B =
            fun.applyInternal(x)(y)
          private[blocks] def envir =
            throw new Exception("block does not have an environment")
        }
      }
    }

  /** Creates a block given an environment value/reference and a
    * function.  The given function must not capture anything.  The
    * second (curried) parameter must be used to access the block's
    * environment. The given function must be a *function literal*
    * which is checked at compile time (using a macro).
    *
    * @tparam E the type of the block's environment
    * @tparam T the block's parameter type
    * @tparam R the block's result type
    * @param env  the block's environment
    * @param body the block's body
    * @return a block initialized with the given environment and body
    */
  inline def apply[E, T, R](inline env: E)(inline body: T => E => R): Block[T, R] { type Env = E } =
    ${ applyCode('env, 'body) }

  def checkBodyExpr[T, S](bodyExpr: Expr[T => S])(using Quotes): Unit = {
    import quotes.reflect.{Block => BlockTree, *}

    def symIsToplevelObject(sym: Symbol): Boolean =
      sym.flags.is(Flags.Module) && sym.owner.flags.is(Flags.Package)

    def ownerChainContains(sym: Symbol, transitiveOwner: Symbol): Boolean =
      if (sym.maybeOwner.isNoSymbol) false
      else ((sym.owner == transitiveOwner) || ownerChainContains(sym.owner, transitiveOwner))

    def checkCaptures(defdefSym: Symbol, anonfunBody: Tree): Unit = {
      /* collect all identifier uses.
         check that they don't have an owner outside the anon fun.
         uses of top-level objects are OK.
       */

      val acc = new TreeAccumulator[List[Ident]] {
        def foldTree(ids: List[Ident], tree: Tree)(owner: Symbol): List[Ident] = tree match {
          case id @ Ident(_) => id :: ids
          case _ =>
            try {
              foldOverTree(ids, tree)(owner)
            } catch {
              case me: MatchError =>
                // compiler bug: skip checking tree
                ids
            }
        }
      }
      val foundIds = acc.foldTree(List(), anonfunBody)(defdefSym)
      val foundSyms = foundIds.map(id => id.symbol)
      val names = foundSyms.map(sym => sym.name)
      val ownerNames = foundSyms.map(sym => sym.owner.name)

      val allOwnersOK = foundSyms.forall(sym =>
        ownerChainContains(sym, defdefSym) ||
          symIsToplevelObject(sym) || ((!sym.maybeOwner.isNoSymbol) && symIsToplevelObject(sym.owner)) || ((!sym.maybeOwner.isNoSymbol) && (!sym.owner.maybeOwner.isNoSymbol) && symIsToplevelObject(sym.owner.owner))) // example: `ExecutionContext.Implicits.global`

      // report error if not all owners OK
      if (!allOwnersOK) {
        foundIds.foreach { id =>
          val sym = id.symbol
          val isOwnedByToplevelObject =
            symIsToplevelObject(sym) || ((!sym.maybeOwner.isNoSymbol) && symIsToplevelObject(sym.owner)) || ((!sym.maybeOwner.isNoSymbol) && (!sym.owner.maybeOwner.isNoSymbol) && symIsToplevelObject(sym.owner.owner))

          val isOwnedByBlock = ownerChainContains(sym, defdefSym)
          if (!isOwnedByToplevelObject) {
            // might find illegal capturing
            if (!isOwnedByBlock)
              report.error(s"Invalid capture of variable `${id.name}`. Use `Block.env` to refer to the block's environment.", id.pos)
          }
        }
      }
    }

    val tree = bodyExpr.asTerm
    tree match {
      case Inlined(None, List(),
        TypeApply(Select(BlockTree(List(), BlockTree(
          List(defdef @ DefDef(anonfun, params, _, Some(anonfunBody))), Closure(_, _)
        )), asInst), _)
      ) =>
        checkCaptures(defdef.symbol, anonfunBody)

      case Inlined(None, List(),
        TypeApply(Select(BlockTree(
          List(defdef @ DefDef(anonfun, params, _, Some(anonfunBody))), Closure(_, _)
        ), asInst), _)
      ) =>
        checkCaptures(defdef.symbol, anonfunBody)

      case Inlined(None, List(),
        BlockTree(List(defdef @ DefDef(anonfun, params, _, Some(anonfunBody))), Closure(_, _))) =>
        checkCaptures(defdef.symbol, anonfunBody)

      case Inlined(None, List(), BlockTree(List(),
        BlockTree(List(defdef @ DefDef(anonfun, params, _, Some(anonfunBody))), Closure(_, _)))) =>
        checkCaptures(defdef.symbol, anonfunBody)

      case _ =>
        val str = tree.show(using Printer.TreeStructure)
        report.error(s"Argument must be a function literal", tree.pos)
    }
  }

  private def applyCode[E, T, R](envExpr: Expr[E], bodyExpr: Expr[T => E => R])(using Type[E], Type[T], Type[R], Quotes): Expr[Block[T, R] { type Env = E }] = {
    checkBodyExpr(bodyExpr)

    '{
      new Block[T, R] {
        type Env = E
        def apply(x: T): R = $bodyExpr(x)($envExpr)
        override private[blocks] def applyInternal(x: T)(env: E): R =
          $bodyExpr(x)(env)
        private[blocks] val envir = $envExpr
      }
    }
  }

  /** Creates a block without an environment. The given body (function)
    * must not capture anything.
    *
    * @tparam T the block's parameter type
    * @tparam R the block's result type
    * @param body the block's body
    * @return a block with the given body
    */
  inline def apply[T, R](inline body: T => R): Block[T, R] { type Env = Nothing } =
    ${ applyCode('body) }

  private def applyCode[T, R](bodyExpr: Expr[T => R])(using Type[T], Type[R], Quotes): Expr[Block[T, R] { type Env = Nothing }] = {
    checkBodyExpr(bodyExpr)

    '{
      new Block[T, R] {
        type Env = Nothing
        def apply(x: T): R = $bodyExpr(x)
        private[blocks] def envir =
          throw new Exception("block does not have an environment")
      }
    }
  }

  /** Creates a thunk block. The given body must be a function literal
    * that does not capture anything.
    *
    * @tparam T the type of the block's environment
    * @tparam R the block's result type
    * @param env  the block's environment
    * @param body the block's body
    * @return a block initialized with the given environment and body
    */
  inline def thunk[T, R](inline env: T)(inline body: T => R): Block[Unit, R] { type Env = T } =
    ${ thunkCode('env, 'body) }

  private def thunkCode[T, R](envExpr: Expr[T], bodyExpr: Expr[T => R])(using Type[T], Type[R], Quotes): Expr[Block[Unit, R] { type Env = T }] = {
    checkBodyExpr(bodyExpr)

    '{
      new Block[Unit, R] {
        type Env = T
        def apply(x: Unit): R = $bodyExpr($envExpr)
        override private[blocks] def applyInternal(x: Unit)(env: T): R =
          $bodyExpr(env)
        private[blocks] val envir = $envExpr
      }
    }
  }

}
