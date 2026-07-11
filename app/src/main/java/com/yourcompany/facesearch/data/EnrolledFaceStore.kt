package com.yourcompany.facesearch.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/**
 * Persists enrolled face embeddings to a JSON file in the app's private
 * storage (/data/data/<package>/files/enrolled_faces.json). Nothing here
 * ever leaves the device -- no network calls, no cloud lookup.
 */
object EnrolledFaceStore {

    private const val FILE_NAME = "enrolled_faces.json"
    private val gson = Gson()

    @Volatile
    private var cache: MutableList<EnrolledFace>? = null

    @Synchronized
    fun getAll(context: Context): List<EnrolledFace> {
        cache?.let { return it.toList() }

        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) {
            cache = mutableListOf()
            return emptyList()
        }

        return try {
            val json = file.readText()
            val type = object : TypeToken<List<EnrolledFace>>() {}.type
            val loaded: List<EnrolledFace> = gson.fromJson(json, type) ?: emptyList()
            cache = loaded.toMutableList()
            loaded
        } catch (e: Exception) {
            cache = mutableListOf()
            emptyList()
        }
    }

    @Synchronized
    fun addFace(context: Context, name: String, embedding: FloatArray): EnrolledFace {
        val faces = getAll(context).toMutableList()
        val newFace = EnrolledFace(id = UUID.randomUUID().toString(), name = name, embedding = embedding)
        faces.add(newFace)
        cache = faces
        persist(context, faces)
        return newFace
    }

    @Synchronized
    fun removeFace(context: Context, id: String) {
        val faces = getAll(context).toMutableList()
        faces.removeAll { it.id == id }
        cache = faces
        persist(context, faces)
    }

    private fun persist(context: Context, faces: List<EnrolledFace>) {
        File(context.filesDir, FILE_NAME).writeText(gson.toJson(faces))
    }
}