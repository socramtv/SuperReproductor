package com.example.superplayer.telegram

import java.lang.reflect.Modifier

/** Diagnóstico: lista por reflexión la API real de la librería ntgcalls (clases, métodos, campos). */
object NtgProbe {
    fun dump(): String {
        val out = StringBuilder()
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque<Class<*>>()
        try {
            queue.add(Class.forName("io.github.pytgcalls.NTgCalls"))
        } catch (e: Throwable) {
            return "No se pudo cargar NTgCalls: $e"
        }
        while (queue.isNotEmpty() && seen.size < 80) {
            val c = queue.removeFirst()
            if (!seen.add(c.name)) continue
            out.append("== ").append(c.name).append(if (c.isEnum) " (enum)" else if (c.isInterface) " (interface)" else "").append('\n')
            if (c.isEnum) {
                out.append("  ").append(c.enumConstants?.joinToString(", ").orEmpty()).append('\n')
            }
            try {
                c.declaredFields.filter { !it.isSynthetic && !c.isEnum }.forEach {
                    out.append("  campo ").append(it.type.simpleName).append(' ').append(it.name).append('\n')
                    enqueue(it.type, queue)
                }
                c.declaredConstructors.filter { !c.isEnum && Modifier.isPublic(it.modifiers) }.forEach { k ->
                    out.append("  new(").append(k.parameterTypes.joinToString(", ") { it.simpleName }).append(")\n")
                    k.parameterTypes.forEach { enqueue(it, queue) }
                }
                c.declaredMethods.filter { !it.isSynthetic && !c.isEnum && Modifier.isPublic(it.modifiers) }.sortedBy { it.name }.forEach { m ->
                    out.append("  ").append(if (Modifier.isStatic(m.modifiers)) "static " else "")
                        .append(m.returnType.simpleName).append(' ').append(m.name).append('(')
                        .append(m.parameterTypes.joinToString(", ") { it.simpleName }).append(")\n")
                    enqueue(m.returnType, queue)
                    m.parameterTypes.forEach { enqueue(it, queue) }
                }
            } catch (e: Throwable) {
                out.append("  (error: ").append(e).append(")\n")
            }
        }
        return out.toString()
    }

    private fun enqueue(t: Class<*>, queue: ArrayDeque<Class<*>>) {
        val type = if (t.isArray) t.componentType ?: return else t
        if (type.name.startsWith("io.github.pytgcalls.") && !type.name.contains("exceptions.")) queue.add(type)
    }
}
