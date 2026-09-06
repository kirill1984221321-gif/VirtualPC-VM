package com.virtualpcvm

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Reusable confirmation dialog component for all destructive & safety-critical VM operations:
 * - Shutting down a running VM
 * - Deleting a VM or snapshot
 * - Wiping disk images or restoring snapshots
 */
object ConfirmationDialogHelper {

    enum class ActionType(
        val defaultConfirmTextRu: String,
        val defaultConfirmTextEn: String,
        val isDestructive: Boolean
    ) {
        SHUTDOWN_VM(
            defaultConfirmTextRu = "Выключить ВМ",
            defaultConfirmTextEn = "Shut Down VM",
            isDestructive = true
        ),
        DELETE_VM(
            defaultConfirmTextRu = "Удалить ВМ",
            defaultConfirmTextEn = "Delete VM",
            isDestructive = true
        ),
        DELETE_SNAPSHOT(
            defaultConfirmTextRu = "Удалить снимок",
            defaultConfirmTextEn = "Delete Snapshot",
            isDestructive = true
        ),
        REVERT_SNAPSHOT(
            defaultConfirmTextRu = "Откатить состояние",
            defaultConfirmTextEn = "Revert Snapshot",
            isDestructive = true
        ),
        WIPE_DISK(
            defaultConfirmTextRu = "Стереть диск",
            defaultConfirmTextEn = "Wipe Disk",
            isDestructive = true
        ),
        CUSTOM(
            defaultConfirmTextRu = "Подтвердить",
            defaultConfirmTextEn = "Confirm",
            isDestructive = true
        )
    }

    fun show(
        context: Context,
        title: String,
        message: String,
        details: String? = null,
        actionType: ActionType = ActionType.CUSTOM,
        confirmText: String? = null,
        cancelText: String? = null,
        onConfirm: () -> Unit
    ): AlertDialog {
        val isRu = LocaleHelper.getLanguage(context) == LocaleHelper.LANG_RU
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_confirmation, null)

        val ivIcon = dialogView.findViewById<ImageView>(R.id.ivConfirmIcon)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvConfirmTitle)
        val tvSubtitle = dialogView.findViewById<TextView>(R.id.tvConfirmSubtitle)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvConfirmMessage)
        val tvDetails = dialogView.findViewById<TextView>(R.id.tvConfirmDetails)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)
        val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnConfirm)

        tvTitle.text = title
        tvMessage.text = message

        if (!details.isNullOrBlank()) {
            tvDetails.visibility = View.VISIBLE
            tvDetails.text = details
        } else {
            tvDetails.visibility = View.GONE
        }

        val resolvedConfirmText = confirmText ?: if (isRu) actionType.defaultConfirmTextRu else actionType.defaultConfirmTextEn
        btnConfirm.text = resolvedConfirmText

        if (cancelText != null) {
            btnCancel.text = cancelText
        }

        // Apply theme/danger colors based on action type
        when (actionType) {
            ActionType.SHUTDOWN_VM -> {
                tvSubtitle.text = if (isRu) "Принудительная остановка QEMU" else "Forced QEMU stop"
                tvSubtitle.setTextColor(Color.parseColor("#FF9800")) // Orange
                ivIcon.setImageResource(android.R.drawable.ic_lock_power_off)
                ivIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#FF9800"))
                btnConfirm.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E65100"))
            }
            ActionType.REVERT_SNAPSHOT -> {
                tvSubtitle.text = if (isRu) "Откат к контрольной точке" else "State revert"
                tvSubtitle.setTextColor(Color.parseColor("#0288D1")) // Light Blue
                ivIcon.setImageResource(android.R.drawable.ic_menu_revert)
                ivIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#0288D1"))
                btnConfirm.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#01579B"))
            }
            ActionType.DELETE_VM, ActionType.WIPE_DISK -> {
                tvSubtitle.text = if (isRu) "Необратимое удаление данных" else "Irreversible data deletion"
                tvSubtitle.setTextColor(ContextCompat.getColor(context, R.color.color_error))
                ivIcon.setImageResource(android.R.drawable.ic_menu_delete)
                ivIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.color_error))
                btnConfirm.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.color_error))
            }
            ActionType.DELETE_SNAPSHOT -> {
                tvSubtitle.text = if (isRu) "Удаление снимка диска" else "Disk snapshot deletion"
                tvSubtitle.setTextColor(ContextCompat.getColor(context, R.color.color_error))
                ivIcon.setImageResource(android.R.drawable.ic_menu_delete)
                ivIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.color_error))
                btnConfirm.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.color_error))
            }
            ActionType.CUSTOM -> {
                tvSubtitle.text = if (isRu) "Требуется подтверждение" else "Confirmation required"
                tvSubtitle.setTextColor(ContextCompat.getColor(context, R.color.color_error))
                ivIcon.setImageResource(android.R.drawable.ic_dialog_alert)
            }
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        btnCancel.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            dialog.dismiss()
            onConfirm()
        }

        dialog.show()
        return dialog
    }
}
