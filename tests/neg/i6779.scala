type F[T]
type G[T]
type Stuff
given Stuff = ???

extension [T](x: T) def f(using Stuff): F[T] = ???


def g1[T](x: T): F[G[T]] = x.f(using summon[Stuff]) // error

def g2[T](x: T): F[G[T]] = x.f // error

def g3[T](x: T): F[G[T]] = extension_f(x)(using summon[Stuff]) // error
