package com.flowwrite.ai.database

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * WritingPreferences Entity (Version 3 Schema).
 * Supports both user-created explicit preferences and automatically learned style patterns
 * extracted from repeated user corrections.
 *
 * A single correction starts with `occurrenceCount = 1` and `confidenceScore = 0.35f` (candidate).
 * Once the user repeats the same correction pattern (or `confidenceScore >= 0.65f`), it is
 * automatically promoted into active prompt conditioning.
 */
@Entity(
    tableName = "writing_preferences",
    indices = [Index(value = ["pattern_key"], unique = true)]
)
data class WritingPreferenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "pattern_key") val patternKey: String = "manual_${System.nanoTime()}",
    @ColumnInfo(name = "preference_text") val preferenceText: String,
    @ColumnInfo(name = "context_category") val contextCategory: String, // TONE, PUNCTUATION, LENGTH, VOCABULARY, FORMALITY, SPELLING, MANUAL
    @ColumnInfo(name = "source_type") val sourceType: String = "MANUAL", // MANUAL or AUTO_LEARNED
    @ColumnInfo(name = "occurrence_count") val occurrenceCount: Int = 1,
    @ColumnInfo(name = "confidence_score") val confidenceScore: Float = 1.0f,
    @ColumnInfo(name = "enabled") val enabled: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

/**
 * FeedbackExamples Entity (Version 3 Schema).
 * Stores original transcript, AI-generated output, and user-approved corrected text
 * along with feedback signal strength and extracted diff tags for fast similarity retrieval.
 */
@Entity(tableName = "feedback_examples")
data class FeedbackExampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "original_transcript") val originalTranscript: String,
    @ColumnInfo(name = "generated_output") val generatedOutput: String,
    @ColumnInfo(name = "approved_correction") val approvedCorrection: String?,
    @ColumnInfo(name = "feedback_type") val feedbackType: String, // ACCEPTED, EDITED, REJECTED, REVERTED
    @ColumnInfo(name = "writing_mode") val writingMode: String,   // CLEAN, POLISH
    @ColumnInfo(name = "signal_weight") val signalWeight: Float = 1.0f, // EDITED=1.0, ACCEPTED=0.6, REJECTED/REVERTED=-0.25
    @ColumnInfo(name = "extracted_tags") val extractedTags: String = "",
    @ColumnInfo(name = "timestamp") val timestamp: Long = System.currentTimeMillis()
)

/**
 * PersonalDictionary Entity
 */
@Entity(tableName = "personal_dictionary")
data class PersonalDictionaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "spoken_form") val spokenForm: String,
    @ColumnInfo(name = "preferred_spelling") val preferredSpelling: String,
    @ColumnInfo(name = "category") val category: String,
    @ColumnInfo(name = "enabled") val enabled: Boolean = true
)

@Dao
interface WritingPreferenceDao {
    @Query("SELECT * FROM writing_preferences ORDER BY confidence_score DESC, occurrence_count DESC, updated_at DESC")
    fun observeAll(): Flow<List<WritingPreferenceEntity>>

    @Query("SELECT * FROM writing_preferences ORDER BY confidence_score DESC, occurrence_count DESC, updated_at DESC")
    suspend fun getAllPreferences(): List<WritingPreferenceEntity>

    /**
     * Retrieves only high-confidence enabled preferences (explicit manual rules or
     * auto-learned rules that have accumulated sufficient repeated evidence >= minConfidence).
     */
    @Query("""
        SELECT * FROM writing_preferences
        WHERE enabled = 1 AND confidence_score >= :minConfidence
        ORDER BY confidence_score DESC, occurrence_count DESC, updated_at DESC
        LIMIT :limit
    """)
    suspend fun getActiveConditioningPreferences(
        minConfidence: Float = 0.65f,
        limit: Int = 5
    ): List<WritingPreferenceEntity>

    @Query("SELECT * FROM writing_preferences WHERE pattern_key = :patternKey LIMIT 1")
    suspend fun findByPatternKey(patternKey: String): WritingPreferenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preference: WritingPreferenceEntity): Long

    @Query("UPDATE writing_preferences SET enabled = :enabled, updated_at = :now WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE writing_preferences SET confidence_score = :confidence, updated_at = :now WHERE id = :id")
    suspend fun updateConfidence(id: Long, confidence: Float, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM writing_preferences WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM writing_preferences WHERE source_type = 'AUTO_LEARNED'")
    suspend fun deleteAutoLearnedPreferences()

    @Query("DELETE FROM writing_preferences")
    suspend fun deleteAll()
}

@Dao
interface FeedbackExampleDao {
    @Query("SELECT * FROM feedback_examples ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<FeedbackExampleEntity>>

    /**
     * Fetches candidate pool of positive approved examples (EDITED and ACCEPTED)
     * so `AdaptiveLearningEngine` can score and rank them by token similarity against the current input.
     */
    @Query("""
        SELECT * FROM feedback_examples
        WHERE writing_mode = :mode
          AND feedback_type IN ('EDITED', 'ACCEPTED')
          AND approved_correction IS NOT NULL
          AND signal_weight > 0
        ORDER BY timestamp DESC
        LIMIT :candidatePoolSize
    """)
    suspend fun getCandidateExamplesForMode(
        mode: String,
        candidatePoolSize: Int = 35
    ): List<FeedbackExampleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(example: FeedbackExampleEntity): Long

    @Query("DELETE FROM feedback_examples WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM feedback_examples")
    suspend fun deleteAll()
}

@Dao
interface PersonalDictionaryDao {
    @Query("SELECT * FROM personal_dictionary ORDER BY LENGTH(spoken_form) DESC")
    fun observeAll(): Flow<List<PersonalDictionaryEntity>>

    @Query("SELECT * FROM personal_dictionary WHERE enabled = 1 ORDER BY LENGTH(spoken_form) DESC")
    suspend fun getEnabledEntries(): List<PersonalDictionaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: PersonalDictionaryEntity): Long

    @Query("UPDATE personal_dictionary SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM personal_dictionary WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM personal_dictionary")
    suspend fun deleteAll()
}

@Database(
    entities = [
        WritingPreferenceEntity::class,
        FeedbackExampleEntity::class,
        PersonalDictionaryEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class FlowWriteDatabase : RoomDatabase() {
    abstract fun preferenceDao(): WritingPreferenceDao
    abstract fun feedbackDao(): FeedbackExampleDao
    abstract fun dictionaryDao(): PersonalDictionaryDao

    companion object {
        @Volatile
        private var INSTANCE: FlowWriteDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE personal_dictionary ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        /**
         * Room Migration 2 -> 3 adding automatic preference learning columns and feedback signal weights.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE writing_preferences ADD COLUMN pattern_key TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE writing_preferences ADD COLUMN source_type TEXT NOT NULL DEFAULT 'MANUAL'")
                db.execSQL("ALTER TABLE writing_preferences ADD COLUMN occurrence_count INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE writing_preferences ADD COLUMN confidence_score REAL NOT NULL DEFAULT 1.0")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_writing_preferences_pattern_key ON writing_preferences(pattern_key)")

                db.execSQL("ALTER TABLE feedback_examples ADD COLUMN signal_weight REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE feedback_examples ADD COLUMN extracted_tags TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): FlowWriteDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    FlowWriteDatabase::class.java,
                    "flowwrite_personalization.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
