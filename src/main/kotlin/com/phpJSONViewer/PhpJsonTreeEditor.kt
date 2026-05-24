package com.phpJSONViewer

import com.google.gson.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import java.awt.Component
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class JsonTreeNode(
    var key: String,
    var value: String,
    var isObject: Boolean,
    var isArray: Boolean,
    val isRootNode: Boolean = false,
    var inArray: Boolean = false
) : DefaultMutableTreeNode() {

    override fun toString(): String = getDisplayText(false)

    fun getDisplayText(isExpanded: Boolean): String {
        if (isRootNode) return "JSON Root"
        val prefix = if (inArray) "" else "$key: "
        return when {
            isObject -> if (isExpanded) "$prefix{}" else "$prefix{ ... }"
            isArray -> if (isExpanded) "$prefix[]" else "$prefix[ ... ]"
            else -> "$prefix$value"
        }
    }
}

class PhpJsonTreeEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val panel: JBScrollPane
    private val tree: Tree

    init {
        val content = String(file.contentsToByteArray())
        val regex = Regex("""<\?php\s*/\*(.*?)\*/\s*\?>""", RegexOption.DOT_MATCHES_ALL)
        val jsonString = regex.find(content)?.groupValues?.get(1)?.trim() ?: "{}"

        val rootNode = try {
            val element = JsonParser.parseString(jsonString)
            buildTreeNode(element, "Root", isRootNode = true)
        } catch (e: Exception) {
            JsonTreeNode("Error", "Syntax Error: " + e.message, false, false, true)
        }

        val model = DefaultTreeModel(rootNode)
        tree = Tree(model)
        tree.showsRootHandles = true
        tree.isRootVisible = false
        tree.toggleClickCount = 0

        tree.cellRenderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(
                tree: JTree, value: Any?, sel: Boolean, expanded: Boolean,
                leaf: Boolean, row: Int, hasFocus: Boolean
            ): Component {
                val c = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
                if (value is JsonTreeNode) text = value.getDisplayText(expanded)
                return c
            }
        }

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val row = tree.getRowForLocation(e.x, e.y)
                    if (row != -1) {
                        tree.setSelectionRow(row)
                        val node = tree.lastSelectedPathComponent as? JsonTreeNode ?: return
                        showEditDialog(node)
                    }
                }
            }

            override fun mousePressed(e: MouseEvent) { if (e.isPopupTrigger) showContextMenu(e) }
            override fun mouseReleased(e: MouseEvent) { if (e.isPopupTrigger) showContextMenu(e) }
        })

        panel = JBScrollPane(tree)
        for (i in 0 until tree.rowCount) tree.expandRow(i)
    }

    private fun showContextMenu(e: MouseEvent) {
        val row = tree.getRowForLocation(e.x, e.y)
        if (row == -1) return
        tree.setSelectionRow(row)
        val node = tree.lastSelectedPathComponent as? JsonTreeNode ?: return

        val popup = JPopupMenu()

        val editItem = JMenuItem("Edit Item")
        editItem.addActionListener { showEditDialog(node) }
        popup.add(editItem)

        if (node.isObject || node.isArray || node.isRootNode) {
            val addItem = JMenuItem("New index/Value")
            addItem.addActionListener { showAddDialog(node) }
            popup.add(addItem)
        }

        if (!node.isRootNode) {
            val delItem = JMenuItem("Remove")
            delItem.addActionListener {
                val parent = node.parent as JsonTreeNode
                parent.remove(node)
                reindexArray(parent)
                (tree.model as DefaultTreeModel).reload(parent)
                saveToFile()
            }
            popup.add(delItem)
        }

        popup.show(tree, e.x, e.y)
    }

    private fun showEditDialog(node: JsonTreeNode) {
        if (node.isRootNode) return
        val dialogPanel = JPanel(GridLayout(0, 1))
        val keyField = JTextField(node.key)
        val valField = JTextField(node.value)

        if (!node.inArray) {
            dialogPanel.add(JLabel("Key:"))
            dialogPanel.add(keyField)
        }
        if (!node.isObject && !node.isArray) {
            dialogPanel.add(JLabel("Value (\"string\", 1, false, null, {}, []):"))
            dialogPanel.add(valField)
        }

        val result = JOptionPane.showConfirmDialog(
            tree, dialogPanel, "Edit Node",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        )

        if (result == JOptionPane.OK_OPTION) {
            if (!node.inArray) node.key = keyField.text.trim()

            if (!node.isObject && !node.isArray) {
                val newVal = valField.text.trim()
                when (newVal) {
                    "{}" -> { node.isObject = true; node.value = "" }
                    "[]" -> { node.isArray = true; node.value = "" }
                    else -> node.value = newVal
                }
            }
            (tree.model as DefaultTreeModel).nodeChanged(node)
            saveToFile()
        }
    }

    private fun showAddDialog(parentNode: JsonTreeNode) {
        val dialogPanel = JPanel(GridLayout(0, 1))
        val keyField = JTextField()
        val valField = JTextField("\"\"")

        if (!parentNode.isArray) {
            dialogPanel.add(JLabel("New Key:"))
            dialogPanel.add(keyField)
        }
        dialogPanel.add(JLabel("New Value (\"string\", 1, false, null, {}, []):"))
        dialogPanel.add(valField)

        val result = JOptionPane.showConfirmDialog(
            tree, dialogPanel, "Add Node",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        )

        if (result == JOptionPane.OK_OPTION) {
            val key = if (parentNode.isArray) parentNode.childCount.toString() else keyField.text.trim()
            val v = valField.text.trim()

            val isObj = v == "{}"
            val isArr = v == "[]"
            val finalVal = if (isObj || isArr) "" else v

            val newNode = JsonTreeNode(key, finalVal, isObj, isArr, false, parentNode.isArray)
            parentNode.add(newNode)

            (tree.model as DefaultTreeModel).reload(parentNode)
            tree.expandPath(TreePath(parentNode.path))
            saveToFile()
        }
    }

    private fun reindexArray(node: JsonTreeNode) {
        if (node.isArray) {
            for (i in 0 until node.childCount) {
                (node.getChildAt(i) as JsonTreeNode).key = i.toString()
            }
        }
    }

    private fun buildTreeNode(element: JsonElement, key: String, isRootNode: Boolean = false, inArray: Boolean = false): JsonTreeNode {
        val node = JsonTreeNode(key, "", element.isJsonObject, element.isJsonArray, isRootNode, inArray)
        when {
            element.isJsonObject -> {
                element.asJsonObject.entrySet().forEach { (k, v) -> node.add(buildTreeNode(v, k)) }
            }
            element.isJsonArray -> {
                element.asJsonArray.forEachIndexed { index, v -> node.add(buildTreeNode(v, index.toString(), inArray = true)) }
            }
            element.isJsonNull -> node.value = "null"
            else -> {
                val p = element.asJsonPrimitive
                node.value = if (p.isString) "\"" + p.asString + "\"" else p.toString()
            }
        }
        return node
    }

    private fun buildJsonElement(node: JsonTreeNode): JsonElement {
        when {
            node.isObject || node.isRootNode -> {
                val obj = JsonObject()
                for (i in 0 until node.childCount) {
                    val child = node.getChildAt(i) as JsonTreeNode
                    obj.add(child.key, buildJsonElement(child))
                }
                return obj
            }
            node.isArray -> {
                val arr = JsonArray()
                for (i in 0 until node.childCount) {
                    val child = node.getChildAt(i) as JsonTreeNode
                    arr.add(buildJsonElement(child))
                }
                return arr
            }
            else -> {
                val v = node.value.trim()
                if (v == "null") return JsonNull.INSTANCE

                if (v.startsWith("\"") && v.endsWith("\"") && v.length >= 2) {
                    return JsonPrimitive(v.substring(1, v.length - 1))
                }

                if (v == "true") return JsonPrimitive(true)
                if (v == "false") return JsonPrimitive(false)
                if (v.toDoubleOrNull() != null) return JsonParser.parseString(v)

                return JsonPrimitive(v)
            }
        }
    }

    private fun saveToFile() {
        WriteCommandAction.runWriteCommandAction(project) {
            val documentManager = FileDocumentManager.getInstance()
            val document = documentManager.getDocument(file) ?: return@runWriteCommandAction

            val rootNode = tree.model.root as JsonTreeNode
            val jsonElement = buildJsonElement(rootNode)

            val newJsonString = GsonBuilder().create().toJson(jsonElement)

            val content = document.text
            val regex = Regex("""<\?php\s*/\*(.*?)\*/\s*\?>""", RegexOption.DOT_MATCHES_ALL)
            val newContent = content.replace(regex, "<?php /*" + newJsonString + "*/ ?>")

            document.setText(newContent)
        }
    }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent? = panel
    override fun getName(): String = "JSON Tree"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() {}
    override fun getCurrentLocation(): FileEditorLocation? = null
}