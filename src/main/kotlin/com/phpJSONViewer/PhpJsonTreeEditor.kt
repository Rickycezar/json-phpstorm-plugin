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
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.event.TreeModelEvent
import javax.swing.event.TreeModelListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel

class JsonTreeNode(
    var key: String,
    var value: String,
    val isObject: Boolean,
    val isArray: Boolean,
    val isRootNode: Boolean = false,
    val inArray: Boolean = false
) : DefaultMutableTreeNode() {

    init {
        userObject = this
    }

    fun getDisplayText(isExpanded: Boolean): String {
        if (isRootNode) return "JSON Root"
        val prefix = if (inArray) "" else "$key: "
        return when {
            isObject -> if (isExpanded) "$prefix{}" else "$prefix{ ... }"
            isArray -> if (isExpanded) "$prefix[]" else "$prefix[ ... ]"
            else -> "$prefix$value"
        }
    }

    override fun toString(): String {
        if (isRootNode) return "JSON Root"
        if (isObject || isArray) return key
        if (inArray) return value
        return "$key: $value"
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
            JsonTreeNode("Error", "Erro de Sintaxe no JSON: ${e.message}", false, false, true)
        }

        val model = DefaultTreeModel(rootNode)
        tree = Tree(model)
        tree.isEditable = true
        tree.showsRootHandles = true
        tree.isRootVisible = false

        tree.toggleClickCount = 0

        tree.cellRenderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(
                tree: JTree, value: Any?, sel: Boolean, expanded: Boolean,
                leaf: Boolean, row: Int, hasFocus: Boolean
            ): Component {
                val c = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
                if (value is JsonTreeNode) {
                    text = value.getDisplayText(expanded)
                }
                return c
            }
        }

        model.addTreeModelListener(object : TreeModelListener {
            override fun treeNodesChanged(e: TreeModelEvent) {
                val node = e.children?.get(0) as? JsonTreeNode ?: return
                val newStr = node.userObject.toString()

                if (node.isObject || node.isArray) {
                    node.key = newStr
                } else if (node.inArray) {
                    node.value = newStr
                } else {
                    val parts = newStr.split(":", limit = 2)
                    if (parts.size == 2) {
                        node.key = parts[0].trim()
                        node.value = parts[1].trim()
                    } else {
                        node.value = newStr.trim()
                    }
                }

                node.userObject = node
                saveToFile()
            }

            override fun treeNodesInserted(e: TreeModelEvent?) {}
            override fun treeNodesRemoved(e: TreeModelEvent?) {}
            override fun treeStructureChanged(e: TreeModelEvent?) {}
        })

        panel = JBScrollPane(tree)

        var i = 0
        while (i < tree.rowCount) {
            tree.expandRow(i)
            i++
        }
    }

    private fun buildTreeNode(element: JsonElement, key: String, isRootNode: Boolean = false, inArray: Boolean = false): JsonTreeNode {
        val node = JsonTreeNode(key, "", element.isJsonObject, element.isJsonArray, isRootNode, inArray)
        when {
            element.isJsonObject -> {
                element.asJsonObject.entrySet().forEach { (k, v) ->
                    node.add(buildTreeNode(v, k))
                }
            }
            element.isJsonArray -> {
                element.asJsonArray.forEachIndexed { index, v ->
                    node.add(buildTreeNode(v, index.toString(), inArray = true))
                }
            }
            element.isJsonNull -> node.value = "null"
            else -> {
                val p = element.asJsonPrimitive
                node.value = if (p.isString) p.asString else p.toString()
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
                if (node.value == "null") return com.google.gson.JsonNull.INSTANCE
                val v = node.value.removeSurrounding("\"")

                return if (v == "true" || v == "false" || v.toDoubleOrNull() != null) {
                    JsonParser.parseString(v)
                } else {
                    JsonPrimitive(v)
                }
            }
        }
    }

    private fun saveToFile() {
        val documentManager = FileDocumentManager.getInstance()
        val document = documentManager.getDocument(file) ?: return

        val rootNode = tree.model.root as JsonTreeNode
        val jsonElement = buildJsonElement(rootNode)

        val newJsonString = GsonBuilder().create().toJson(jsonElement)

        val content = document.text
        val regex = Regex("""<\?php\s*/\*(.*?)\*/\s*\?>""", RegexOption.DOT_MATCHES_ALL)
        val newContent = content.replace(regex, "<?php /*$newJsonString*/ ?>")

        WriteCommandAction.runWriteCommandAction(project) {
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