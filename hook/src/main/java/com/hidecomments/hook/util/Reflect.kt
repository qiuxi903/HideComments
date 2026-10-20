package com.hidecomments.hook.util

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 反射工具。
 *
 * 全部方法都「找不到就返回 null」，绝不抛异常 —— 宿主 App 改版后类名或方法名对不上是常态，
 * 模块必须安静地退化成「什么都没屏蔽」，而不是把宿主 App 搞崩。
 */
object Reflect {

    fun findClass(name: String, loaders: List<ClassLoader>): Class<*>? {
        for (loader in loaders) {
            runCatching { Class.forName(name, false, loader) }.getOrNull()?.let { return it }
        }
        return null
    }

    fun findClassAny(names: List<String>, loaders: List<ClassLoader>): Class<*>? {
        for (name in names) {
            findClass(name, loaders)?.let { return it }
        }
        return null
    }

    /** 按方法名 + 精确参数类型找方法。 */
    fun findMethod(clazz: Class<*>?, name: String, vararg params: Class<*>): Method? {
        if (clazz == null) return null
        return runCatching { clazz.getDeclaredMethod(name, *params) }.getOrNull()
            ?: runCatching { clazz.getMethod(name, *params) }.getOrNull()
    }

    /** 收集类自身 + 全部父类上同名的方法。 */
    private fun methodsNamed(clazz: Class<*>?, name: String): List<Method> {
        if (clazz == null) return emptyList()
        val result = LinkedHashSet<Method>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            val declared = runCatching { current.declaredMethods }.getOrNull()
            declared?.forEach { method ->
                if (method.name == name) result.add(method)
            }
            current = current.superclass
        }
        return result.toList()
    }

    fun findMethodsByName(clazz: Class<*>?, name: String): List<Method> = methodsNamed(clazz, name)

    /**
     * 按「名字包含」找方法。
     *
     * 比完全相等更宽松：宿主可能自己定义了一个 `setCurrentItemInternal`，
     * 或者基类把方法改名了，带一点前缀后缀的情形都能捞到。
     */
    fun findMethodsByNameContains(clazz: Class<*>?, fragment: String): List<Method> {
        if (clazz == null) return emptyList()
        val result = LinkedHashSet<Method>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            val declared = runCatching { current.declaredMethods }.getOrNull()
            declared?.forEach { method ->
                if (method.name.contains(fragment)) result.add(method)
            }
            current = current.superclass
        }
        return result.toList()
    }

    /** 同上，但按参数个数过滤。 */
    fun findMethodsByNameAndArgCount(clazz: Class<*>?, name: String, argCount: Int): List<Method> =
        methodsNamed(clazz, name).filter { it.parameterCount == argCount }

    /**
     * 找一个「名字像被混淆过」的静态方法：单字母或双字母命名，且参数里带 ClassLoader。
     *
     * 快手的 DVA 插件加载器 `FeaturePluginLoader.b(...)` 就是这种形态，
     * 而且方法名每次发版都可能从 `b` 变成 `c`，所以只能按特征去捞，不能写死。
     */
    fun findPluginLoaderMethod(clazz: Class<*>?): List<Method> {
        if (clazz == null) return emptyList()
        val result = LinkedHashSet<Method>()

        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            val declared = runCatching { current.declaredMethods }.getOrNull()
            declared?.forEach { method ->
                if (Modifier.isStatic(method.modifiers)) return@forEach
                if (method.name.length > 2) return@forEach
                val hasClassLoader = method.parameterTypes.any { it == ClassLoader::class.java }
                if (hasClassLoader) result.add(method)
            }
            current = current.superclass
        }

        return result.toList()
    }

    fun isStatic(method: Method): Boolean = Modifier.isStatic(method.modifiers)
}
