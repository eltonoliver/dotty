---
layout: doc-page
title: "Safe Initialization"
---

Dotty implements experimental safe initialization check, which can be enabled by the compiler option `-Ycheck-init`.

## A Quick Glance

To get a feel of how it works, we first show several examples below.

### Parent-Child Interaction

Given the following code snippet:

``` Scala
abstract class AbstractFile {
   def name: String
   val extension: String = name.substring(4)
}

class RemoteFile(url: String) extends AbstractFile {
   val localFile: String = url.hashCode + ".tmp"       // error: usge of `localFile` before it's initialized
   def name: String = localFile
}
```

The checker will report:

``` scala
-- Warning: tests/init/neg/AbstractFile.scala:7:4 ------------------------------
7 |	val localFile: String = url.hashCode + ".tmp"  // error
  |	    ^
  |    Access non-initialized field value localFile. Calling trace:
  |     -> val extension: String = name.substring(4)	[ AbstractFile.scala:3 ]
  |      -> def name: String = localFile            	[ AbstractFile.scala:8 ]
```

### Inner-Outer Interaction

Given the code below:

``` scala
object Trees {
  class ValDef { counter += 1 }
  class EmptyValDef extends ValDef
  val theEmptyValDef = new EmptyValDef
  private var counter = 0  // error
}
```

The checker will report:

``` scala
-- Warning: tests/init/neg/trees.scala:5:14 ------------------------------------
5 |  private var counter = 0  // error
  |              ^
  |             Access non-initialized field variable counter. Calling trace:
  |              -> val theEmptyValDef = new EmptyValDef    [ trees.scala:4 ]
  |               -> class EmptyValDef extends ValDef       [ trees.scala:3 ]
  |                -> class ValDef { counter += 1 }	     [ trees.scala:2 ]
```

### Functions

Given the code below:

``` scala
abstract class Parent {
  val f: () => String = () => this.message
  def message: String
}
class Child extends Parent {
  val a = f()
  val b = "hello"           // error
  def message: String = b
}
```

The checker reports:

``` scala
-- Warning: tests/init/neg/features-high-order.scala:7:6 -----------------------
7 |  val b = "hello"           // error
  |      ^
  |Access non-initialized field value b. Calling trace:
  | -> val a = f()                              	[ features-high-order.scala:6 ]
  |   -> val f: () => String = () => this.message	[ features-high-order.scala:2 ]
  |    -> def message: String = b	                [ features-high-order.scala:8 ]
```

## Design Goals

We establish the following design goals:

- __Sound__: checking always terminates, and is sound for common and reasonable usage (over-approximation)
- __Expressive__: support common and reasonable initialization patterns
- __Friendly__: simple rules, minimal syntactic overhead, informative error messages
- __Modular__: modular checking, no analysis beyond project boundary
- __Fast__: instant feedback
- __Simple__: no changes to core type system, explainable by a simple theory

By _reasonable usage_, we include the following use cases (but not restricted to them):

- Access fields on `this` and outer `this` during initialization
- Call methods on `this` and outer `this` during initialization
- Instantiate inner class and call methods on such instances during initialization
- Capture fields in functions

## Principles and Rules

To achieve the goals, we uphold three fundamental principles:
_stackability_, _monotonicity_ and _scopability_.

Stackability means that objects are initialized in stack order: if the
object `b` is created during the initialization of object `a`, then
all fields of `b` should become initialized before or at the same time
as `a`.  Scala enforces this property in syntax by demanding that all
fields are initialized at the end of the primary constructor, except
for the language feature below:

``` scala
var x: T = _
```

Control effects such as exceptions may break this property, as the
following example shows:

``` scala
class MyException(val b: B) extends Exception("")
class A {
  val b = try { new B } catch { case myEx: MyException => myEx.b }
  println(b.a)
}
class B {
  throw new MyException(this)
  val a: Int = 1
}
```

In the code above, the control effect teleport the uninitialized value
wrapped in an exception. In the implementation, we avoid the problem
by ensuring that the values that are thrown must be transitively initialized.

Monotonicity means that the initialization status of an object should
not go backward: initialized fields continue to be initialized, a
field points to an initialized object may not later point to an
object under initialization. As an example, the following code will be rejected:

``` scala
trait Reporter { def report(msg: String): Unit }
class FileReporter(ctx: Context) extends Reporter {
    ctx.typer.reporter = this                // ctx now reaches an uninitialized object
    val file: File = new File("report.txt")
    def report(msg: String) = file.write(msg)
}
```

In the code above, suppose `ctx` points to a transitively initialized
object. Now the assignment at line 3 makes `this`, which is not fully
initialized, reachable from `ctx`. This makes field usage dangerous,
as it may indirectly reach uninitialized fields.

Monotonicity is based on a well-known technique called _heap monotonic
typestate_ to ensure soundness in the presence of aliasing
[1]. Otherwise, either soundness will be compromised or we have to
disallow the usage of already initialized fields.

Scopability means that an expression may only access existing objects via formal
parameters and `this`. More precisely, given any environment `ρ` (which are the
value bindings for method parameters and `this`) and heap `σ` for evaluating an expression
`e`, if the resulting value reaches an object `o` pre-existent in `σ`, then `o`
is reachable from `ρ` in `σ`. Control effects like coroutines, delimited
control, resumable exceptions may break the property, as they can transport a
value upper in the stack (not in scope) to be reachable from the current scope.
Static fields can also serve as a teleport thus breaks this property.  In the
implementation, we need to enforce that teleported values are transitively
initialized.

With the established principles and design goals, following rules are imposed:

1. In an assignment `o.x = e`, the expression `e` may only point to transitively initialized objects.

   This is how monotonicity is enforced in the system. Note that in an
   initialization `val f: T = e`, the expression `e` may point to an object
   under initialization. This requires a distinction between mutation and
   initialization in order to enforce different rules. Scala
   has different syntax for them, it thus is not an issue.

2. References to objects under initialization may not be passed as arguments to method calls or constructors.

   Escape of `this` in the constructor is commonly regarded as an
   anti-pattern, and it's rarely used in practice. This rule is simple
   for the programmer to reason about initialization and it simplifies
   implementation. The theory supports safe escape of `this` with the help of
   annotations, we delay the extension until there is a strong need.

3. Local definitions may only refer to transitively initialized objects.

   It means that in a local definition `val x: T = e`, the expression `e` may
   only evaluate to transitively initialized objects. The same goes for local
   lazy variables and methods. This rule is again motivated for simplicity in
   reasoning about initialization: programmers may safely assume that all local
   definitions only point to transitively initialized objects.

## Modularity

For modularity, we forbid subtle initialization interaction beyond project
boundaries. For example, the following code passes the check when the two
classes are defined in the same project:

```Scala
class Base {
  private val map: mutable.Map[Int, String] = mutable.Map.empty
  def enter(k: Int, v: String) = map(k) = v
}
class Child extends Base {
  enter(1, "one")
  enter(2, "two")
}
```

However, when the class `Base` and `Child` are defined in two different
projects, the check will emit a warning for the calls to `enter` in the class
`Child`. This restricts subtle initialization within project boundaries,
and avoids accidental violation of contracts across library versions.

We impose the following rules to enforce modularity:

4. A class or trait that may be extended in another project should not
   call virtual methods on `this` in its template/mixin evaluation,
   directly or indirectly.

5. The method call `o.m(args)` is forbidden if `o` is not transitively
   initialized and the target of `m` is defined in an external project.

6. The expression `new p.C(args)` is forbidden, if `p` is not transitively
   initialized and `C` is defined in an external project.

Theoretically, we may analyze across project boundaries based on tasty. However,
from our experience with Dotty community projects, most subtle initialization
patterns are restricted in the same project. As the rules only report warnings
instead of errors, we think it is good to first impose more strict rules, The
feedback from the community is welcome.

## Theory

The theory is based on type-and-effect systems [2]. We introduce two concepts,
_effects_ and _potentials_:

```
π = C.this | Warm(C, π) | π.f | π.m | π.super[D] | Cold | Fun(Π, Φ) | Outer(C, π)
ϕ = π↑ | π.f! | π.m!
```

Potentials (π) represent values that are possibly under initialization.

- `C.this`: current object
- `Warm(C, π)`: an object of type `C` where all its fields are assigned, and the potential for `this` of its enclosing class is `π`.
- `π.f`: the potential of the field `f` in the potential `π`
- `π.m`: the potential of the field `f` in the potential `π`
- `π.super[D]`: essentially the object π, used for virtual method resolution
- `Cold`: an object with unknown initialization status
- `Fun(Π, Φ)`: a function, when called produce effects Φ and return potentials Π.
- `Outer(C, π)`: the potential of `this` for the enclosing class of `C` when `C.this` is ` π`.

Effects are triggered from potentials:

- `π↑`: promote the object pointed to by the potential `π` to fully-initialized
- `π.f!`: access field `f` on the potential `π`
- `π.m!`: call the method `m` on the potential `π`

To ensure that the checking always terminate and for better
performance, we restrict the length of potentials to be finite (by
default 2). If the potential is too long, the checker stops
tracking it by checking that the potential is actually transitively
initialized.

For an expression `e`, it may be summarized by the pair `(Π, Φ)`,
which means evaluation of `e` may produce the effects Φ and return the
potentials Π. Each field and method is associated with such a pair.
We call such a pair _summary_. The expansion of proxy potentials and effects,
such as `π.f`, `π.m` and `π.m!`, will take advantage of the summaries.
Depending on the potential `π` for `this`, the summaries need to be rebased (`asSeenFrom`) before usage.

The checking treats the templates of concrete classes as entry points.
It maintains the set of initialized fields as initialization
progresses, and check that only initialized fields are accessed during
the initialization and there is no leaking of values under initialization.
Virtual method calls on `this` is not a problem,
as they can always be resolved statically.

More details can be found in a forthcoming paper.

## Back Doors

Occasionally you may want to suppress warnings reported by the
checker.  You can either write `e: @unchecked` to tell the checker to
skip checking for the expression `e`, or you may use the old trick:
mark some fields as lazy.

## Caveats

The system cannot handle static fields, nor does it provide safety
guarantee when extending Java or Scala 2 classes. Calling methods of
Java or Scala 2 is always safe.

## References

- Fähndrich, M. and Leino, K.R.M., 2003, July. _Heap monotonic typestates_. In International Workshop on Aliasing, Confinement and Ownership in object-oriented programming (IWACO).
- Lucassen, J.M. and Gifford, D.K., 1988, January. _Polymorphic effect systems_. In Proceedings of the 15th ACM SIGPLAN-SIGACT symposium on Principles of programming languages (pp. 47-57). ACM.
