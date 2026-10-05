package com.sengine.project

import android.content.Context
import com.sengine.engine.core.AssetKind
import org.json.JSONObject

/** Original, offline assets bundled with the editor APK. */
data class BuiltinAsset(
    val id: String,
    val name: String,
    val category: String,
    val path: String,
    val filename: String,
    val description: String,
    val license: String,
    val source: String,
) {
    val kind: AssetKind? get() = AssetKind.of(filename)
}

object BuiltinAssetLibrary {
    private const val MANIFEST = "asset-library/library.json"

    fun list(context: Context): List<BuiltinAsset> {
        val json = context.assets.open(MANIFEST).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val entries = JSONObject(json).getJSONArray("entries")
        return List(entries.length()) { i ->
            val item = entries.getJSONObject(i)
            BuiltinAsset(
                id = item.getString("id"),
                name = item.getString("name"),
                category = item.getString("category"),
                path = item.getString("path"),
                filename = item.getString("filename"),
                description = item.getString("description"),
                license = item.optString("license", "Unknown"),
                source = item.optString("source", "Built-in library"),
            )
        }
    }

    /** Copies an entry to project storage without loading binary data into memory. */
    fun install(context: Context, project: Project, asset: BuiltinAsset): String {
        val name = project.uniqueAssetName(asset.filename)
        context.assets.open("asset-library/${asset.path}").use { project.writeAsset(name, it) }
        return name
    }
}
