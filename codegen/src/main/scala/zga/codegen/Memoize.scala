package zga.codegen

import java.util.concurrent.ConcurrentHashMap

object Memoize {

  def apply[K, V](f: K => V): K => V = {
    val cache = new ConcurrentHashMap[K, V]()
    key => cache.computeIfAbsent(key, k => f(k))
  }
}
