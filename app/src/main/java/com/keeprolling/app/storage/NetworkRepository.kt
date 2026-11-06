package com.keeprolling.app.storage

import android.content.Context
import com.keeprolling.app.model.JunctionConfig
import com.keeprolling.app.model.RoadNetwork
import com.keeprolling.app.model.SimSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** 存档结构（v2）：路网 + 各开放路口配置 + 全局设置 */
@Serializable
data class SavedNetwork(
    val name: String,
    val network: RoadNetwork,
    val configs: Map<Long, JunctionConfig>,
    val settings: SimSettings,
    val savedAtMillis: Long,
)

/**
 * 路网本地仓库：保存/加载/删除，存在 App 私有目录 files/networks/ 下。
 * 纯本地存储，不需要任何系统权限。
 */
class NetworkRepository(context: Context) {

    companion object {
        /** 自动暂存文件的主名（不带扩展名）：不出现在"加载存档"列表里 */
        const val AUTO_FILE_NAME = "autosave"

        /** 自动暂存的存档展示名 */
        const val AUTO_DISPLAY_NAME = "自动暂存"
    }

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val dir: File = File(context.filesDir, "networks").apply { mkdirs() }

    /** 同一套 JSON 配置也供撤销栈快照使用 */
    val codec: Json get() = json

    private fun fileOf(name: String) = File(dir, "$name.json")

    private val autoFile: File get() = fileOf(AUTO_FILE_NAME)

    // ---------------- 自动暂存（需求 2）----------------

    /**
     * 把当前路网 / 路口配置 / 全局设置写入自动暂存槽位。
     * 应用每次改动后都会调用它，因此**强制关闭再打开也能恢复到上次的状态**。
     */
    fun saveAuto(network: RoadNetwork, configs: Map<Long, JunctionConfig>, settings: SimSettings) {
        val data = SavedNetwork(
            AUTO_DISPLAY_NAME, network, configs.toMap(), settings, System.currentTimeMillis(),
        )
        autoFile.writeText(json.encodeToString(SavedNetwork.serializer(), data))
    }

    /** 读取自动暂存（不存在或损坏时返回 null） */
    fun loadAuto(): SavedNetwork? {
        val f = autoFile
        if (!f.exists()) return null
        return runCatching { json.decodeFromString(SavedNetwork.serializer(), f.readText()) }.getOrNull()
    }

    /** 清空自动暂存（用户主动清空画布时用） */
    fun clearAuto() {
        autoFile.delete()
    }

    fun save(name: String, network: RoadNetwork, configs: Map<Long, JunctionConfig>, settings: SimSettings) {
        val data = SavedNetwork(name, network, configs.toMap(), settings, System.currentTimeMillis())
        fileOf(name).writeText(json.encodeToString(SavedNetwork.serializer(), data))
    }

    /** 列出全部存档（按保存时间倒序）；自动暂存不算存档，不列出来 */
    fun list(): List<SavedNetwork> =
        dir.listFiles { f -> f.extension == "json" && f.nameWithoutExtension != AUTO_FILE_NAME }
            ?.mapNotNull { f ->
                runCatching { json.decodeFromString(SavedNetwork.serializer(), f.readText()) }.getOrNull()
            }
            ?.sortedByDescending { it.savedAtMillis }
            .orEmpty()

    fun delete(name: String) {
        fileOf(name).delete()
    }
}
