package com.sidesense.enhance

import android.content.ComponentName
import io.github.libxposed.api.XposedModule
import java.text.Collator

/**
 * MoreAppsSorter - 点击 "More apps" 后出现的应用列表按名称字母序排序
 *
 * 原版列表 (AllAppsView) 由 AppListTask.doInBackground 在后台组装:
 *   自定义应用 + 预测应用 (排在前面) + 其余应用 (字母序)
 * 这里对 doInBackground 的返回值整体重排, 因此预测/自定义应用也会并入字母序。
 * "最近应用" 区域是另一个视图 (AppsView), 不受影响。
 *
 * 用法 (在 Main.kt 里): MoreAppsSorter.install(this, classLoader)
 * 日志开关见 SsLog.kt。
 */
object MoreAppsSorter {

    private const val APPS = "com.sonymobile.sidesenseapp.launcher.apps"

    fun install(module: XposedModule, cl: ClassLoader) {
        val task = cl.loadClass("$APPS.allapps.ui.AllAppsView\$AppListTask")
        val baseItem = cl.loadClass("$APPS.suggestion.list.Item\$BaseAppItem")
        val appItem = cl.loadClass("$APPS.suggestion.list.Item\$AppItem")

        // BaseAppItem.getText(): 应用显示名称 (子类 AppItem 重写, 反射调用会走虚派发)
        val getText = baseItem.getDeclaredMethod("getText").apply { isAccessible = true }
        // AppItem.getComponentName(): 用于取包名 (仅日志用)
        val getComponentName = appItem.getDeclaredMethod("getComponentName").apply { isAccessible = true }

        // AsyncTask.doInBackground(Void...): 参数是 Void[] (可变参数), 返回最终显示的列表。
        // 类里还有一个 doInBackground(Object[]) 的桥接方法, 这里按名称取非桥接的那个,
        // 不依赖参数类型, 避免签名写错。
        val doInBackground = task.declaredMethods
            .first { it.name == "doInBackground" && !it.isBridge && !it.isSynthetic }
            .apply { isAccessible = true }
        SsLog.i { "target method: $doInBackground" }

        // 日志: 列表前 5 项的 "序号.包名(名称)"
        fun top5(items: List<*>): String = items.take(5).mapIndexed { i, item ->
            val pkg = try {
                (getComponentName.invoke(item) as? ComponentName)?.packageName
            } catch (_: Throwable) {
                null
            } ?: "?"
            val label = try {
                getText.invoke(item)?.toString()
            } catch (_: Throwable) {
                null
            } ?: "?"
            "${i + 1}.$pkg($label)"
        }.joinToString(" | ")

        module.hook(doInBackground).intercept { chain ->
            val result = chain.proceed()
            try {
                val src = result as? List<*>
                if (src == null) {
                    SsLog.i { "doInBackground returned non-list: ${result?.javaClass?.name}" }
                    return@intercept result
                }
                SsLog.i { "hook called, size=${src.size}" }
                SsLog.i { "before top5: ${top5(src)}" }
                if (src.size < 2) return@intercept result

                // 与原版一致使用 Collator, 中文系统下按拼音排序
                val collator = Collator.getInstance()

                // 先取出名称再排序, 避免比较时反复反射; sortedWith 是稳定排序
                val sorted = src.filterNotNull()
                    .map { it to (getText.invoke(it)?.toString() ?: "") }
                    .sortedWith { a, b -> collator.compare(a.second, b.second) }
                    .map { it.first }

                SsLog.i { "after  top5: ${top5(sorted)}" }
                ArrayList(sorted)
            } catch (t: Throwable) {
                // 排序失败时保持原版顺序
                SsLog.w(t) { "sort failed, keep original order" }
                result
            }
        }

        SsLog.i { "hook installed on AppListTask.doInBackground" }
    }
}