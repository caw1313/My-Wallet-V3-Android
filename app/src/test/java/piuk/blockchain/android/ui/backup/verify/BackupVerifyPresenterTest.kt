package piuk.blockchain.android.ui.backup.verify

import com.blockchain.componentlib.alert.SnackbarType
import com.blockchain.preferences.WalletStatus
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.times
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.verifyNoMoreInteractions
import com.nhaarman.mockitokotlin2.verifyZeroInteractions
import com.nhaarman.mockitokotlin2.whenever
import io.reactivex.rxjava3.core.Completable
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import piuk.blockchain.android.R
import piuk.blockchain.android.util.BackupWalletUtil
import piuk.blockchain.androidcore.data.payload.PayloadDataManager

class BackupVerifyPresenterTest {

    private lateinit var subject: BackupVerifyPresenter
    private val view: BackupVerifyView = mock()
    private val payloadDataManager: PayloadDataManager = mock(defaultAnswer = Mockito.RETURNS_DEEP_STUBS)
    private val walletStatus: WalletStatus = mock()
    private val backupWalletUtil: BackupWalletUtil = mock()

    @Before
    fun setUp() {
        subject = BackupVerifyPresenter(payloadDataManager, walletStatus, backupWalletUtil)
        subject.initView(view)
    }

    @Test
    fun onViewReady() {
        // Arrange
        val pairOne = 1 to "word_one"
        val pairTwo = 6 to "word_two"
        val pairThree = 7 to "word_three"
        val sequence = listOf(pairOne, pairTwo, pairThree)
        whenever(view.getPageBundle()).thenReturn(null)
        whenever(backupWalletUtil.getConfirmSequence(null)).thenReturn(sequence)
        // Act
        subject.onViewReady()
        // Assert
        verify(view).getPageBundle()
        verify(view).showWordHints(listOf(1, 6, 7))
        verifyNoMoreInteractions(view)
        verify(backupWalletUtil).getConfirmSequence(null)
        verifyNoMoreInteractions(backupWalletUtil)
    }

    @Test
    fun `onVerifyClicked failure`() {
        // Arrange
        val pairOne = 1 to "word_one"
        val pairTwo = 6 to "word_two"
        val pairThree = 7 to "word_three"
        val sequence = listOf(pairOne, pairTwo, pairThree)
        whenever(backupWalletUtil.getConfirmSequence(null)).thenReturn(sequence)
        // Act
        subject.onVerifyClicked(pairOne.second, pairTwo.second, pairTwo.second)
        // Assert
        verify(view).getPageBundle()
        verify(view).showSnackbar(R.string.backup_word_mismatch, SnackbarType.Error)
        verifyNoMoreInteractions(view)
        verify(backupWalletUtil).getConfirmSequence(null)
        verifyNoMoreInteractions(backupWalletUtil)
    }

    @Test
    fun `onVerifyClicked success`() {
        // Arrange
        val pairOne = 1 to "word_one"
        val pairTwo = 6 to "word_two"
        val pairThree = 7 to "word_three"
        val sequence = listOf(pairOne, pairTwo, pairThree)
        whenever(backupWalletUtil.getConfirmSequence(null)).thenReturn(sequence)
        whenever(payloadDataManager.syncPayloadWithServer()).thenReturn(Completable.complete())
        whenever(payloadDataManager.wallet!!.walletBody).thenReturn(mock())
        // Act
        subject.onVerifyClicked(pairOne.second, pairTwo.second, pairThree.second)
        // Assert
        verify(backupWalletUtil).getConfirmSequence(null)
        verifyNoMoreInteractions(backupWalletUtil)
        verify(payloadDataManager).syncPayloadWithServer()
        verify(payloadDataManager, times(2)).wallet
        verifyNoMoreInteractions(payloadDataManager)
        verify(view).getPageBundle()
        verify(view).showProgressDialog()
        verify(view).hideProgressDialog()
        verify(view).showSnackbar(any(), eq(SnackbarType.Success))
        verify(view).showCompletedFragment()
        verifyNoMoreInteractions(view)
        verify(walletStatus).lastBackupTime = any()
        verifyNoMoreInteractions(walletStatus)
    }

    @Test
    fun `updateBackupStatus success`() {
        // Arrange
        whenever(payloadDataManager.syncPayloadWithServer()).thenReturn(Completable.complete())
        whenever(payloadDataManager.wallet!!.walletBody).thenReturn(mock())
        // Act
        subject.updateBackupStatus()
        // Assert
        verify(payloadDataManager).syncPayloadWithServer()
        verify(payloadDataManager, times(2)).wallet
        verifyNoMoreInteractions(payloadDataManager)
        verify(view).showProgressDialog()
        verify(view).hideProgressDialog()
        verify(view).showSnackbar(any(), eq(SnackbarType.Success))
        verify(view).showCompletedFragment()
        verifyNoMoreInteractions(view)
        verify(walletStatus).lastBackupTime = any()
        verifyNoMoreInteractions(walletStatus)
    }

    @Test
    fun `updateBackupStatus failure`() {
        // Arrange
        whenever(payloadDataManager.syncPayloadWithServer())
            .thenReturn(Completable.error { Throwable() })
        whenever(payloadDataManager.wallet!!.walletBody).thenReturn(mock())
        // Act
        subject.updateBackupStatus()
        // Assert
        verify(payloadDataManager).syncPayloadWithServer()
        verify(payloadDataManager, times(2)).wallet
        verifyNoMoreInteractions(payloadDataManager)
        verify(view).showProgressDialog()
        verify(view).hideProgressDialog()
        verify(view).showSnackbar(any(), eq(SnackbarType.Error))
        verify(view).showStartingFragment()
        verifyNoMoreInteractions(view)
        verifyZeroInteractions(walletStatus)
    }
}
