package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.entity.BlockedHashEntity
import com.example.data.entity.DownloadEntity
import com.example.data.entity.SharedFileEntity
import com.example.data.entity.StationCategoryEntity
import com.example.data.entity.TrackMetadataEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        DownloadEntity::class,
        SharedFileEntity::class,
        BlockedHashEntity::class,
        TrackMetadataEntity::class,
        StationCategoryEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AresDatabase : RoomDatabase() {

    abstract fun downloadDao(): DownloadDao
    abstract fun sharedFileDao(): SharedFileDao
    abstract fun blockedHashDao(): BlockedHashDao
    abstract fun trackMetadataDao(): TrackMetadataDao
    abstract fun stationCategoryDao(): StationCategoryDao

    companion object {
        @Volatile
        private var INSTANCE: AresDatabase? = null

        fun getInstance(context: Context): AresDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AresDatabase::class.java,
                    "aresnet_p2p.db"
                )
                .fallbackToDestructiveMigration()
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        CoroutineScope(Dispatchers.IO).launch {
                            // Prepopulate default moderation blocklist for Google Play & AdMob policy compliance
                            val preloadedBlocks = listOf(
                                BlockedHashEntity(
                                    sha256Hex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                                    fileNameHint = "empty_payload_malformed.bin",
                                    reason = "POLICY_VIOLATION"
                                ),
                                BlockedHashEntity(
                                    sha256Hex = "5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8",
                                    fileNameHint = "unauthorized_pirated_installer.exe",
                                    reason = "COPYRIGHT_DMCA"
                                ),
                                BlockedHashEntity(
                                    sha256Hex = "4b227777d4dd1fc61c6f884f48641d02b4d121d3fd328cb08b5531fcacdabf8a",
                                    fileNameHint = "malicious_keygen_patcher.apk",
                                    reason = "MALWARE"
                                )
                            )
                            getInstance(context).blockedHashDao().insertAll(preloadedBlocks)

                            // Seed default station categories
                            val categoryDao = getInstance(context).stationCategoryDao()
                            if (categoryDao.count() == 0) {
                                val defaultCategories = listOf(
                                    StationCategoryEntity(
                                        id = "music", name = "Música", subtitle = "Canciones y playlists",
                                        iconEmoji = "🎵", colorStart = 0xFF00E5FF, colorEnd = 0xFF2563EB,
                                        orderIndex = 0, isSystem = true, allowedExtensions = "mp3,m4a,flac,ogg,aac,wav,opus"
                                    ),
                                    StationCategoryEntity(
                                        id = "audiobooks", name = "Audiolibros", subtitle = "Relatos, cursos y más",
                                        iconEmoji = "📚", colorStart = 0xFF10B981, colorEnd = 0xFF059669,
                                        orderIndex = 1, isSystem = true, allowedExtensions = "mp3,m4a,ogg,mp4,m4b"
                                    ),
                                    StationCategoryEntity(
                                        id = "videos", name = "Videos", subtitle = "Clips y contenido audiovisual",
                                        iconEmoji = "🎬", colorStart = 0xFFEC4899, colorEnd = 0xFF8B5CF6,
                                        orderIndex = 2, isSystem = true, allowedExtensions = "mp4,mkv,webm,avi,mov,3gp"
                                    ),
                                    StationCategoryEntity(
                                        id = "documents", name = "Documentos", subtitle = "PDF, TXT y otros documentos",
                                        iconEmoji = "📄", colorStart = 0xFFF59E0B, colorEnd = 0xFFD97706,
                                        orderIndex = 3, isSystem = true, allowedExtensions = "pdf,txt,epub"
                                    ),
                                    StationCategoryEntity(
                                        id = "images", name = "Imágenes", subtitle = "Fondos y contenido gráfico",
                                        iconEmoji = "🖼️", colorStart = 0xFF14B8A6, colorEnd = 0xFF0D9488,
                                        orderIndex = 4, isSystem = true, allowedExtensions = "jpg,jpeg,png,webp,gif"
                                    ),
                                    StationCategoryEntity(
                                        id = "other", name = "Otros", subtitle = "Archivos diversos",
                                        iconEmoji = "📦", colorStart = 0xFF6366F1, colorEnd = 0xFF4338CA,
                                        orderIndex = 5, isSystem = true, allowedExtensions = "*"
                                    )
                                )
                                categoryDao.insertAll(defaultCategories)
                            }
                        }
                    }
                }).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
