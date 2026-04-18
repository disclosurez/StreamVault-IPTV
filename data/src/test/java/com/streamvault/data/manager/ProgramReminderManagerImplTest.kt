package com.streamvault.data.manager

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.ProgramReminderDao
import com.streamvault.data.local.entity.ProgramReminderEntity
import com.streamvault.data.manager.reminder.ProgramReminderAlarmScheduler
import com.streamvault.data.manager.reminder.ProgramReminderNotifier
import com.streamvault.domain.model.Program
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ProgramReminderManagerImplTest {

    private val dao: ProgramReminderDao = mock()
    private val alarmScheduler: ProgramReminderAlarmScheduler = mock()
    private val notifier: ProgramReminderNotifier = mock()

    private val manager = ProgramReminderManagerImpl(
        programReminderDao = dao,
        alarmScheduler = alarmScheduler,
        notifier = notifier
    )

    @Test
    fun `scheduleReminder inserts reminder and schedules alarm`() = runTest {
        val now = System.currentTimeMillis()
        val program = Program(
            channelId = "bbc1",
            title = "World News",
            startTime = now + 30 * 60_000L,
            endTime = now + 60 * 60_000L,
            providerId = 7L
        )
        whenever(dao.getByProgram(7L, "bbc1", "World News", program.startTime)).thenReturn(null)
        whenever(dao.insert(org.mockito.kotlin.any())).thenReturn(42L)

        val result = manager.scheduleReminder(
            providerId = 7L,
            channelId = "bbc1",
            channelName = "BBC One",
            program = program
        )

        assertThat(result).isInstanceOf(com.streamvault.domain.model.Result.Success::class.java)
        verify(dao).insert(org.mockito.kotlin.any())
        verify(alarmScheduler).schedule(42L, org.mockito.kotlin.any())
    }

    @Test
    fun `cancelReminder deletes reminder and cancels alarm`() = runTest {
        val reminder = ProgramReminderEntity(
            id = 42L,
            providerId = 7L,
            channelId = "bbc1",
            channelName = "BBC One",
            programTitle = "World News",
            programStartTime = 1_000L,
            remindAt = 900L
        )
        whenever(dao.getByProgram(7L, "bbc1", "World News", 1_000L)).thenReturn(reminder)

        manager.cancelReminder(
            providerId = 7L,
            channelId = "bbc1",
            programTitle = "World News",
            programStartTime = 1_000L
        )

        verify(dao).deleteById(42L)
        verify(alarmScheduler).cancel(42L)
    }

    @Test
    fun `deliverReminder notifies once and marks reminder notified`() = runTest {
        val reminder = ProgramReminderEntity(
            id = 42L,
            providerId = 7L,
            channelId = "bbc1",
            channelName = "BBC One",
            programTitle = "World News",
            programStartTime = System.currentTimeMillis() + 5 * 60_000L,
            remindAt = System.currentTimeMillis()
        )
        whenever(dao.getById(42L)).thenReturn(reminder)

        manager.deliverReminder(42L)

        verify(notifier).showReminder(reminder)
        val updatedCaptor = argumentCaptor<ProgramReminderEntity>()
        verify(dao).update(updatedCaptor.capture())
        assertThat(updatedCaptor.firstValue.notifiedAt).isNotNull()
    }
}
