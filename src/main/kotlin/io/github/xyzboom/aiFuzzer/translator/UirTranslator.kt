package io.github.xyzboom.aiFuzzer.translator

import io.github.xyzboom.aiFuzzer.ir.UirElement

/**
 * 将 UIR 元素翻译为特定编译器的输入格式。
 *
 * @param IR 输入 IR 元素类型
 * @param R  输出类型（字符串、字节数组等）
 */
interface UirTranslator<in IR : UirElement, out R> {
    fun translate(element: IR): R
}