
Use Scala 3 in a functional way:

- create an `opaque type` instead of using primitive types
- everything returned from a function is immutable
- mutation is allowed if it is contained with a function or class and does not escape
- use named arguments when constructing a value with multiple fields of the same type, to prevent accidental reordering
- a `case class` does not need to be `final`
