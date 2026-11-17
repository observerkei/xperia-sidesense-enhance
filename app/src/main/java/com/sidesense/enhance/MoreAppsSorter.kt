package com.sidesense.enhance

import android.content.ComponentName
import android.content.Context
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.text.Collator

/**
 * MoreAppsSorter - 点击 "More apps" 后出现的应用列表: 显示全部应用, 并按名称字母序排序
 *
 * 原版列表 (AllAppsView) 由 AppListTask.doInBackground 在后台组装:
 *   自定义应用 + 预测应用 (排在前面) + 其余应用, 并且排除主栏已显示的应用。
 * 这里不替换原版列表项: 以原版返回的列表为基础 (列表项对象保持不变, 点击行为与原版一致),
 * 只把被原版排除的应用 (来自 AllApps 缓存) 追加进来, 再整体按名称字母序重排。
 * 分屏 / TW 模式下原版有额外的过滤逻辑, 不追加, 只做排序。
 * "最近应用" 区域是另一个视图 (AppsView), 不受影响。
 *
 * 用法 (在 Main.kt 里): MoreAppsSorter.install(this, classLoader)
 * 日志开关见 SsLog.kt。
 */
object MoreAppsSorter {

    private const val APPS = "com.sonymobile.sidesenseapp.launcher.apps"

    /** true: 追加被原版排除的应用 (主栏里已显示的); false: 只重排原版列表 */
    private const val SHOW_ALL_APPS = true

    /** true: 按名称字母序重排 (Collator); false: 保持 "原版结果 + 追加项" 的顺序 */
    private const val SORT_AGAIN = true

    fun install(module: XposedModule, cl: ClassLoader) {
        val task = cl.loadClass("$APPS.allapps.ui.AllAppsView\$AppListTask")
        val allApps = cl.loadClass("$APPS.suggestion.list.AllApps")
        val baseItem = cl.loadClass("$APPS.suggestion.list.Item\$BaseAppItem")
        val appItem = cl.loadClass("$APPS.suggestion.list.Item\$AppItem")

        // BaseAppItem.getText(): 应用显示名称 (子类 AppItem 重写, 反射调用会走虚派发)
        val getText = baseItem.getDeclaredMethod("getText").apply { isAccessible = true }
        // AppItem.getComponentName(): 用于去重和日志
        val getComponentName = appItem.getDeclaredMethod("getComponentName").apply { isAccessible = true }

        // AllApps.getInstance(Context) / getLauncherAppList(): 原版缓存的全部应用
        val getInstance = allApps.getDeclaredMethod("getInstance", Context::class.java)
            .apply { isAccessible = true }
        val getLauncherAppList = allApps.getDeclaredMethod("getLauncherAppList")
            .apply { isAccessible = true }

        // AppListTask 的字段: mContext, 外部类 this$0 (AllAppsView)
        val contextField = task.getDeclaredField("mContext").apply { isAccessible = true }
        val outerField = task.getDeclaredField("this\$0").apply { isAccessible = true }

        // AsyncTask.doInBackground(Void...): 参数是 Void[] (可变参数), 返回最终显示的列表。
        // 类里还有一个 doInBackground(Object[]) 的桥接方法, 这里按名称取非桥接的那个,
        // 不依赖参数类型, 避免签名写错。
        val doInBackground = task.declaredMethods
            .first { it.name == "doInBackground" && !it.isBridge && !it.isSynthetic }
            .apply { isAccessible = true }
        SsLog.i { "target method: $doInBackground" }

        // 在类及其父类中按名称找方法 (getExtraValues 声明在父类 BaseView)
        fun findMethod(start: Class<*>, name: String): Method? {
            var c: Class<*>? = start
            while (c != null) {
                c.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }
                    ?.let { return it.apply { isAccessible = true } }
                c = c.superclass
            }
            return null
        }

        fun boolField(obj: Any, name: String): Boolean = try {
            obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.getBoolean(obj)
        } catch (_: Throwable) {
            false
        }

        fun cnOf(item: Any?): ComponentName? = try {
            getComponentName.invoke(item) as? ComponentName
        } catch (_: Throwable) {
            null
        }

        // 取 AllApps 缓存的全部应用; 分屏取不到时返回 null
        fun fullList(taskObj: Any?): List<*>? {
            if (taskObj == null) return null
            val outer = outerField.get(taskObj) ?: return null
            val extra = findMethod(outer.javaClass, "getExtraValues")?.invoke(outer) ?: return null
            if (boolField(extra, "isSplitScreen")) return null
            val ctx = contextField.get(taskObj) as? Context ?: return null
            return getLauncherAppList.invoke(getInstance.invoke(null, ctx)) as? List<*>
        }

        // 缓存里有、但原版结果里没有的应用 (按 ComponentName 判断)
        fun missingApps(taskObj: Any?, original: List<*>): List<Any> {
            val full = fullList(taskObj) ?: return emptyList()
            val have = HashSet<ComponentName>()
            for (item in original) cnOf(item)?.let { have.add(it) }
            return full.filterNotNull().filter { item ->
                val cn = cnOf(item)
                cn != null && cn !in have
            }
        }

        // 日志: 列表前 5 项的 "序号.包名(名称)"
        fun top5(items: List<*>): String = items.take(5).mapIndexed { i, item ->
            val pkg = cnOf(item)?.packageName ?: "?"
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
                val original = result as? List<*>
                if (original == null) {
                    SsLog.i { "doInBackground returned non-list: ${result?.javaClass?.name}" }
                    return@intercept result
                }

                val extra = if (SHOW_ALL_APPS) missingApps(chain.thisObject, original) else emptyList()
                SsLog.i { "hook called, original=${original.size}, extra=${extra.size}" }
                if (extra.isNotEmpty()) SsLog.i { "extra top5: ${top5(extra)}" }
                SsLog.i { "before top5: ${top5(original)}" }

                // 原版列表项原样保留, 只追加缺失的应用
                val base = ArrayList<Any>(original.size + extra.size)
                original.filterNotNull().forEach { base.add(it) }
                base.addAll(extra)
                if (base.size < 2) return@intercept result

                if (!SORT_AGAIN) {
                    return@intercept if (extra.isEmpty()) result else base
                }

                // 与原版一致使用 Collator, 中文系统下按拼音排序
                val collator = Collator.getInstance()

                // 先取出名称再排序, 避免比较时反复反射; sortedWith 是稳定排序
                val sorted = base
                    .map { it to (getText.invoke(it)?.toString() ?: "") }
                    .sortedWith { a, b -> collator.compare(a.second, b.second) }
                    .map { it.first }

                SsLog.i { "after  top5: ${top5(sorted)}" }
                ArrayList(sorted)
            } catch (t: Throwable) {
                // 失败时保持原版结果
                SsLog.w(t) { "failed, keep original result" }
                result
            }
        }

        SsLog.i { "hook installed on AppListTask.doInBackground" }
    }
}