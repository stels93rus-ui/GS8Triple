package ru.gs8triple

import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.regex.Pattern
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private data class AppEntry(val label: String, val packageName: String, val activity: String) {
        override fun toString() = label
    }

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var status: TextView
    private lateinit var diagnostics: TextView
    private lateinit var appCount: TextView
    private var apps: List<AppEntry> = emptyList()
    private val spinners = mutableListOf<Spinner>()
    private val packageKeys = listOf("left_package", "center_package", "right_package")
    private val defaultPackages = listOf("ru.yandex.music", "ru.yandex.yandexnavi", "ru.yandex.rutube")
    private val slotNames = listOf("СЛЕВА — музыка • 400 px", "ПО ЦЕНТРУ — навигатор • 760 px", "СПРАВА — Rutube/любое приложение • 760 px")
    private val bounds = listOf(
        intArrayOf(0, 0, 400, 968),
        intArrayOf(400, 0, 1160, 968),
        intArrayOf(1160, 0, 1920, 968)
    )

    private var remote: IWindowShellService? = null
    private var shizukuBound = false
    private var lastPermissionRequest = false

    private val binderReceived = Shizuku.OnBinderReceivedListener { updateBackendStatus() }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) bindShizukuService()
            else status.text = "Shizuku: разрешение не выдано."
        }
    }
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = IWindowShellService.Stub.asInterface(service)
            status.text = "Shizuku backend подключён. UID=${safeShizukuUid()}"
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            status.text = "Shizuku backend отключился."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("gs8_triple_settings", MODE_PRIVATE)
        apps = loadApps()
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addRequestPermissionResultListener(permissionListener)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 18, 22, 18)
        }
        root.addView(TextView(this).apply { text = "GS8 TRIPLE"; textSize = 28f })
        root.addView(TextView(this).apply {
            text = "GAC GS8 Gen2 • Android 9 • 1920×968"
            textSize = 14f
        })
        root.addView(section("РАСКЛАДКА"))
        root.addView(TextView(this).apply {
            text = "Музыка слева 400 px  |  Навигатор центр 760 px  |  Rutube справа 760 px"
            textSize = 14f
        })
        root.addView(section("ВЫБОР ПРИЛОЖЕНИЙ"))
        appCount = TextView(this).apply { textSize = 12f }
        root.addView(appCount)
        repeat(3) { i ->
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(10, 8, 10, 8)
                setBackgroundColor(0xFFEFEFEF.toInt())
            }
            panel.addView(TextView(this).apply { text = slotNames[i]; textSize = 15f })
            val spinner = Spinner(this)
            spinners.add(spinner)
            panel.addView(spinner)
            root.addView(panel)
        }
        root.addView(Button(this).apply {
            text = "Обновить список приложений"
            setOnClickListener { apps = loadApps(); populateSpinners(); status.text = "Список обновлён." }
        })
        root.addView(Button(this).apply {
            text = "Сохранить назначения"
            setOnClickListener { saveSelections(); status.text = "Назначения сохранены." }
        })
        root.addView(section("УПРАВЛЕНИЕ ОКНАМИ"))
        root.addView(Button(this).apply {
            text = "Подключить Shizuku / запросить доступ"
            setOnClickListener { requestAndBindShizuku() }
        })
        root.addView(Button(this).apply {
            text = "Разложить три окна"
            setOnClickListener {
                saveSelections()
                arrangeWindows()
            }
        })
        root.addView(Button(this).apply {
            text = "Проверить доступ и Freeform"
            setOnClickListener { runDiagnostics() }
        })
        diagnostics = TextView(this).apply { text = "Диагностика ещё не запускалась."; textSize = 12f }
        root.addView(diagnostics)
        status = TextView(this).apply {
            text = "\nВыбери приложения и подключи Shizuku или Root."
            textSize = 14f
        }
        root.addView(status)
        root.addView(TextView(this).apply {
            text = "\nВажно: размещение выполняется через команды Android am. Shizuku должен быть запущен и разрешён для GS8 Triple. Если он работает с UID shell, прошивка должна разрешать нужные команды; Root даёт более широкие права."
            textSize = 11f
        })
        setContentView(ScrollView(this).apply { addView(root) })
        populateSpinners()
        updateBackendStatus()
    }

    private fun section(s: String) = TextView(this).apply {
        text = "\n$s"; textSize = 18f; setPadding(0, 10, 0, 5)
    }

    @Suppress("DEPRECATION")
    private fun loadApps(): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { AppEntry(it.loadLabel(packageManager).toString(), it.activityInfo.packageName, it.activityInfo.name) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun populateSpinners() {
        for (i in spinners.indices) {
            spinners[i].adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, apps).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val saved = prefs.getString(packageKeys[i], defaultPackages[i])
            val index = apps.indexOfFirst { it.packageName == saved }
            spinners[i].setSelection(if (index >= 0) index else 0)
        }
        appCount.text = "Найдено приложений: ${apps.size}"
    }

    private fun saveSelections() {
        val edit = prefs.edit()
        spinners.forEachIndexed { i, spinner ->
            apps.getOrNull(spinner.selectedItemPosition)?.let { edit.putString(packageKeys[i], it.packageName) }
        }
        edit.apply()
    }

    private fun selectedApps(): List<AppEntry> =
        spinners.mapIndexedNotNull { i, spinner ->
            val entry = apps.getOrNull(spinner.selectedItemPosition)
            if (entry == null) null else entry
        }

    private fun requestAndBindShizuku() {
        if (!Shizuku.pingBinder()) {
            status.text = "Shizuku не запущен. Открой Shizuku, запусти службу и вернись сюда."
            return
        }
        if (Shizuku.isPreV11) {
            status.text = "Установленная версия Shizuku слишком старая. Нужна API v11 или новее."
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            lastPermissionRequest = true
            Shizuku.requestPermission(REQUEST_CODE)
        } else {
            bindShizukuService()
        }
    }

    private fun bindShizukuService() {
        if (!Shizuku.pingBinder()) {
            status.text = "Shizuku Binder недоступен."
            return
        }
        try {
            val args = Shizuku.UserServiceArgs(ComponentName(this, WindowShellService::class.java))
                .tag("gs8-triple-shell")
                .version(1)
                .daemon(false)
            Shizuku.bindUserService(args, serviceConnection)
            shizukuBound = true
            status.text = "Запрашиваю Shizuku UserService…"
        } catch (e: Exception) {
            status.text = "Не удалось подключить Shizuku: ${e.message}"
        }
    }

    private fun safeShizukuUid(): String =
        try { Shizuku.getUid().toString() } catch (_: Exception) { "?" }

    private fun updateBackendStatus() {
        if (Shizuku.pingBinder()) {
            status.text = "Shizuku обнаружен. UID=${safeShizukuUid()}. Нажми «Подключить Shizuku»."
        } else {
            status.text = "Shizuku не обнаружен. Можно проверить Root или запустить Shizuku."
        }
    }

    private fun execute(args: Array<String>): String {
        remote?.let { return it.execute(args) }
        // Root fallback: execute same argv without shell-string interpolation.
        val su = try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", args.joinToString(" ") { shellQuote(it) }))
        } catch (e: Exception) {
            return "ERROR: Shizuku не подключён, su не запустился: ${e.message}"
        }
        val out = BufferedReader(InputStreamReader(su.inputStream)).readText()
        val err = BufferedReader(InputStreamReader(su.errorStream)).readText()
        val code = su.waitFor()
        return "exit=$code\n$out\n$err"
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun arrangeWindows() {
        val chosen = selectedApps()
        if (chosen.size != 3) {
            status.text = "Выбери приложение для каждой панели."
            return
        }
        if (remote == null && !Shizuku.pingBinder()) {
            status.text = "Сначала подключи Shizuku или предоставь Root. Открой Shizuku, запусти службу и нажми подключение."
            return
        }
        status.text = "Запускаю три приложения и определяю ID задач…"
        thread {
            val log = StringBuilder()
            var success = 0
            chosen.forEachIndexed { index, app ->
                val component = "${app.packageName}/${app.activity}"
                val start = execute(arrayOf("am", "start", "--windowingMode", "5", "--activity-multiple-task", "-n", component))
                log.append("START ${app.label}: $start\n")
                Thread.sleep(1300)
                val taskId = findTaskId(app.packageName)
                if (taskId == null) {
                    log.append("Не найден Task ID для ${app.packageName}\n")
                } else {
                    val b = bounds[index]
                    val resize = execute(arrayOf("am", "task", "resize", taskId.toString(),
                        b[0].toString(), b[1].toString(), b[2].toString(), b[3].toString()))
                    log.append("RESIZE task=$taskId bounds=${b.joinToString()}: $resize\n")
                    if (resize.contains("exit=0")) success++
                }
            }
            runOnUiThread {
                diagnostics.text = log.toString()
                status.text = "Обработка завершена: $success из 3 задач получили команду изменения размера. Проверь визуально."
            }
        }
    }

    private fun findTaskId(packageName: String): Int? {
        val dump = execute(arrayOf("dumpsys", "activity", "activities"))
        val regex = Regex("TaskRecord\\{[^}]*?#(\\d+)[^}]*?\\}")
        val matches = regex.findAll(dump).toList()
        for (i in matches.indices.reversed()) {
            val start = matches[i].range.first
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else dump.length
            val block = dump.substring(start, end)
            if (block.contains(packageName)) return matches[i].groupValues[1].toIntOrNull()
        }
        // Fallback: inspect recents dump; some Android builds print Task id differently.
        val recents = execute(arrayOf("dumpsys", "activity", "recents"))
        val lines = recents.lines()
        var candidate: Int? = null
        for (line in lines) {
            if (line.contains(packageName)) {
                val before = lines.takeWhile { it != line }.takeLast(8).joinToString("\n") + "\n" + line
                candidate = Regex("#(\\d+)").findAll(before).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
            }
        }
        return candidate
    }

    private fun runDiagnostics() {
        status.text = "Собираю диагностику…"
        thread {
            val report = StringBuilder()
            report.append("Android API: ${android.os.Build.VERSION.SDK_INT}\n")
            report.append("Shizuku Binder: ${Shizuku.pingBinder()}\n")
            if (Shizuku.pingBinder()) {
                report.append("Shizuku UID: ${safeShizukuUid()}\n")
                report.append("Shizuku permission: ${Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED}\n")
            }
            val freeform = try { Settings.Global.getInt(contentResolver, "enable_freeform_support", 0) } catch (_: Exception) { -1 }
            val resizable = try { Settings.Global.getInt(contentResolver, "force_resizable_activities", 0) } catch (_: Exception) { -1 }
            report.append("enable_freeform_support=$freeform\nforce_resizable_activities=$resizable\n")
            val id = execute(arrayOf("id"))
            report.append("Backend identity:\n$id\n")
            val activity = execute(arrayOf("am", "supports-multiwindow"))
            report.append("supports-multiwindow:\n$activity\n")
            runOnUiThread {
                diagnostics.text = report.toString()
                status.text = "Диагностика завершена."
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        if (shizukuBound) {
            try {
                val args = Shizuku.UserServiceArgs(ComponentName(this, WindowShellService::class.java))
                    .tag("gs8-triple-shell").version(1).daemon(false)
                Shizuku.unbindUserService(args, serviceConnection, true)
            } catch (_: Exception) {}
        }
    }

    companion object { private const val REQUEST_CODE = 501 }
}
