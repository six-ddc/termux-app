package com.termux.autotermux.triggers

import android.content.Context
import android.content.Intent
import android.os.Build

class TriggerTermuxCommandLauncher(
    private val context: Context,
) {
    sealed class Result {
        object Success : Result()
        data class Error(val message: String) : Result()
    }

    fun launch(rule: TriggerRule, signal: TriggerSignal, renderedPrompt: String): Result {
        val commandPath = rule.commandPath?.takeIf { it.isNotBlank() }
            ?: return Result.Error("Missing commandPath")
        val stdin = TriggerTemplateRenderer.render(
            rule.stdinTemplate ?: rule.promptTemplate,
            rule,
            signal,
        ).ifBlank { renderedPrompt }
        val renderedArgs = rule.arguments
            .map { TriggerTemplateRenderer.render(it, rule, signal) }
            .toTypedArray()

        val intent = Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, commandPath)
            putExtra(EXTRA_ARGUMENTS, renderedArgs)
            putExtra(EXTRA_STDIN, stdin)
            rule.workingDirectory?.takeIf { it.isNotBlank() }?.let {
                putExtra(EXTRA_WORKDIR, it)
            }
            putExtra(EXTRA_BACKGROUND, rule.background)
            putExtra(EXTRA_RUNNER, rule.runner)
            putExtra(EXTRA_COMMAND_LABEL, rule.commandLabel ?: "AutoTermux trigger ${rule.name}")
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Result.Success
        } catch (e: Exception) {
            Result.Error(e.message ?: "Failed to launch Termux command")
        }
    }

    companion object {
        private const val TERMUX_PACKAGE = "com.termux"
        private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_RUNNER = "com.termux.RUN_COMMAND_RUNNER"
        private const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    }
}
