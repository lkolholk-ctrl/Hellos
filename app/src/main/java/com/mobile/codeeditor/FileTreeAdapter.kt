package com.mobile.codeeditor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.RecyclerView

/**
 * Renders a [DocumentFile] tree as a flat, indented list with collapsible
 * directories — the VS Code "Explorer" experience adapted for a narrow drawer.
 */
class FileTreeAdapter(
    private val onFileClick: (DocumentFile) -> Unit
) : RecyclerView.Adapter<FileTreeAdapter.ViewHolder>() {

    private data class Row(val doc: DocumentFile, val depth: Int)

    private var root: DocumentFile? = null
    private val expanded = HashSet<String>()
    private val rows = ArrayList<Row>()

    fun setRoot(doc: DocumentFile?) {
        root = doc
        expanded.clear()
        rebuild()
    }

    fun refresh() = rebuild()

    private fun rebuild() {
        rows.clear()
        root?.let { addChildren(it, 0) }
        notifyDataSetChanged()
    }

    private fun addChildren(dir: DocumentFile, depth: Int) {
        val children = dir.listFiles().sortedWith(
            compareBy({ !it.isDirectory }, { it.name?.lowercase() ?: "" })
        )
        for (child in children) {
            rows.add(Row(child, depth))
            if (child.isDirectory && expanded.contains(child.uri.toString())) {
                addChildren(child, depth + 1)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = rows[position]
        val doc = row.doc
        val isDir = doc.isDirectory
        val isExpanded = expanded.contains(doc.uri.toString())

        holder.name.text = doc.name ?: "?"

        // Indentation per depth level.
        val indent = (12 + row.depth * 16).dp(holder.itemView)
        holder.itemView.setPaddingRelative(
            indent,
            holder.itemView.paddingTop,
            holder.itemView.paddingEnd,
            holder.itemView.paddingBottom
        )

        if (isDir) {
            holder.expand.visibility = View.VISIBLE
            holder.expand.rotation = if (isExpanded) 90f else 0f
            holder.icon.setImageResource(
                if (isExpanded) R.drawable.ic_folder_open else R.drawable.ic_folder
            )
        } else {
            holder.expand.visibility = View.INVISIBLE
            holder.icon.setImageResource(R.drawable.ic_file)
        }

        holder.itemView.setOnClickListener {
            if (isDir) {
                val key = doc.uri.toString()
                if (expanded.contains(key)) expanded.remove(key) else expanded.add(key)
                rebuild()
            } else {
                onFileClick(doc)
            }
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val expand: ImageView = view.findViewById(R.id.expandIcon)
        val icon: ImageView = view.findViewById(R.id.fileIcon)
        val name: TextView = view.findViewById(R.id.fileName)
    }

    private fun Int.dp(view: View): Int =
        (this * view.resources.displayMetrics.density).toInt()
}
