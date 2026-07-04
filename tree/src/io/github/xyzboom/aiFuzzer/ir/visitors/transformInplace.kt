package io.github.xyzboom.aiFuzzer.ir.visitors
import io.github.xyzboom.aiFuzzer.ir.UirElement
import io.github.xyzboom.aiFuzzer.ir.visitors.UirTransformer

fun <T : UirElement, D> MutableList<T>.transformInplace(
    transformer: UirTransformer<D>, data: D
) {
    val it = listIterator()
    while (it.hasNext()) {
        val el = it.next()
        @Suppress("UNCHECKED_CAST")
        val t = el.transform<T, D>(transformer, data)
        if (t !== el) it.set(t)
    }
}
