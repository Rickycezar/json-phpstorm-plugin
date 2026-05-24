package com.phpJSONViewer

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class PhpJsonTreeEditorProvider : FileEditorProvider {
    override fun getEditorTypeId(): String = "PhpJsonTreeEditor"
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR

    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (file.extension != "php") return false
        val content = String(file.contentsToByteArray())
        return content.contains("<?php /*") && content.contains("*/ ?>")
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return PhpJsonTreeEditor(project, file)
    }
}