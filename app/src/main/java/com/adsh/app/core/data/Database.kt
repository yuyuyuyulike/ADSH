package com.adsh.app.core.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

/**
 * 工作区（dsh 的 Workspace）：手机上一个被「绑定」的文件夹。
 * 会话按工作区分组显示在抽屉里；删除工作区只是解除绑定，文件夹与会话记录都保留
 * （会话转为「未分组」，与 dsh 的 delete.desc 一致）。
 */
@Entity(tableName = "workspaces", indices = [Index(value = ["path"], unique = true)])
data class WorkspaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 手机上的绝对路径 */
    val path: String,
    /** 展示名：默认取末级目录名（dsh 的 workspaceTitleOf），可重命名 */
    val name: String,
    val createdAt: Long,
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** 所属工作区；null = 未分组（dsh 的 group.ungrouped） */
    val workspaceId: Long? = null,
    /** dsh 的 /plan：计划模式（true 时注入 plan mode 提示词段，不落文件改动） */
    val planMode: Boolean = false,
    /** 会话统计（dsh 的 stats.dialog）：随会话一起持久化，切回来还能看到 */
    val statPromptTokens: Long = 0,
    val statCompletionTokens: Long = 0,
    val statCacheHitTokens: Long = 0,
    val statCacheMissTokens: Long = 0,
    val statLlmMillis: Long = 0,
    val statToolMillis: Long = 0,
    val statTtftMillis: Long = 0,
    val statTtftSamples: Long = 0,
)

@Entity(tableName = "messages", indices = [Index("conversationId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    val reasoning: String? = null,
    /** PTC：助手消息携带的 tool_calls（JSON），以及工具结果消息的回指信息 */
    val toolCallsJson: String? = null,
    val toolCallId: String? = null,
    val name: String? = null,
    /** PTC：程序内的子调用轨迹（JSON），供「轨迹」视图还原 思考/代码/工具 三段 */
    val subCallsJson: String? = null,
    /** 工具结果是否是失败（dsh 的 outcome.kind = error，行首换成红色状态点） */
    val isError: Boolean = false,
    /** 这次工具调用花了多久（工具执行耗时；行上不展示，供统计/轨迹使用） */
    val durationMs: Long = 0,
    /**
     * 本轮用量（dsh 的 turn tokenUsage / runMs）：落在这一轮的用户消息行上，
     * 供轮尾的「用量 N tok」「用时 X」两个按钮展开明细（JSON，见 TurnUsage）。
     */
    val usageJson: String? = null,
    /**
     * 这条用户消息带的附件（JSON 数组，见 UserAttachment）。
     * dsh 的 prompt content：消息本身只存引用，装配请求时才翻译成
     * 文件句柄文本 / 图片句柄 + 图片块（图片不进消息正文）。
     */
    val attachmentsJson: String? = null,
    val createdAt: Long,
)

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM workspaces ORDER BY createdAt ASC")
    suspend fun list(): List<WorkspaceEntity>

    @Query("SELECT * FROM workspaces WHERE id = :id")
    suspend fun byId(id: Long): WorkspaceEntity?

    @Query("SELECT * FROM workspaces WHERE path = :path LIMIT 1")
    suspend fun byPath(path: String): WorkspaceEntity?

    @Insert
    suspend fun insert(workspace: WorkspaceEntity): Long

    @Query("UPDATE workspaces SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM workspaces WHERE id = :id")
    suspend fun delete(id: Long)

    /** 删除工作区：它的会话转为「未分组」，不删记录 */
    @Query("UPDATE conversations SET workspaceId = NULL WHERE workspaceId = :id")
    suspend fun orphanConversations(id: Long)

    /** 首次启动时把已有会话收进默认工作区 */
    @Query("UPDATE conversations SET workspaceId = :workspaceId WHERE workspaceId IS NULL")
    suspend fun adoptOrphans(workspaceId: Long)

    @Query("SELECT COUNT(*) FROM workspaces")
    suspend fun count(): Int
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    suspend fun list(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun byId(id: Long): ConversationEntity?

    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: Long, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    @Query("UPDATE conversations SET planMode = :planMode, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setPlanMode(id: Long, planMode: Boolean, updatedAt: Long)

    @Query(
        "UPDATE conversations SET statPromptTokens = :promptTokens, statCompletionTokens = :completionTokens, " +
            "statCacheHitTokens = :cacheHitTokens, statCacheMissTokens = :cacheMissTokens, " +
            "statLlmMillis = :llmMillis, statToolMillis = :toolMillis, statTtftMillis = :ttftMillis, " +
            "statTtftSamples = :ttftSamples, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun setStats(
        id: Long,
        promptTokens: Long,
        completionTokens: Long,
        cacheHitTokens: Long,
        cacheMissTokens: Long,
        llmMillis: Long,
        toolMillis: Long,
        ttftMillis: Long,
        ttftSamples: Long,
        updatedAt: Long,
    )

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
    suspend fun list(conversationId: Long): List<MessageEntity>

    /**
     * 兜底读：把大字段截断后再读。
     *
     * CursorWindow 只有 2MB，只要**一行**超过它，SQLite 就抛
     * SQLiteBlobTooBigException，而 list() 是「一进会话就读」，于是整条会话
     * 变成「一打开就闪退、根本进不去」（实测 dropbox：requiredPos=57 totalRows=58）。
     * 截断读取至少能让界面打开、让用户删掉这条会话或接着聊。
     */
    @Query(
        "SELECT id, conversationId, role, substr(content, 1, 32768) AS content, " +
            "substr(reasoning, 1, 8192) AS reasoning, " +
            "substr(toolCallsJson, 1, 8192) AS toolCallsJson, toolCallId, name, " +
            "substr(subCallsJson, 1, 16384) AS subCallsJson, isError, durationMs, " +
            "substr(usageJson, 1, 8192) AS usageJson, " +
            "substr(attachmentsJson, 1, 8192) AS attachmentsJson, createdAt " +
            "FROM messages WHERE conversationId = :conversationId ORDER BY id ASC",
    )
    suspend fun listCapped(conversationId: Long): List<MessageEntity>

    /**
     * 只数行数，**不碰 content / subCallsJson 这些大字段**。
     *
     * 「轮 / 步」原先靠 list() 读全表再 count，那条超大记录的会话正好在读这里时抛
     * SQLiteBlobTooBigException；COUNT 不读 blob，所以永远不会。
     */
    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND role = :role")
    suspend fun countByRole(conversationId: Long, role: String): Int

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: Long)

    /** 最后一条某种角色的消息（dsh 的 system-prompt 节点：内容没变就不再写一条） */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND role = :role ORDER BY id DESC LIMIT 1")
    suspend fun lastOfRole(conversationId: Long, role: String): MessageEntity?

    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND role = :role AND name = :name " +
            "ORDER BY id DESC LIMIT 1"
    )
    suspend fun lastNamed(conversationId: Long, role: String, name: String): MessageEntity?

    /**
     * 会话搜索（dsh 的 ApiSessionList.search）：在**可见正文**（用户 / 助手消息）里做字面量匹配。
     * 工具结果与系统提示词节点不算「会话内容」，不参与搜索；转义由仓储层处理。
     */
    @Query(
        "SELECT * FROM messages WHERE role IN ('user', 'assistant') " +
            "AND content LIKE '%' || :pattern || '%' ESCAPE '\\' ORDER BY id DESC LIMIT :limit"
    )
    suspend fun searchContent(pattern: String, limit: Int): List<MessageEntity>

    /** 本轮结束时把用量写回这一轮的用户消息行（dsh 的轮尾统计） */
    @Query("UPDATE messages SET usageJson = :usageJson WHERE id = :id")
    suspend fun setUsage(id: Long, usageJson: String?)

    /** 系统提示词就地替换（dsh 的 system/message replace：不再多出一行「系统提示词更新」） */
    @Query("UPDATE messages SET content = :content WHERE id = :id")
    suspend fun setContent(id: Long, content: String)
}

@Dao
interface ConversationDaoExtra {
    /**
     * 某个分组里一条消息都没有的会话（dsh 的 session summary.blank）：
     * 「新会话」按钮命中它就复用，不再反复建空会话。
     */
    @Query(
        "SELECT * FROM conversations WHERE workspaceId IS :workspaceId " +
            "AND id NOT IN (SELECT DISTINCT conversationId FROM messages) ORDER BY id DESC LIMIT 1"
    )
    suspend fun blank(workspaceId: Long?): ConversationEntity?

    /** 分组里所有空白会话（清理占位用；keep 是本次要保留的那条） */
    @Query(
        "SELECT * FROM conversations WHERE workspaceId IS :workspaceId AND id != :keep " +
            "AND id NOT IN (SELECT DISTINCT conversationId FROM messages)"
    )
    suspend fun blanksExcept(workspaceId: Long?, keep: Long): List<ConversationEntity>

    /**
     * 全部「一条消息都没有」的会话 id（dsh 的 session summary.blank）。
     * 侧栏据此隐藏空白会话 —— 除了正好是当前会话的那一条（见 dsh 的 sessionVisible）。
     */
    @Query("SELECT id FROM conversations WHERE id NOT IN (SELECT DISTINCT conversationId FROM messages)")
    suspend fun blankIds(): List<Long>
}

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, WorkspaceEntity::class],
    version = 12,
    exportSchema = true,
)
abstract class AdshDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun conversationsExtra(): ConversationDaoExtra
    abstract fun messages(): MessageDao
    abstract fun workspaces(): WorkspaceDao

    companion object {
        /** v2 → v3：只加一列，历史会话不需要被清空 */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN subCallsJson TEXT")
            }
        }

        /** v3 → v4：会话上新增 计划模式 列 */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN planMode INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v4 → v5：会话统计（模型/工具用时、TTFT、token 用量）随会话持久化 */
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                listOf(
                    "statPromptTokens", "statCompletionTokens", "statCacheHitTokens", "statCacheMissTokens",
                    "statLlmMillis", "statToolMillis", "statTtftMillis", "statTtftSamples",
                ).forEach { column ->
                    db.execSQL("ALTER TABLE conversations ADD COLUMN " + column + " INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        /** v5 → v6：工作区表 + 会话归属（dsh 侧栏按工作区分组） */
        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS workspaces (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "path TEXT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_workspaces_path ON workspaces (path)")
                db.execSQL("ALTER TABLE conversations ADD COLUMN workspaceId INTEGER")
            }
        }

        /** v6 → v7：工具行的失败标记与用时（dsh 工具行的状态点与「用时」） */
        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN isError INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v7 → v8：本轮用量（轮尾「用量 / 用时」按钮） */
        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN usageJson TEXT")
            }
        }

        /** v8 → v9：用户消息的附件引用（dsh 的 prompt content：文件句柄 / 图片块） */
        private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN attachmentsJson TEXT")
            }
        }

        /** v9 → v10：目标的暂停状态（dsh 的 GoalBar 暂停/恢复按钮） */
        private val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN goalPaused INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v10 → v11：目标域（阶段 / 轮数 / 上限 / 受阻原因 / 版本号），旧的 goalPaused 折进 goalPhase */
        private val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN goalPhase TEXT")
                db.execSQL("ALTER TABLE conversations ADD COLUMN goalRounds INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN goalMaxRounds INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN goalBlockedReason TEXT")
                db.execSQL("ALTER TABLE conversations ADD COLUMN goalRevision INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE conversations SET goalPhase = CASE WHEN goalPaused = 1 THEN 'paused' ELSE 'active' END, " +
                        "goalMaxRounds = 256, goalRevision = 1 WHERE goalObjective IS NOT NULL AND goalObjective != ''"
                )
            }
        }

        /**
         * v11 → v12：目标功能整个删掉了，把 conversations 上的目标列摘干净。
         * SQLite 的老版本不支持 DROP COLUMN，所以重建表再搬数据。
         */
        private val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                val stats = listOf(
                    "statPromptTokens", "statCompletionTokens", "statCacheHitTokens", "statCacheMissTokens",
                    "statLlmMillis", "statToolMillis", "statTtftMillis", "statTtftSamples",
                )
                val columns = listOf("id", "title", "createdAt", "updatedAt", "workspaceId", "planMode") + stats
                val columnList = columns.joinToString(", ")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS conversations_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "title TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
                        "workspaceId INTEGER, planMode INTEGER NOT NULL DEFAULT 0, " +
                        stats.joinToString(", ") { it + " INTEGER NOT NULL DEFAULT 0" } +
                        ")"
                )
                db.execSQL("INSERT INTO conversations_new (" + columnList + ") SELECT " + columnList + " FROM conversations")
                db.execSQL("DROP TABLE conversations")
                db.execSQL("ALTER TABLE conversations_new RENAME TO conversations")
            }
        }

        @Volatile private var instance: AdshDatabase? = null

        fun get(context: Context): AdshDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AdshDatabase::class.java, "adsh.db")
                .addMigrations(
                    MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
                )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { instance = it }
        }
    }
}

/** 会话仓储：把 Room 细节挡在 agent/UI 之外 */
class ConversationRepository(private val db: AdshDatabase) {

    companion object {
        /** /compact 落下的检查点消息标记（仅 UI 用，不发给模型） */
        const val COMPACT_MARKER = "compact"
    }

    suspend fun conversations(): List<ConversationEntity> = db.conversations().list()

    suspend fun createConversation(title: String = "新会话", workspaceId: Long? = null): Long {
        val now = System.currentTimeMillis()
        return db.conversations().insert(
            ConversationEntity(title = title, createdAt = now, updatedAt = now, workspaceId = workspaceId),
        )
    }

    /** dsh 的 fork：把一条会话的历史整份复制成新会话（分叉点 = 当前历史末尾） */
    suspend fun forkConversation(id: Long): Long? {
        val source = db.conversations().byId(id) ?: return null
        val now = System.currentTimeMillis()
        val copyId = db.conversations().insert(
            ConversationEntity(
                title = source.title + " (分叉)",
                createdAt = now,
                updatedAt = now,
                workspaceId = source.workspaceId,
                planMode = source.planMode,
            )
        )
        readMessages(id).forEach { message ->
            db.messages().insert(message.copy(id = 0, conversationId = copyId))
        }
        return copyId
    }

    // ---------------------------------------------------------------- 会话搜索

    /**
     * 会话搜索的一条命中（dsh 的 SessionSearchValue 里的一条）：
     * 会话 + 所属工作区 + 命中处的片段。
     */
    data class SessionSearchHit(
        val conversationId: Long,
        val title: String,
        val workspace: String?,
        val snippet: String,
    )

    /** 搜索结果：命中列表 + 是否被截断（dsh 的 search.hasMore：仅显示前 N 条） */
    data class SessionSearchOutcome(
        val hits: List<SessionSearchHit> = emptyList(),
        val hasMore: Boolean = false,
    )

    /**
     * 搜索会话历史（dsh 的 ApiSessionList.search：在可见消息正文里做字面量匹配）。
     *
     * 一条会话只出一条结果（取最新命中的那条消息做片段），按命中消息的新旧排序；
     * 结果超过 [limit] 条时截断并给出 hasMore。
     */
    suspend fun searchSessions(query: String, limit: Int = 20): SessionSearchOutcome {
        val needle = query.trim()
        if (needle.isEmpty()) return SessionSearchOutcome()
        // LIKE 的通配符要转义，否则用户输入的 % 会变成「匹配任意内容」
        val pattern = needle
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        val rows = db.messages().searchContent(pattern, limit * 20)
        if (rows.isEmpty()) return SessionSearchOutcome()
        val conversations = db.conversations().list().associateBy { it.id }
        val workspaces = db.workspaces().list().associateBy { it.id }
        val hits = ArrayList<SessionSearchHit>(limit + 1)
        val seen = HashSet<Long>()
        for (row in rows) {
            if (!seen.add(row.conversationId)) continue
            val conversation = conversations[row.conversationId] ?: continue
            hits += SessionSearchHit(
                conversationId = conversation.id,
                title = conversation.title,
                workspace = conversation.workspaceId?.let { workspaces[it]?.name },
                snippet = snippetAround(row.content, needle),
            )
        }
        return SessionSearchOutcome(
            hits = hits.take(limit),
            hasMore = hits.size > limit,
        )
    }

    /** 命中处前后各取一段，片段里的换行与连续空白压成单空格（结果行只有一行文字） */
    private fun snippetAround(content: String, needle: String, radius: Int = 48): String {
        val flat = content.replace(Regex("\\s+"), " ").trim()
        val at = flat.indexOf(needle, ignoreCase = true)
        if (at < 0) return flat.take(radius * 2)
        val start = (at - radius).coerceAtLeast(0)
        val end = (at + needle.length + radius).coerceAtMost(flat.length)
        return (if (start > 0) "…" else "") + flat.substring(start, end) + (if (end < flat.length) "…" else "")
    }

    // ---------------------------------------------------------------- 工作区

    suspend fun workspaces(): List<WorkspaceEntity> = db.workspaces().list()

    suspend fun workspace(id: Long): WorkspaceEntity? = db.workspaces().byId(id)

    /** 绑定文件夹为工作区（同一个路径不会重复建） */
    suspend fun addWorkspace(path: String, name: String): Long {
        db.workspaces().byPath(path)?.let { return it.id }
        return db.workspaces().insert(
            WorkspaceEntity(path = path, name = name, createdAt = System.currentTimeMillis()),
        )
    }

    /** dsh 的「重命名工作区」 */
    suspend fun renameWorkspace(id: Long, name: String) = db.workspaces().rename(id, name)

    /** dsh 的「删除工作区」：只解绑，会话转未分组 */
    suspend fun deleteWorkspace(id: Long) {
        db.workspaces().orphanConversations(id)
        db.workspaces().delete(id)
    }

    /** 首次启动：把「设置里已绑定」的那个工作区带进工作区列表，并把无归属的会话收进去 */
    suspend fun ensureDefaultWorkspace(path: String): Long {
        val existing = db.workspaces().byPath(path)
        if (existing != null) return existing.id
        val id = db.workspaces().insert(
            WorkspaceEntity(path = path, name = File(path).name.ifBlank { path }, createdAt = System.currentTimeMillis()),
        )
        if (db.workspaces().count() == 1) db.workspaces().adoptOrphans(id)
        return id
    }


    suspend fun rename(id: Long, title: String) {
        db.conversations().rename(id, title, System.currentTimeMillis())
    }

    suspend fun byId(id: Long): ConversationEntity? = db.conversations().byId(id)


    /** dsh 的 /plan：进入或退出计划模式 */
    suspend fun setPlanMode(id: Long, planMode: Boolean) {
        db.conversations().setPlanMode(id, planMode, System.currentTimeMillis())
    }

    /** 会话统计落库（dsh 的 stats.dialog 数据随会话保存） */
    suspend fun setStats(id: Long, stats: com.adsh.app.core.llm.SessionStats) {
        db.conversations().setStats(
            id = id,
            promptTokens = stats.promptTokens,
            completionTokens = stats.completionTokens,
            cacheHitTokens = stats.cacheHitTokens,
            cacheMissTokens = stats.cacheMissTokens,
            llmMillis = stats.llmMillis,
            toolMillis = stats.toolMillis,
            ttftMillis = stats.ttftMillis,
            ttftSamples = stats.ttftSamples.toLong(),
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** 读出持久化的会话统计（turns/steps 由消息推导，不入库） */
    suspend fun statsOf(id: Long): com.adsh.app.core.llm.SessionStats {
        val row = db.conversations().byId(id) ?: return com.adsh.app.core.llm.SessionStats()
        return com.adsh.app.core.llm.SessionStats(
            // COUNT 而不是「读全表再 count」：openConversation 每次开会话都会调这里，
            // 读全表等于把已经修好的闪退路径又打开一次（超大行 → CursorWindow 2MB）。
            turns = db.messages().countByRole(id, "user"),
            steps = db.messages().countByRole(id, "tool"),
            promptTokens = row.statPromptTokens,
            completionTokens = row.statCompletionTokens,
            cacheHitTokens = row.statCacheHitTokens,
            cacheMissTokens = row.statCacheMissTokens,
            llmMillis = row.statLlmMillis,
            toolMillis = row.statToolMillis,
            ttftMillis = row.statTtftMillis,
            ttftSamples = row.statTtftSamples.toInt(),
        )
    }

    suspend fun delete(id: Long) {
        db.messages().deleteForConversation(id)
        db.conversations().delete(id)
    }

    /**
     * 按会话整份读消息 —— **所有**这类路径都必须走这里。
     *
     * 超大行会让整条会话读不出来（CursorWindow 2MB）——以前是直接闪退、进不去。
     * 兜底成截断读取：界面能打开，用户能删会话 / 继续聊，而不是被困在闪退里。
     * 绕过它（直接 db.messages().list()）等于把修好的闪退路径重新打开一次。
     */
    private suspend fun readMessages(conversationId: Long): List<MessageEntity> = try {
        db.messages().list(conversationId)
    } catch (t: Throwable) {
        android.util.Log.w("ADSH_DB", "messages() failed, falling back to capped read", t)
        runCatching { db.messages().listCapped(conversationId) }.getOrDefault(emptyList())
    }

    suspend fun messages(conversationId: Long): List<MessageEntity> = readMessages(conversationId)

    /** 最后一条某种角色的消息；name 为 null 时按角色取（dsh 的会话节点去重） */
    suspend fun lastNamed(conversationId: Long, role: String, name: String? = null): MessageEntity? =
        if (name == null) db.messages().lastOfRole(conversationId, role)
        else db.messages().lastNamed(conversationId, role, name)

    /**
     * dsh 的 /compact：把会话历史替换成「一条检查点 + 保留的最近若干条」。
     * id 会重新分配，相对顺序不变；检查点用 name = COMPACT_MARKER 标记，UI 可单独折叠展示。
     */
    suspend fun replaceHistory(
        conversationId: Long,
        prefix: List<MessageEntity>,
        keep: List<MessageEntity>,
        checkpoint: String,
    ) {
        db.messages().deleteForConversation(conversationId)
        // 顺序必须与 dsh 的会话节点一致：系统提示词 / 上下文注入，然后是检查点，再是保留的尾巴
        prefix.forEach { message ->
            db.messages().insert(message.copy(id = 0, conversationId = conversationId))
        }
        db.messages().insert(
            MessageEntity(
                conversationId = conversationId,
                role = "user",
                content = checkpoint,
                name = COMPACT_MARKER,
                createdAt = System.currentTimeMillis(),
            )
        )
        keep.forEach { message ->
            db.messages().insert(message.copy(id = 0, conversationId = conversationId))
        }
    }

    /** 所有空白会话的 id 集合（dsh 的 sessionVisible：空白会话只在它是当前会话时露出来） */
    suspend fun blankIds(): Set<Long> = db.conversationsExtra().blankIds().toSet()

    /**
     * dsh 的 connectWorkspace：同一个工作区里已经有空白会话就复用，不再新建一条。
     * 没有就现建一条；随后把该分组里多余的空白会话清理掉（只留 keep）。
     */
    suspend fun openOrCreateBlank(workspaceId: Long?): Long {
        val existing = db.conversationsExtra().blank(workspaceId)
        val keep = existing?.id ?: db.conversations().insert(
            ConversationEntity(
                title = "新会话",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                workspaceId = workspaceId,
            )
        )
        db.conversationsExtra().blanksExcept(workspaceId, keep).forEach { db.conversations().delete(it.id) }
        return keep
    }

    /** 把本轮的用量写回用户消息行（dsh 的 turn tokenUsage） */
    suspend fun setUsage(messageId: Long, usageJson: String?) = db.messages().setUsage(messageId, usageJson)

    /** 系统提示词就地替换：内容变了也只更新原来那一行（对齐 dsh 的 system/message replace） */
    suspend fun setMessageContent(messageId: Long, content: String) = db.messages().setContent(messageId, content)

    /**
     * dsh 的「在新对话中分支」：以某条消息为界，把截止到它的历史复制成一条新会话。
     * 分支出来的会话与当前会话并列（同一工作区），原会话不动。
     */
    suspend fun forkAt(conversationId: Long, uptoMessageId: Long): Long? {
        val source = db.conversations().byId(conversationId) ?: return null
        val history = readMessages(conversationId)
        val cut = history.indexOfLast { it.id == uptoMessageId }
        if (cut < 0) return null
        val now = System.currentTimeMillis()
        val id = db.conversations().insert(
            ConversationEntity(
                title = source.title.take(20) + " (分支)",
                createdAt = now,
                updatedAt = now,
                workspaceId = source.workspaceId,
                planMode = source.planMode,
            )
        )
        history.take(cut + 1).forEach { message ->
            db.messages().insert(message.copy(id = 0, conversationId = id))
        }
        return id
    }

    suspend fun addMessage(
        conversationId: Long,
        role: String,
        content: String,
        reasoning: String? = null,
        toolCallsJson: String? = null,
        toolCallId: String? = null,
        name: String? = null,
        subCallsJson: String? = null,
        isError: Boolean = false,
        durationMs: Long = 0,
        attachmentsJson: String? = null,
    ): Long {
        val id = db.messages().insert(
            MessageEntity(
                conversationId = conversationId,
                role = role,
                content = content,
                reasoning = reasoning,
                toolCallsJson = toolCallsJson,
                toolCallId = toolCallId,
                name = name,
                subCallsJson = subCallsJson,
                isError = isError,
                durationMs = durationMs,
                attachmentsJson = attachmentsJson,
                createdAt = System.currentTimeMillis(),
            )
        )
        db.conversations().rename(conversationId, titleFor(conversationId, content, role), System.currentTimeMillis())
        return id
    }

    private suspend fun titleFor(conversationId: Long, content: String, role: String): String {
        val existing = db.conversations().byId(conversationId)
        if (existing != null && existing.title != "新会话") return existing.title
        return if (role == "user") content.lineSequence().firstOrNull()?.take(24)?.ifBlank { "新会话" } ?: "新会话"
        else (existing?.title ?: "新会话")
    }
}
