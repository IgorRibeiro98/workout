package com.example.domain.engine

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.data.local.WorkoutDao
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter

class ExportEngine(private val dao: WorkoutDao, private val context: Context) {
    
    suspend fun exportData() = withContext(Dispatchers.IO) {
        val allSessions = dao.getAllCompletedSessionsWithDetails()
        
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(List::class.java)
        
        val json = adapter.toJson(allSessions)
        
        val file = File(context.cacheDir, "workout_export.json")
        val writer = FileWriter(file)
        writer.write(json)
        writer.close()
        
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        val chooser = Intent.createChooser(intent, "Exportar Dados")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
