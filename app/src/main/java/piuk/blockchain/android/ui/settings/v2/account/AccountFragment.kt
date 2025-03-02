package piuk.blockchain.android.ui.settings.v2.account

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import com.blockchain.commonarch.presentation.base.updateToolbar
import com.blockchain.commonarch.presentation.mvi.MviFragment
import com.blockchain.componentlib.alert.SnackbarType
import com.blockchain.componentlib.basic.ImageResource
import com.blockchain.componentlib.tag.TagType
import com.blockchain.componentlib.tag.TagViewState
import com.blockchain.componentlib.viewextensions.visibleIf
import com.blockchain.koin.scopedInject
import com.blockchain.koin.walletConnectFeatureFlag
import com.blockchain.notifications.analytics.LaunchOrigin
import com.blockchain.remoteconfig.FeatureFlag
import com.blockchain.walletconnect.domain.WalletConnectAnalytics
import info.blockchain.balance.FiatCurrency
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import piuk.blockchain.android.BuildConfig
import piuk.blockchain.android.R
import piuk.blockchain.android.databinding.FragmentAccountBinding
import piuk.blockchain.android.simplebuy.sheets.CurrencySelectionSheet
import piuk.blockchain.android.ui.customviews.BlockchainSnackbar
import piuk.blockchain.android.ui.customviews.ErrorBottomDialog
import piuk.blockchain.android.ui.settings.SettingsAnalytics
import piuk.blockchain.android.ui.settings.v2.SettingsNavigator
import piuk.blockchain.android.ui.settings.v2.SettingsScreen
import piuk.blockchain.android.ui.thepit.ExchangeConnectionSheet
import piuk.blockchain.android.urllinks.URL_THE_PIT_LAUNCH_SUPPORT
import piuk.blockchain.android.util.launchUrlInBrowser

class AccountFragment :
    MviFragment<AccountModel, AccountIntent, AccountState, FragmentAccountBinding>(),
    CurrencySelectionSheet.Host,
    SettingsScreen {

    override fun initBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentAccountBinding =
        FragmentAccountBinding.inflate(inflater, container, false)

    override fun navigator(): SettingsNavigator =
        (activity as? SettingsNavigator) ?: throw IllegalStateException(
            "Parent must implement SettingsNavigator"
        )

    override fun onBackPressed(): Boolean = true

    override val model: AccountModel by scopedInject()
    private val walletConnectFF: FeatureFlag by scopedInject(walletConnectFeatureFlag)

    private val compositeDisposable = CompositeDisposable()
    private lateinit var walletId: String

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updateToolbar(
            toolbarTitle = getString(R.string.account_toolbar),
            menuItems = emptyList()
        )

        compositeDisposable += walletConnectFF.enabled.onErrorReturn { false }.subscribe { enabled ->
            binding.settingsWalletConnect.visibleIf { enabled }
        }

        with(binding) {
            settingsLimits.apply {
                primaryText = getString(R.string.account_limits_title)
                secondaryText = getString(R.string.account_limits_subtitle)
                onClick = {
                    navigator().goToKycLimits()
                }
            }

            settingsWalletId.apply {
                primaryText = getString(R.string.account_wallet_id_title)
                secondaryText = getString(R.string.account_wallet_id_subtitle)
                endImageResource = ImageResource.Local(R.drawable.ic_copy, null)
                onClick = {
                    analytics.logEvent(SettingsAnalytics.WalletIdCopyClicked)

                    if (::walletId.isInitialized) {
                        val clipboard =
                            requireActivity().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("walletId", walletId)
                        clipboard.setPrimaryClip(clip)
                        BlockchainSnackbar.make(
                            binding.root,
                            getString(R.string.copied_to_clipboard),
                            type = SnackbarType.Success
                        ).show()
                        analytics.logEvent(SettingsAnalytics.WalletIdCopyCopied)
                    } else {
                        BlockchainSnackbar.make(
                            binding.root,
                            getString(R.string.account_wallet_id_copy_error),
                            type = SnackbarType.Error
                        ).show()
                    }
                }
            }

            settingsCurrency.apply {
                primaryText = getString(R.string.account_currency_title)
                onClick = {
                    model.process(AccountIntent.LoadFiatList)
                }
            }

            settingsExchange.apply {
                primaryText = getString(R.string.account_exchange_title)
                onClick = {
                    model.process(AccountIntent.LoadExchange)
                }
            }

            settingsWalletConnect.apply {

                primaryText = getString(R.string.account_wallet_connect)
                onClick = {
                    navigator().goToWalletConnect()
                    analytics.logEvent(
                        WalletConnectAnalytics.ConnectedDappsListClicked(
                            origin = LaunchOrigin.SETTINGS
                        )
                    )
                }
            }

            settingsAirdrops.apply {
                primaryText = getString(R.string.account_airdrops_title)
                onClick = {
                    navigator().goToAirdrops()
                }
            }
            settingsAddresses.apply {
                primaryText = getString(R.string.account_addresses_title)
                onClick = {
                    navigator().goToAddresses()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.process(AccountIntent.LoadAccountInformation)
        model.process(AccountIntent.LoadExchangeInformation)
    }

    override fun render(newState: AccountState) {
        if (newState.accountInformation != null) {
            renderWalletInformation(newState.accountInformation)
        }

        if (newState.viewToLaunch != ViewToLaunch.None) {
            renderViewToLaunch(newState)
        }

        renderExchangeInformation(newState.exchangeLinkingState)
        renderErrorState(newState.errorState)
    }

    private fun renderExchangeInformation(exchangeLinkingState: ExchangeLinkingState) =
        when (exchangeLinkingState) {
            ExchangeLinkingState.UNKNOWN,
            ExchangeLinkingState.NOT_LINKED -> {
                binding.settingsExchange.secondaryText = getString(R.string.account_exchange_not_connected)
            }
            ExchangeLinkingState.LINKED -> {
                with(binding.settingsExchange) {
                    secondaryText = null
                    tags = listOf(TagViewState(getString(R.string.account_exchange_connected), TagType.Success()))
                }
            }
        }

    private fun renderErrorState(error: AccountError) =
        when (error) {
            AccountError.ACCOUNT_INFO_FAIL -> {
                showErrorSnackbar(R.string.account_load_info_error)
            }
            AccountError.FIAT_LIST_FAIL -> {
                showErrorSnackbar(R.string.account_load_fiat_error)
            }
            AccountError.ACCOUNT_FIAT_UPDATE_FAIL -> {
                showErrorSnackbar(R.string.account_fiat_update_error)
            }
            AccountError.EXCHANGE_INFO_FAIL -> {
                showErrorSnackbar(R.string.account_exchange_info_error)
            }
            AccountError.EXCHANGE_LOAD_FAIL -> {
                showErrorSnackbar(R.string.account_load_exchange_error)
            }
            AccountError.NONE -> {
                // do nothing
            }
        }

    private fun showErrorSnackbar(@StringRes message: Int) {
        BlockchainSnackbar.make(
            binding.root,
            getString(message),
            type = SnackbarType.Error
        ).show()
    }

    private fun renderWalletInformation(accountInformation: AccountInformation) {
        walletId = accountInformation.walletId

        binding.settingsCurrency.apply {
            secondaryText = accountInformation.userCurrency.nameWithSymbol()
        }
    }

    private fun renderViewToLaunch(newState: AccountState) {
        when (val view = newState.viewToLaunch) {
            is ViewToLaunch.CurrencySelection -> {
                showBottomSheet(
                    CurrencySelectionSheet.newInstance(
                        currencies = view.currencyList,
                        selectedCurrency = view.selectedCurrency,
                        currencySelectionType = CurrencySelectionSheet.Companion.CurrencySelectionType.DISPLAY_CURRENCY
                    )
                )
            }
            is ViewToLaunch.ExchangeLink -> {
                when (view.exchangeLinkingState) {
                    ExchangeLinkingState.UNKNOWN,
                    ExchangeLinkingState.NOT_LINKED -> {
                        showBottomSheet(
                            ExchangeConnectionSheet.newInstance(
                                ErrorBottomDialog.Content(
                                    title = getString(R.string.account_exchange_connect_title),
                                    description = getString(R.string.account_exchange_connect_subtitle),
                                    ctaButtonText = R.string.common_connect,
                                    icon = R.drawable.ic_exchange_logo
                                ),
                                tags = emptyList(),
                                primaryCtaClick = {
                                    navigator().goToExchange()
                                }
                            )
                        )
                    }
                    ExchangeLinkingState.LINKED -> {
                        showBottomSheet(
                            ExchangeConnectionSheet.newInstance(
                                ErrorBottomDialog.Content(
                                    title = getString(R.string.account_exchange_connected_title),
                                    ctaButtonText = R.string.account_exchange_connected_cta,
                                    dismissText = R.string.contact_support,
                                    icon = R.drawable.ic_exchange_logo
                                ),
                                listOf(TagViewState(getString(R.string.account_exchange_connected), TagType.Success())),
                                // TODO this will be changed to support the Exchange app?
                                primaryCtaClick = {
                                    requireActivity().launchUrlInBrowser(BuildConfig.PIT_LAUNCHING_URL)
                                },
                                secondaryCtaClick = {
                                    requireActivity().launchUrlInBrowser(URL_THE_PIT_LAUNCH_SUPPORT)
                                }
                            )
                        )
                    }
                }
            }
            ViewToLaunch.None -> {
                // do nothing
            }
        }
        model.process(AccountIntent.ResetViewState)
    }

    override fun onCurrencyChanged(currency: FiatCurrency) {
        model.process(AccountIntent.UpdateFiatCurrency(currency))
    }

    override fun onSheetClosed() {
        // do nothing
    }

    override fun onDestroy() {
        super.onDestroy()
        compositeDisposable.clear()
    }

    companion object {
        fun newInstance() = AccountFragment()
    }
}
