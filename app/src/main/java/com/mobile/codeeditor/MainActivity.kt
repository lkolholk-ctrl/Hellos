package com.mobile.codeeditor

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.mobile.codeeditor.databinding.ActivityMainBinding
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.langs.java.JavaLanguage
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var fileAdapter: FileTreeAdapter

    private val tabs = mutableListOf<OpenFile>()
    private var currentTab = -1

    // Guards so programmatic changes don't trigger user-facing side effects.
    private var loadingContent = false
    private var suppressTabEvents = false

    private var wordWrapEnabled = false
    private var pendingSaveTab = -1

    // Extensions that benefit from the bundled (C-family) highlighter.
    private val codeExtensions = setOf(
        "java", "kt", "kts", "js", "jsx", "ts", "tsx", "json", "c", "cc", "cpp",
        "h", "hpp", "cs", "go", "rs", "php", "swift", "gradle", "scala", "dart",
        "groovy", "css", "scss"
    )

    // ----- Activity result launchers -----

    private val openFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            openFileInTab(uri)
        }

    private val openFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@registerForActivityResult
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val doc = DocumentFile.fromTreeUri(this, uri)
            fileAdapter.setRoot(doc)
            binding.folderName.text = doc?.name ?: getString(R.string.explorer)
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

    private val createFileLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri ?: return@registerForActivityResult
            val index = pendingSaveTab
            pendingSaveTab = -1
            if (index !in tabs.indices) return@registerForActivityResult
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            tabs[index].uri = uri
            tabs[index].name = queryName(uri)
            writeTab(index)
        }

    // ----- Lifecycle -----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupEditor()
        setupExplorer()
        setupTabs()
        setupWelcome()

        // Handle "open with" from another app.
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { openFileInTab(it) }
        }

        updateUiState()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationIcon(R.drawable.ic_menu)
        binding.toolbar.setNavigationOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    private fun setupEditor() {
        binding.editor.apply {
            colorScheme = SchemeDarcula()
            setTextSize(14f)
            typefaceText = Typeface.MONOSPACE
            isWordwrap = wordWrapEnabled
            subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                if (!loadingContent && currentTab in tabs.indices) {
                    if (!tabs[currentTab].modified) {
                        tabs[currentTab].modified = true
                        refreshTabTitles()
                    }
                }
            }
        }

        binding.symbolInput.bindEditor(binding.editor)
        binding.symbolInput.addSymbols(
            arrayOf("→", "{", "}", "(", ")", "[", "]", ";", "\"", "'", "=", ":", ".", ",", "<", ">", "/", "|", "&", "!", "?", "+", "-", "*"),
            arrayOf("\t", "{", "}", "(", ")", "[", "]", ";", "\"", "'", "=", ":", ".", ",", "<", ">", "/", "|", "&", "!", "?", "+", "-", "*")
        )
    }

    private fun setupExplorer() {
        fileAdapter = FileTreeAdapter { doc -> openDocumentFile(doc) }
        binding.fileList.layoutManager = LinearLayoutManager(this)
        binding.fileList.adapter = fileAdapter

        binding.btnDrawerOpenFolder.setOnClickListener { openFolderLauncher.launch(null) }
        binding.btnDrawerRefresh.setOnClickListener { fileAdapter.refresh() }
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (!suppressTabEvents) selectTab(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun setupWelcome() {
        binding.welcomeOpenFolder.setOnClickListener { openFolderLauncher.launch(null) }
        binding.welcomeNewFile.setOnClickListener { newFile() }
    }

    // ----- Menu -----

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_word_wrap)?.isChecked = wordWrapEnabled
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_save -> { saveCurrent(); true }
            R.id.action_open_file -> { openFileLauncher.launch(arrayOf("*/*")); true }
            R.id.action_open_folder -> { openFolderLauncher.launch(null); true }
            R.id.action_new_file -> { newFile(); true }
            R.id.action_save_as -> { saveAs(); true }
            R.id.action_word_wrap -> { toggleWordWrap(item); true }
            R.id.action_about -> { showAbout(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun toggleWordWrap(item: MenuItem) {
        wordWrapEnabled = !wordWrapEnabled
        item.isChecked = wordWrapEnabled
        binding.editor.isWordwrap = wordWrapEnabled
    }

    // ----- Tab / file logic -----

    private fun openDocumentFile(doc: DocumentFile) {
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        openFileInTab(doc.uri)
    }

    private fun openFileInTab(uri: Uri) {
        val existing = tabs.indexOfFirst { it.uri == uri }
        if (existing >= 0) {
            selectTab(existing)
            updateUiState()
            return
        }
        val content = try {
            readContent(uri)
        } catch (e: Exception) {
            toast(getString(R.string.open_failed, e.message ?: ""))
            return
        }
        tabs.add(OpenFile(queryName(uri), uri, content))
        refreshTabs()
        selectTab(tabs.size - 1)
        updateUiState()
    }

    private fun newFile() {
        tabs.add(OpenFile(getString(R.string.untitled), null, ""))
        refreshTabs()
        selectTab(tabs.size - 1)
        updateUiState()
    }

    private fun selectTab(index: Int) {
        if (index !in tabs.indices) {
            currentTab = -1
            loadingContent = true
            binding.editor.setText("")
            loadingContent = false
            updateUiState()
            return
        }
        // Persist the text currently in the editor back to its tab.
        if (currentTab in tabs.indices && currentTab != index) {
            tabs[currentTab].content = binding.editor.text.toString()
        }
        currentTab = index
        val file = tabs[index]

        loadingContent = true
        binding.editor.setText(file.content)
        loadingContent = false
        applyLanguage(file.name)

        suppressTabEvents = true
        binding.tabLayout.getTabAt(index)?.select()
        suppressTabEvents = false

        updateTitle()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val file = tabs[index]
        if (index == currentTab) {
            file.content = binding.editor.text.toString()
        }
        if (file.modified) {
            AlertDialog.Builder(this)
                .setTitle(R.string.unsaved_title)
                .setMessage(getString(R.string.unsaved_message, file.name))
                .setPositiveButton(R.string.save) { _, _ ->
                    if (currentTab != index) selectTab(index)
                    saveCurrent()
                    performClose(index)
                }
                .setNegativeButton(R.string.discard) { _, _ -> performClose(index) }
                .setNeutralButton(R.string.cancel, null)
                .show()
        } else {
            performClose(index)
        }
    }

    private fun performClose(index: Int) {
        if (index !in tabs.indices) return
        tabs.removeAt(index)
        currentTab = -1
        refreshTabs()
        val next = (index - 1).coerceAtLeast(0)
        if (tabs.isNotEmpty()) selectTab(next) else selectTab(-1)
        updateUiState()
    }

    private fun saveCurrent() {
        val index = currentTab
        if (index !in tabs.indices) return
        tabs[index].content = binding.editor.text.toString()
        if (tabs[index].uri == null) {
            pendingSaveTab = index
            createFileLauncher.launch(tabs[index].name)
        } else {
            writeTab(index)
        }
    }

    private fun saveAs() {
        val index = currentTab
        if (index !in tabs.indices) return
        tabs[index].content = binding.editor.text.toString()
        pendingSaveTab = index
        createFileLauncher.launch(tabs[index].name)
    }

    private fun writeTab(index: Int) {
        val file = tabs[index]
        val uri = file.uri ?: return
        try {
            writeContent(uri, file.content)
            file.modified = false
            refreshTabs()
            updateTitle()
            toast(getString(R.string.saved))
        } catch (e: Exception) {
            toast(getString(R.string.save_failed, e.message ?: ""))
        }
    }

    private fun applyLanguage(name: String) {
        val ext = name.substringAfterLast('.', "").lowercase()
        binding.editor.setEditorLanguage(
            if (ext in codeExtensions) JavaLanguage() else EmptyLanguage()
        )
    }

    // ----- Tab views -----

    private fun refreshTabs() {
        suppressTabEvents = true
        binding.tabLayout.removeAllTabs()
        for (i in tabs.indices) {
            val tab = binding.tabLayout.newTab()
            val view = LayoutInflater.from(this)
                .inflate(R.layout.tab_item, binding.tabLayout, false)
            tab.customView = view
            bindTabView(view, i)
            binding.tabLayout.addTab(tab, false)
        }
        suppressTabEvents = false
        if (currentTab in tabs.indices) {
            suppressTabEvents = true
            binding.tabLayout.getTabAt(currentTab)?.select()
            suppressTabEvents = false
        }
    }

    private fun refreshTabTitles() {
        for (i in tabs.indices) {
            binding.tabLayout.getTabAt(i)?.customView?.let { bindTabView(it, i) }
        }
    }

    private fun bindTabView(view: android.view.View, index: Int) {
        val file = tabs[index]
        view.findViewById<TextView>(R.id.tabTitle).text =
            (if (file.modified) "● " else "") + file.name
        view.findViewById<ImageButton>(R.id.tabClose).setOnClickListener {
            val pos = binding.tabLayout.selectedTabPosition
            // Resolve current index by identity in case positions shifted.
            val current = tabs.indexOf(file)
            closeTab(if (current >= 0) current else pos)
        }
    }

    // ----- UI state -----

    private fun updateUiState() {
        val hasTabs = tabs.isNotEmpty()
        binding.editor.visibility = if (hasTabs) android.view.View.VISIBLE else android.view.View.GONE
        binding.welcomeView.visibility = if (hasTabs) android.view.View.GONE else android.view.View.VISIBLE
        binding.tabLayout.visibility = if (hasTabs) android.view.View.VISIBLE else android.view.View.GONE
        binding.symbolInput.visibility = if (hasTabs) android.view.View.VISIBLE else android.view.View.GONE
        updateTitle()
    }

    private fun updateTitle() {
        if (currentTab in tabs.indices) {
            val file = tabs[currentTab]
            supportActionBar?.title = file.name
            supportActionBar?.subtitle =
                if (file.modified) "Modified" else (file.uri?.let { "Saved" } ?: "New file")
        } else {
            supportActionBar?.title = getString(R.string.app_name)
            supportActionBar?.subtitle = null
        }
    }

    private fun showAbout() {
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "1.0"
        AlertDialog.Builder(this)
            .setTitle(R.string.action_about)
            .setMessage(getString(R.string.about_message, version))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ----- IO helpers -----

    private fun readContent(uri: Uri): String =
        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""

    private fun writeContent(uri: Uri, text: String) {
        contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
        } ?: throw IllegalStateException("Cannot open output stream")
    }

    private fun queryName(uri: Uri): String {
        var name: String? = null
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
            }
        }
        return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "untitled"
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    // ----- Back handling -----

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
