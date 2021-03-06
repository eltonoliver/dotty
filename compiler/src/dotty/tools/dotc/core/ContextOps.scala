package dotty.tools.dotc
package core

import Contexts._, Symbols._, Types._, Flags._, Scopes._, Decorators._, NameOps._
import Denotations._
import SymDenotations.LazyType, Names.Name, StdNames.nme
import ast.untpd

/** Extension methods for contexts where we want to keep the ctx.<methodName> syntax */
object ContextOps:

  extension (ctx: Context):

    /** Enter symbol into current class, if current class is owner of current context,
    *  or into current scope, if not. Should always be called instead of scope.enter
    *  in order to make sure that updates to class members are reflected in
    *  finger prints.
    */
    def enter(sym: Symbol): Symbol = inContext(ctx) {
      ctx.owner match
        case cls: ClassSymbol => cls.classDenot.enter(sym)
        case _ => scope.openForMutations.enter(sym)
      sym
    }

    /** The denotation with the given `name` and all `required` flags in current context
     */
    def denotNamed(name: Name, required: FlagSet = EmptyFlags): Denotation = inContext(ctx) {
      if (owner.isClass)
        if (outer.owner == owner) { // inner class scope; check whether we are referring to self
          if (scope.size == 1) {
            val elem = scope.lastEntry
            if (elem.name == name) return elem.sym.denot // return self
          }
          val pre = owner.thisType
          pre.findMember(name, pre, required, EmptyFlags)
        }
        else // we are in the outermost context belonging to a class; self is invisible here. See inClassContext.
          owner.findMember(name, owner.thisType, required, EmptyFlags)
      else
        scope.denotsNamed(name).filterWithFlags(required, EmptyFlags).toDenot(NoPrefix)
    }

    /** A fresh local context with given tree and owner.
    *  Owner might not exist (can happen for self valdefs), in which case
    *  no owner is set in result context
    */
    def localContext(tree: untpd.Tree, owner: Symbol): FreshContext = inContext(ctx) {
      val freshCtx = fresh.setTree(tree)
      if (owner.exists) freshCtx.setOwner(owner) else freshCtx
    }

    /** Context where `sym` is defined, assuming we are in a nested context. */
    def defContext(sym: Symbol): Context = inContext(ctx) {
      outersIterator
        .dropWhile(_.owner != sym)
        .dropWhile(_.owner == sym)
        .next()
    }

    /** A new context for the interior of a class */
    def inClassContext(selfInfo: TypeOrSymbol): Context = inContext(ctx) {
      val localCtx: Context = fresh.setNewScope
      selfInfo match {
        case sym: Symbol if sym.exists && sym.name != nme.WILDCARD => localCtx.scope.openForMutations.enter(sym)
        case _ =>
      }
      localCtx
    }

    def packageContext(tree: untpd.PackageDef, pkg: Symbol): Context = inContext(ctx) {
      if (pkg.is(Package)) fresh.setOwner(pkg.moduleClass).setTree(tree)
      else ctx
    }
end ContextOps