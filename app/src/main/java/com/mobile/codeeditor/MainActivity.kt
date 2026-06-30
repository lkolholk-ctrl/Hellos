package com.mobile.codeeditor

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
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
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.langs.java.JavaLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher.SearchOptions
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
import io.github.rosemoe.sora.widget.schemes.SchemeEclipse
import io.github.rosemoe.sora.widget.schemes.SchemeGitHub
import io.github.rosemoe.sora.widget.schemes.SchemeNotepadXX
import io.github.rosemoe.sora.widget.schemes.SchemeVS2019

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var fileAdapter: FileTreeAdapter
    private lateinit var prefs: Prefs

    private val tabs = mutableListOf<OpenFile>()
    private var currentTab = -1

    private var loadingContent = false
    private var suppressTabEvents = false
    private var pendingSaveTab = -1

    // Find & Replace state
    private var matchCaseEnabled = false
    private var regexEnabled = false

    private val codeExtensions = setOf(
        "java", "kt", "kts", "js", "jsx", "ts", "tsx", "json", "c", "cc", "cpp",
        "h", "hpp", "cs", "go", "rs", "php", "swift", "gradle", "scala", "dart",
        "groovy", "css", "scss"
    )

    // ----- Activity result launchers -----

    private val openFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            persistPermission(uri, write = false)
            openFileInTab(uri)
        }

    private val openFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@registerForActivityResult
            persistPermission(uri, write = true)
            setFolder(uri)
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

    private val createFileLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri ?: return@registerForActivityResult
            val index = pendingSaveTab
            pendingSaveTab = -1
            if (index !in tabs.indices) return@registerForActivityResult
            persistPermission(uri, write = true)
            tabs[index].uri = uri
            tabs[index].name = queryName(uri)
            writeTab(index)
        }

    // ----- Lifecycle -----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupEditor()
        setupExplorer()
        setupTabs()
        setupWelcome()
        setupFindPanel()

        binding.statusBar.setOnClickListener { showGotoLineDialog() }

        val handledIntent = intent?.action == Intent.ACTION_VIEW && intent.data != null
        if (handledIntent) {
            openFileInTab(intent.data!!)
        } else if (prefs.restoreSession) {
            restoreSession()
        }

        updateUiState()
    }

    override fun onResume() {
        super.onResume()
        applyEditorPrefs()
    }

    override fun onStop() {
        super.onStop()
        saveSession()
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
            typefaceText = Typeface.MONOSPACE
            setCursorAnimationEnabled(true)
            subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                if (!loadingContent && currentTab in tabs.indices && !tabs[currentTab].modified) {
                    tabs[currentTab].modified = true
                    refreshTabTitles()
                    updateTitle()
                }
                updateStatus()
            }
            subscribeEvent(SelectionChangeEvent::class.java) { _, _ -> updateStatus() }
        }
        applyEditorPrefs()

        binding.symbolInput.bindEditor(binding.editor)
        binding.symbolInput.addSymbols(
            arrayOf("→", "{", "}", "(", ")", "[", "]", ";", "\"", "'", "=", ":", ".", ",", "<", ">", "/", "|", "&", "!", "?", "+", "-", "*"),
            arrayOf("\t", "{", "}", "(", ")", "[", "]", ";", "\"", "'", "=", ":", ".", ",", "<", ">", "/", "|", "&", "!", "?", "+", "-", "*")
        )
    }

    private fun applyEditorPrefs() {
        binding.editor.apply {
            colorScheme = themeFor(prefs.theme)
            setTextSize(prefs.fontSize.toFloat())
            setTabWidth(prefs.tabSize)
            isWordwrap = prefs.wordWrap
            isLineNumberEnabled = prefs.lineNumbers
            nonPrintablePaintingFlags = if (prefs.showWhitespace) {
                CodeEditor.FLAG_DRAW_WHITESPACE_LEADING or
                    CodeEditor.FLAG_DRAW_WHITESPACE_INNER or
                    CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING
            } else 0
        }
    }

    private fun themeFor(index: Int) = when (index) {
        1 -> SchemeVS2019()
        2 -> SchemeGitHub()
        3 -> SchemeEclipse()
        4 -> SchemeNotepadXX()
        else -> SchemeDarcula()
    }

    private fun setupExplorer() {
        fileAdapter = FileTreeAdapter(
            onFileClick = { doc -> openDocumentFile(doc) },
            onItemLongClick = { doc -> showFileContextMenu(doc) }
        )
        binding.fileList.layoutManager = LinearLayoutManager(this)
        binding.fileList.adapter = fileAdapter

        binding.btnDrawerOpenFolder.setOnClickListener { openFolderLauncher.launch(null) }
        binding.btnDrawerRefresh.setOnClickListener { fileAdapter.refresh() }
        binding.btnDrawerNewFile.setOnClickListener { createInDir(fileAdapter.rootDir, isFolder = false) }
        binding.btnDrawerNewFolder.setOnClickListener { createInDir(fileAdapter.rootDir, isFolder = true) }
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

    // ----- Find & Replace -----

    private fun setupFindPanel() {
        binding.findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = runSearch()
        })
        binding.btnFindNext.setOnClickListener {
            binding.editor.searcher.gotoNext(); postMatchCount()
        }
        binding.btnFindPrev.setOnClickListener {
            binding.editor.searcher.gotoPrevious(); postMatchCount()
        }
        binding.btnMatchCase.setOnClickListener {
            matchCaseEnabled = !matchCaseEnabled
            tintToggle(binding.btnMatchCase, matchCaseEnabled)
            runSearch()
        }
        binding.btnRegex.setOnClickListener {
            regexEnabled = !regexEnabled
            tintToggle(binding.btnRegex, regexEnabled)
            runSearch()
        }
        binding.btnReplace.setOnClickListener {
            if (binding.editor.searcher.hasQuery()) {
                binding.editor.searcher.replaceThis(binding.replaceInput.text.toString())
                postMatchCount()
            }
        }
        binding.btnReplaceAll.setOnClickListener {
            if (binding.editor.searcher.hasQuery()) {
                binding.editor.searcher.replaceAll(binding.replaceInput.text.toString())
                postMatchCount()
            }
        }
        binding.btnCloseFind.setOnClickListener { closeFind() }
        tintToggle(binding.btnMatchCase, false)
        tintToggle(binding.btnRegex, false)
    }

    private fun openFind() {
        binding.findPanel.visibility = View.VISIBLE
        val sel = binding.editor.cursor
        if (sel.isSelected) {
            val text = binding.editor.text.substring(sel.left, sel.right)
            if (!text.contains('\n')) binding.findInput.setText(text)
        }
        binding.findInput.requestFocus()
        runSearch()
    }

    private fun closeFind() {
        binding.findPanel.visibility = View.GONE
        binding.editor.searcher.stopSearch()
    }

    private fun runSearch() {
        val query = binding.findInput.text.toString()
        val searcher = binding.editor.searcher
        if (query.isEmpty()) {
            searcher.stopSearch()
            binding.matchCount.text = ""
            return
        }
        try {
            val type = if (regexEnabled) {
                SearchOptions.TYPE_REGULAR_EXPRESSION
            } else {
                SearchOptions.TYPE_NORMAL
            }
            searcher.search(query, SearchOptions(type, !matchCaseEnabled))
            postMatchCount()
        } catch (e: Exception) {
            binding.matchCount.text = getString(R.string.no_matches)
        }
    }

    private fun postMatchCount() {
        // Search runs asynchronously; refresh the counter shortly after.
        binding.matchCount.postDelayed({ updateMatchCount() }, 120)
        updateMatchCount()
    }

    private fun updateMatchCount() {
        val searcher = binding.editor.searcher
        if (!searcher.hasQuery()) {
            binding.matchCount.text = ""
            return
        }
        val count = searcher.matchedPositionCount
        binding.matchCount.text = if (count == 0) {
            getString(R.string.no_matches)
        } else {
            getString(R.string.match_count, searcher.currentMatchedPositionIndex + 1, count)
        }
    }

    private fun tintToggle(button: ImageButton, active: Boolean) {
        val color = if (active) {
            getColor(R.color.vscode_accent)
        } else {
            getColor(R.color.vscode_text_dim)
        }
        button.setColorFilter(color)
    }

    // ----- Menu -----

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_undo -> { if (binding.editor.canUndo()) binding.editor.undo(); true }
            R.id.action_redo -> { if (binding.editor.canRedo()) binding.editor.redo(); true }
            R.id.action_find -> { if (tabs.isNotEmpty()) openFind(); true }
            R.id.action_save -> { saveCurrent(); true }
            R.id.action_open_file -> { openFileLauncher.launch(arrayOf("*/*")); true }
            R.id.action_open_folder -> { openFolderLauncher.launch(null); true }
            R.id.action_new_file -> { newFile(); true }
            R.id.action_save_as -> { saveAs(); true }
            R.id.action_goto_line -> { showGotoLineDialog(); true }
            R.id.action_share -> { shareCurrent(); true }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java)); true
            }
            R.id.action_about -> { showAbout(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ----- Tab / file logic -----

    private fun openDocumentFile(doc: DocumentFile) {
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        openFileInTab(doc.uri)
    }

    private fun openFileInTab(uri: Uri, silent: Boolean = false): Boolean {
        val existing = tabs.indexOfFirst { it.uri == uri }
        if (existing >= 0) {
            selectTab(existing)
            updateUiState()
            return true
        }
        val content = try {
            readContent(uri)
        } catch (e: Exception) {
            if (!silent) toast(getString(R.string.open_failed, e.message ?: ""))
            return false
        }
        tabs.add(OpenFile(queryName(uri), uri, content))
        refreshTabs()
        selectTab(tabs.size - 1)
        updateUiState()
        return true
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
        updateStatus()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val file = tabs[index]
        if (index == currentTab) file.content = binding.editor.text.toString()
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
        if (tabs.isNotEmpty()) selectTab((index - 1).coerceAtLeast(0)) else selectTab(-1)
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

    private fun showGotoLineDialog() {
        if (currentTab !in tabs.indices) return
        val lineCount = binding.editor.text.lineCount
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.goto_line_hint, lineCount)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.goto_line_title)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val line = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                val target = (line - 1).coerceIn(0, lineCount - 1)
                binding.editor.setSelection(target, 0)
                binding.editor.ensurePositionVisible(target, 0)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun shareCurrent() {
        if (currentTab !in tabs.indices) return
        val file = tabs[currentTab]
        val text = binding.editor.text.toString()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, file.name))
    }

    // ----- File explorer operations -----

    private fun showFileContextMenu(doc: DocumentFile) {
        val isDir = doc.isDirectory
        val actions = if (isDir) {
            arrayOf(
                getString(R.string.new_file),
                getString(R.string.new_folder),
                getString(R.string.rename),
                getString(R.string.delete)
            )
        } else {
            arrayOf(getString(R.string.rename), getString(R.string.delete))
        }
        AlertDialog.Builder(this)
            .setTitle(doc.name ?: "?")
            .setItems(actions) { _, which ->
                if (isDir) {
                    when (which) {
                        0 -> createInDir(doc, isFolder = false)
                        1 -> createInDir(doc, isFolder = true)
                        2 -> renameDoc(doc)
                        3 -> deleteDoc(doc)
                    }
                } else {
                    when (which) {
                        0 -> renameDoc(doc)
                        1 -> deleteDoc(doc)
                    }
                }
            }
            .show()
    }

    private fun createInDir(dir: DocumentFile?, isFolder: Boolean) {
        if (dir == null || !dir.isDirectory) return
        promptName(
            titleRes = if (isFolder) R.string.new_folder_title else R.string.new_file_title,
            initial = ""
        ) { name ->
            val created = if (isFolder) {
                dir.createDirectory(name)
            } else {
                dir.createFile("text/plain", name)
            }
            if (created == null) {
                toast(getString(R.string.op_failed))
            } else {
                fileAdapter.refresh()
                if (!isFolder) openFileInTab(created.uri)
            }
        }
    }

    private fun renameDoc(doc: DocumentFile) {
        promptName(R.string.rename_title, doc.name ?: "") { name ->
            if (doc.renameTo(name)) fileAdapter.refresh() else toast(getString(R.string.op_failed))
        }
    }

    private fun deleteDoc(doc: DocumentFile) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_message, doc.name ?: "?"))
            .setPositiveButton(R.string.delete) { _, _ ->
                val uri = doc.uri
                if (doc.delete()) {
                    val open = tabs.indexOfFirst { it.uri == uri }
                    if (open >= 0) performClose(open)
                    fileAdapter.refresh()
                } else {
                    toast(getString(R.string.op_failed))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptName(titleRes: Int, initial: String, onConfirm: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.name_hint)
            setText(initial)
            setSelection(initial.length)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(input)
            .setPositiveButton(R.string.create) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onConfirm(name)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

    private fun bindTabView(view: View, index: Int) {
        val file = tabs[index]
        view.findViewById<TextView>(R.id.tabTitle).text =
            (if (file.modified) "● " else "") + file.name
        view.findViewById<ImageButton>(R.id.tabClose).setOnClickListener {
            val current = tabs.indexOf(file)
            closeTab(if (current >= 0) current else binding.tabLayout.selectedTabPosition)
        }
    }

    // ----- UI state -----

    private fun updateUiState() {
        val hasTabs = tabs.isNotEmpty()
        binding.editor.visibility = if (hasTabs) View.VISIBLE else View.GONE
        binding.welcomeView.visibility = if (hasTabs) View.GONE else View.VISIBLE
        binding.tabLayout.visibility = if (hasTabs) View.VISIBLE else View.GONE
        binding.symbolInput.visibility = if (hasTabs) View.VISIBLE else View.GONE
        binding.statusBar.visibility = if (hasTabs) View.VISIBLE else View.GONE
        if (!hasTabs) closeFind()
        updateTitle()
        updateStatus()
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

    private fun updateStatus() {
        if (currentTab !in tabs.indices) return
        val cursor = binding.editor.cursor
        binding.statusPosition.text =
            getString(R.string.status_position, cursor.leftLine + 1, cursor.leftColumn + 1)
        binding.statusLines.text = getString(R.string.status_lines, binding.editor.text.lineCount)
        val ext = tabs[currentTab].name.substringAfterLast('.', "").uppercase()
        binding.statusLang.text = if (ext.isEmpty()) "TEXT" else ext
    }

    private fun showAbout() {
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "1.0"
        AlertDialog.Builder(this)
            .setTitle(R.string.action_about)
            .setMessage(getString(R.string.about_message, version))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    // ----- Session -----

    private fun setFolder(treeUri: Uri) {
        val doc = DocumentFile.fromTreeUri(this, treeUri)
        fileAdapter.setRoot(doc)
        binding.folderName.text = doc?.name ?: getString(R.string.explorer)
        prefs.lastFolder = treeUri.toString()
    }

    private fun saveSession() {
        if (!prefs.restoreSession) return
        if (currentTab in tabs.indices) {
            tabs[currentTab].content = binding.editor.text.toString()
        }
        prefs.openFiles = tabs.mapNotNull { it.uri?.toString() }
        prefs.activeTab = currentTab.coerceAtLeast(0)
    }

    private fun restoreSession() {
        prefs.lastFolder?.let { folder ->
            runCatching { setFolder(Uri.parse(folder)) }
        }
        val files = prefs.openFiles
        if (files.isEmpty()) return
        var opened = 0
        for (uriStr in files) {
            if (openFileInTab(Uri.parse(uriStr), silent = true)) opened++
        }
        if (opened > 0) {
            selectTab(prefs.activeTab.coerceIn(0, tabs.size - 1))
        }
    }

    // ----- IO helpers -----

    private fun persistPermission(uri: Uri, write: Boolean) {
        runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            contentResolver.takePersistableUriPermission(uri, flags)
        }
    }

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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            binding.drawerLayout.isDrawerOpen(GravityCompat.START) ->
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            binding.findPanel.visibility == View.VISIBLE -> closeFind()
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }
}
