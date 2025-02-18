package com.fsck.k9.notification

import app.k9mail.legacy.account.Account
import com.fsck.k9.K9

internal class SingleMessageNotificationDataCreator {

    fun createSingleNotificationData(
        account: Account,
        notificationId: Int,
        content: NotificationContent,
        timestamp: Long,
        addLockScreenNotification: Boolean,
    ): SingleNotificationData {
        return SingleNotificationData(
            notificationId = notificationId,
            isSilent = true,
            timestamp = timestamp,
            content = content,
            actions = createSingleNotificationActions(),
            wearActions = createSingleNotificationWearActions(account),
            addLockScreenNotification = addLockScreenNotification,
        )
    }

    fun createSummarySingleNotificationData(
        data: NotificationData,
        timestamp: Long,
        silent: Boolean,
    ): SummarySingleNotificationData {
        return SummarySingleNotificationData(
            SingleNotificationData(
                notificationId = NotificationIds.getNewMailSummaryNotificationId(data.account),
                isSilent = silent,
                timestamp = timestamp,
                content = data.activeNotifications.first().content,
                actions = createSingleNotificationActions(),
                wearActions = createSingleNotificationWearActions(data.account),
                addLockScreenNotification = false,
            ),
        )
    }

    private fun createSingleNotificationActions(): List<NotificationAction> {
        return buildList {
            add(NotificationAction.Reply)
            add(NotificationAction.MarkAsRead)

            if (isActionButtonEnabled()) {
                when (K9.notificationQuickAction) {
                    K9.NotificationQuickAction.DELETE -> add(NotificationAction.Delete)
                    K9.NotificationQuickAction.ARCHIVE -> add(NotificationAction.Archive)
                }
            }
        }
    }

    private fun createSingleNotificationWearActions(account: Account): List<WearNotificationAction> {
        return buildList {
            add(WearNotificationAction.Reply)
            add(WearNotificationAction.MarkAsRead)

            if (isDeleteActionAvailableForWear()) {
                add(WearNotificationAction.Delete)
            }

            if (account.hasArchiveFolder()) {
                add(WearNotificationAction.Archive)
            }

            if (isSpamActionAvailableForWear(account)) {
                add(WearNotificationAction.Spam)
            }
        }
    }

    private fun isActionButtonEnabled(): Boolean {
        return K9.notificationQuickDeleteBehaviour != K9.NotificationQuickDelete.NEVER
    }

    // We don't support confirming actions on Wear devices. So don't show the action when confirmation is enabled.
    private fun isDeleteActionAvailableForWear(): Boolean {
        return isActionButtonEnabled() && !K9.isConfirmDeleteFromNotification
    }

    // We don't support confirming actions on Wear devices. So don't show the action when confirmation is enabled.
    private fun isSpamActionAvailableForWear(account: Account): Boolean {
        return account.hasSpamFolder() && !K9.isConfirmSpam
    }
}
