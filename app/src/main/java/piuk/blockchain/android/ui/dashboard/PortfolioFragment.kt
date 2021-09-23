package piuk.blockchain.android.ui.dashboard

import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blockchain.featureflags.GatedFeature
import com.blockchain.featureflags.InternalFeatureFlagApi
import com.blockchain.koin.scopedInject
import com.blockchain.notifications.analytics.AnalyticsEvents
import com.blockchain.notifications.analytics.LaunchOrigin
import com.blockchain.preferences.CurrencyPrefs
import com.blockchain.preferences.DashboardPrefs
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import info.blockchain.balance.AssetInfo
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.kotlin.subscribeBy
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import piuk.blockchain.android.R
import piuk.blockchain.android.campaign.CampaignType
import piuk.blockchain.android.campaign.blockstackCampaignName
import com.blockchain.coincore.AssetAction
import com.blockchain.coincore.BlockchainAccount
import com.blockchain.coincore.Coincore
import com.blockchain.coincore.CryptoAccount
import com.blockchain.coincore.FiatAccount
import com.blockchain.coincore.InterestAccount
import com.blockchain.coincore.SingleAccount
import com.blockchain.coincore.impl.CustodialTradingAccount
import com.blockchain.nabu.Feature
import com.blockchain.nabu.datamanagers.NabuUserIdentity
import io.reactivex.rxjava3.kotlin.zipWith
import io.reactivex.rxjava3.schedulers.Schedulers
import piuk.blockchain.android.databinding.FragmentPortfolioBinding
import piuk.blockchain.android.simplebuy.BuySellClicked
import piuk.blockchain.android.simplebuy.BuySellType
import piuk.blockchain.android.simplebuy.SimpleBuyAnalytics
import piuk.blockchain.android.simplebuy.SimpleBuyCancelOrderBottomSheet
import piuk.blockchain.android.ui.airdrops.AirdropStatusSheet
import piuk.blockchain.android.ui.customviews.BlockchainListDividerDecor
import piuk.blockchain.android.ui.customviews.KycBenefitsBottomSheet
import piuk.blockchain.android.ui.customviews.VerifyIdentityNumericBenefitItem
import piuk.blockchain.android.ui.dashboard.adapter.PortfolioDelegateAdapter
import piuk.blockchain.android.ui.dashboard.announcements.AnnouncementCard
import piuk.blockchain.android.ui.dashboard.announcements.AnnouncementHost
import piuk.blockchain.android.ui.dashboard.announcements.AnnouncementList
import piuk.blockchain.android.ui.dashboard.assetdetails.AssetDetailsAnalytics
import piuk.blockchain.android.ui.dashboard.assetdetails.AssetDetailsFlow
import piuk.blockchain.android.ui.dashboard.assetdetails.assetActionEvent
import piuk.blockchain.android.ui.dashboard.assetdetails.fiatAssetAction
import piuk.blockchain.android.ui.dashboard.model.CheckBackupStatus
import piuk.blockchain.android.ui.dashboard.model.ClearAnnouncement
import piuk.blockchain.android.ui.dashboard.model.ClearBottomSheet
import piuk.blockchain.android.ui.dashboard.model.CryptoAssetState
import piuk.blockchain.android.ui.dashboard.model.PortfolioIntent
import piuk.blockchain.android.ui.dashboard.model.DashboardItem
import piuk.blockchain.android.ui.dashboard.model.PortfolioModel
import piuk.blockchain.android.ui.dashboard.model.DashboardNavigationAction
import piuk.blockchain.android.ui.dashboard.model.PortfolioState
import piuk.blockchain.android.ui.dashboard.model.GetAvailableAssets
import piuk.blockchain.android.ui.dashboard.model.LaunchAssetDetailsFlow
import piuk.blockchain.android.ui.dashboard.model.LaunchBankTransferFlow
import piuk.blockchain.android.ui.dashboard.model.LaunchInterestDepositFlow
import piuk.blockchain.android.ui.dashboard.model.LaunchInterestWithdrawFlow
import piuk.blockchain.android.ui.dashboard.model.LaunchSendFlow
import piuk.blockchain.android.ui.dashboard.model.LinkBankNavigationAction
import piuk.blockchain.android.ui.dashboard.model.LinkablePaymentMethodsForAction
import piuk.blockchain.android.ui.dashboard.model.RefreshAllBalancesIntent
import piuk.blockchain.android.ui.dashboard.model.ResetPortfolioNavigation
import piuk.blockchain.android.ui.dashboard.model.ShowAnnouncement
import piuk.blockchain.android.ui.dashboard.model.ShowBankLinkingSheet
import piuk.blockchain.android.ui.dashboard.model.ShowPortfolioSheet
import piuk.blockchain.android.ui.dashboard.model.ShowFiatAssetDetails
import piuk.blockchain.android.ui.dashboard.model.UpdateSelectedCryptoAccount
import piuk.blockchain.android.ui.dashboard.sheets.FiatFundsDetailSheet
import piuk.blockchain.android.ui.dashboard.sheets.ForceBackupForSendSheet
import piuk.blockchain.android.ui.dashboard.sheets.LinkBankMethodChooserBottomSheet
import piuk.blockchain.android.ui.dashboard.sheets.WireTransferAccountDetailsBottomSheet
import piuk.blockchain.android.ui.home.HomeScreenMviFragment
import piuk.blockchain.android.ui.home.MainActivity
import piuk.blockchain.android.ui.interest.InterestSummarySheet
import piuk.blockchain.android.ui.linkbank.BankAuthActivity
import piuk.blockchain.android.ui.linkbank.BankAuthSource
import piuk.blockchain.android.ui.recurringbuy.onboarding.RecurringBuyOnboardingActivity
import piuk.blockchain.android.ui.resources.AssetResources
import piuk.blockchain.android.ui.sell.BuySellFragment
import piuk.blockchain.android.ui.settings.BankLinkingHost
import piuk.blockchain.android.ui.transactionflow.DialogFlow
import piuk.blockchain.android.ui.transactionflow.TransactionFlow
import piuk.blockchain.android.ui.transactionflow.TransactionLauncher
import piuk.blockchain.android.ui.transactionflow.analytics.SwapAnalyticsEvents
import piuk.blockchain.android.ui.transfer.analytics.TransferAnalyticsEvent
import piuk.blockchain.android.util.launchUrlInBrowser
import piuk.blockchain.android.util.visibleIf
import piuk.blockchain.androidcore.data.events.ActionEvent
import piuk.blockchain.androidcore.data.rxjava.RxBus
import piuk.blockchain.androidcore.utils.helperfunctions.unsafeLazy
import timber.log.Timber

class EmptyDashboardItem : DashboardItem

class PortfolioFragment :
    HomeScreenMviFragment<PortfolioModel, PortfolioIntent, PortfolioState, FragmentPortfolioBinding>(),
    ForceBackupForSendSheet.Host,
    FiatFundsDetailSheet.Host,
    KycBenefitsBottomSheet.Host,
    DialogFlow.FlowHost,
    AssetDetailsFlow.AssetDetailsHost,
    InterestSummarySheet.Host,
    BankLinkingHost {

    override val model: PortfolioModel by scopedInject()
    private val announcements: AnnouncementList by scopedInject()
    private val analyticsReporter: BalanceAnalyticsReporter by scopedInject()
    private val currencyPrefs: CurrencyPrefs by inject()
    private val dashboardPrefs: DashboardPrefs by inject()
    private val coincore: Coincore by scopedInject()
    private val assetResources: AssetResources by inject()
    private val txLauncher: TransactionLauncher by inject()
    private val gatedFeatures: InternalFeatureFlagApi by inject()
    private val userIdentity: NabuUserIdentity by scopedInject()

    private val theAdapter: PortfolioDelegateAdapter by lazy {
        PortfolioDelegateAdapter(
            prefs = get(),
            onCardClicked = { onAssetClicked(it) },
            analytics = get(),
            onFundsItemClicked = { onFundsClicked(it) },
            assetResources = assetResources
        )
    }

    private val theLayoutManager: RecyclerView.LayoutManager by unsafeLazy {
        SafeLayoutManager(requireContext())
    }

    private val displayList = mutableListOf<DashboardItem>()

    private val compositeDisposable = CompositeDisposable()
    private val rxBus: RxBus by inject()

    private val actionEvent by unsafeLazy {
        rxBus.register(ActionEvent::class.java)
    }

    private val flowToLaunch: AssetAction? by unsafeLazy {
        arguments?.getSerializable(FLOW_TO_LAUNCH) as? AssetAction
    }

    private val flowCurrency: String? by unsafeLazy {
        arguments?.getString(FLOW_FIAT_CURRENCY)
    }

    private var state: PortfolioState? =
        null // Hold the 'current' display state, to enable optimising of state updates

    @UiThread
    override fun render(newState: PortfolioState) {
        try {
            doRender(newState)
        } catch (e: Throwable) {
            Timber.e("Error rendering: $e")
        }
    }

    @UiThread
    private fun doRender(newState: PortfolioState) {

        binding.swipe.isRefreshing = false
        updateDisplayList(newState)

        if (this.state?.dashboardNavigationAction != newState.dashboardNavigationAction) {
            handleStateNavigation(newState)
        }

        // Update/show dialog flow
        if (state?.activeFlow != newState.activeFlow) {
            state?.activeFlow?.let {
                clearBottomSheet()
            }

            newState.activeFlow?.let {
                if (it is TransactionFlow) {
                    txLauncher.startFlow(
                        activity = requireActivity(),
                        fragmentManager = childFragmentManager,
                        action = it.txAction,
                        flowHost = this@PortfolioFragment,
                        sourceAccount = it.txSource,
                        target = it.txTarget,
                        compositeDisposable = compositeDisposable
                    )
                } else {
                    it.startFlow(childFragmentManager, this)
                }
            }
        }

        // Update/show announcement
        if (this.state?.announcement != newState.announcement) {
            showAnnouncement(newState.announcement)
        }

        updateAnalytics(this.state, newState)

        binding.dashboardProgress.visibleIf { newState.hasLongCallInProgress }

        this.state = newState
    }

    private fun updateDisplayList(newState: PortfolioState) {
        with(displayList) {
            val newList = mutableListOf<DashboardItem>()
            if (isEmpty()) {
                newList.add(IDX_CARD_ANNOUNCE, EmptyDashboardItem())
                newList.add(IDX_CARD_BALANCE, newState)
                newList.add(IDX_FUNDS_BALANCE, EmptyDashboardItem()) // Placeholder for funds
            } else {
                newList.add(IDX_CARD_ANNOUNCE, get(IDX_CARD_ANNOUNCE))
                newList.add(IDX_CARD_BALANCE, newState)
                if (newState.fiatAssets.fiatAccounts.isNotEmpty()) {
                    newList.add(IDX_FUNDS_BALANCE, newState.fiatAssets)
                } else {
                    newList.add(IDX_FUNDS_BALANCE, get(IDX_FUNDS_BALANCE))
                }
            }
            // Add assets, sorted by fiat balance then alphabetically
            val assets = newState.assets.values.sortedWith(
                compareByDescending<CryptoAssetState> { it.fiatBalance?.toBigInteger() }
                    .thenBy { it.currency.name }
            )
            if (gatedFeatures.isFeatureEnabled(GatedFeature.NEW_SPLIT_DASHBOARD)) {
                val hasBalanceOrIsLoading = assets.any { it.accountBalance?.total?.isPositive == true } ||
                    newState.hasLongCallInProgress
                binding.portfolioLayoutGroup.visibleIf { hasBalanceOrIsLoading }
                binding.emptyPortfolioGroup.visibleIf { !hasBalanceOrIsLoading }
            }

            newList.addAll(assets)
            clear()
            addAll(newList)
        }
        theAdapter.notifyDataSetChanged()
    }

    private fun handleStateNavigation(state: PortfolioState) {
        when {
            state.dashboardNavigationAction?.isBottomSheet() == true -> {
                handleBottomSheet(state)
                model.process(ResetPortfolioNavigation)
            }
            state.dashboardNavigationAction is LinkBankNavigationAction -> {
                startBankLinking(state.dashboardNavigationAction)
            }
        }
    }

    private fun startBankLinking(action: DashboardNavigationAction) {
        (action as? DashboardNavigationAction.LinkBankWithPartner)?.let {
            startActivityForResult(
                BankAuthActivity.newInstance(
                    action.linkBankTransfer,
                    when (it.assetAction) {
                        AssetAction.FiatDeposit -> {
                            BankAuthSource.DEPOSIT
                        }
                        AssetAction.Withdraw -> {
                            BankAuthSource.WITHDRAW
                        }
                        else -> {
                            throw IllegalStateException("Attempting to link from an unsupported action")
                        }
                    },
                    requireContext()
                ),
                BankAuthActivity.LINK_BANK_REQUEST_CODE
            )
        }
    }

    private fun handleBottomSheet(state: PortfolioState) {
        showBottomSheet(
            when (state.dashboardNavigationAction) {
                DashboardNavigationAction.StxAirdropComplete -> AirdropStatusSheet.newInstance(
                    blockstackCampaignName
                )
                DashboardNavigationAction.BackUpBeforeSend -> ForceBackupForSendSheet.newInstance(
                    state.backupSheetDetails!!
                )
                DashboardNavigationAction.SimpleBuyCancelOrder -> {
                    analytics.logEvent(SimpleBuyAnalytics.BANK_DETAILS_CANCEL_PROMPT)
                    SimpleBuyCancelOrderBottomSheet.newInstance(true)
                }
                DashboardNavigationAction.FiatFundsDetails -> FiatFundsDetailSheet.newInstance(
                    state.selectedFiatAccount
                        ?: return
                )
                DashboardNavigationAction.LinkOrDeposit -> {
                    state.selectedFiatAccount?.let {
                        WireTransferAccountDetailsBottomSheet.newInstance(it)
                    } ?: WireTransferAccountDetailsBottomSheet.newInstance()
                }
                DashboardNavigationAction.PaymentMethods -> {
                    state.linkablePaymentMethodsForAction?.let {
                        LinkBankMethodChooserBottomSheet.newInstance(
                            it
                        )
                    }
                }
                DashboardNavigationAction.FiatFundsNoKyc -> showFiatFundsKyc()
                DashboardNavigationAction.InterestSummary -> InterestSummarySheet.newInstance(
                    state.selectedCryptoAccount!!,
                    state.selectedAsset!!
                )
                else -> null
            })
    }

    private fun showFiatFundsKyc(): BottomSheetDialogFragment {
        val currencyIcon = when (currencyPrefs.selectedFiatCurrency) {
            "EUR" -> R.drawable.ic_funds_euro
            "GBP" -> R.drawable.ic_funds_gbp
            else -> R.drawable.ic_funds_usd // show dollar if currency isn't selected
        }

        return KycBenefitsBottomSheet.newInstance(
            KycBenefitsBottomSheet.BenefitsDetails(
                title = getString(R.string.fiat_funds_no_kyc_announcement_title),
                description = getString(R.string.fiat_funds_no_kyc_announcement_description),
                listOfBenefits = listOf(
                    VerifyIdentityNumericBenefitItem(
                        getString(R.string.fiat_funds_no_kyc_step_1_title),
                        getString(R.string.fiat_funds_no_kyc_step_1_description)
                    ),
                    VerifyIdentityNumericBenefitItem(
                        getString(R.string.fiat_funds_no_kyc_step_2_title),
                        getString(R.string.fiat_funds_no_kyc_step_2_description)
                    ),
                    VerifyIdentityNumericBenefitItem(
                        getString(R.string.fiat_funds_no_kyc_step_3_title),
                        getString(R.string.fiat_funds_no_kyc_step_3_description)
                    )
                ),
                icon = currencyIcon
            )
        )
    }

    private fun showAnnouncement(card: AnnouncementCard?) {
        displayList[IDX_CARD_ANNOUNCE] = card ?: EmptyDashboardItem()
        theAdapter.notifyItemChanged(IDX_CARD_ANNOUNCE)
    }

    private fun updateAnalytics(oldState: PortfolioState?, newState: PortfolioState) {
        analyticsReporter.updateFiatTotal(newState.fiatBalance)

        newState.assets.forEach { (asset, state) ->
            val newBalance = state.accountBalance?.total
            if (newBalance != null && newBalance != oldState?.assets?.get(asset)?.accountBalance?.total) {
                // If we have the full set, this will fire
                analyticsReporter.gotAssetBalance(asset, newBalance)
            }
        }
    }

    override fun onBackPressed(): Boolean = false

    override fun initBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentPortfolioBinding =
        FragmentPortfolioBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        analytics.logEvent(AnalyticsEvents.Dashboard)

        setupSwipeRefresh()
        setupRecycler()
        setupCtaButtons()

        if (flowToLaunch != null && flowCurrency != null) {
            compositeDisposable += coincore.fiatAssets.accountGroup().subscribeBy(
                onSuccess = { fiatGroup ->
                    val selectedAccount = fiatGroup.accounts.first {
                        (it as FiatAccount).fiatCurrency == flowCurrency
                    }

                    when (flowToLaunch) {
                        AssetAction.FiatDeposit -> model.process(
                            LaunchBankTransferFlow(
                                selectedAccount, AssetAction.FiatDeposit, false
                            )
                        )
                        AssetAction.Withdraw -> model.process(
                            LaunchBankTransferFlow(
                                selectedAccount, AssetAction.Withdraw, false
                            )
                        )
                        else -> throw IllegalStateException("Unsupported flow launch for action $flowToLaunch")
                    }
                },
                onError = {
                    // TODO
                }
            )
        }
    }

    private fun setupCtaButtons() {
        with(binding) {
            buyCryptoButton.setOnClickListener { navigator().launchBuySell() }
            receiveDepositButton.apply {
                leftButton.setOnClickListener { navigator().launchReceive() }
                rightButton.setOnClickListener { handleDepositClicked() }
            }
        }
    }

    private fun handleDepositClicked() {
        compositeDisposable += userIdentity.isEligibleFor(Feature.SimpleBuy)
            .zipWith(coincore.fiatAssets.accountGroup().toSingle())
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeBy(
                onSuccess = { (isEligible, fiatGroup) ->
                    model.process(
                        if (isEligible) {
                            val selectedAccount = fiatGroup.accounts.first {
                                (it as FiatAccount).fiatCurrency == currencyPrefs.selectedFiatCurrency
                            }
                            LaunchBankTransferFlow(
                                selectedAccount, AssetAction.FiatDeposit, false
                            )
                        } else {
                            ShowPortfolioSheet(DashboardNavigationAction.FiatFundsNoKyc)
                        }
                    )
                },
                onError = {
                    Timber.e(it)
                    model.process(ShowPortfolioSheet(DashboardNavigationAction.FiatFundsNoKyc))
                }
            )
    }

    private fun setupRecycler() {
        binding.recyclerView.apply {
            layoutManager = theLayoutManager
            adapter = theAdapter

            addItemDecoration(BlockchainListDividerDecor(requireContext()))
        }
        theAdapter.items = displayList
    }

    private fun setupSwipeRefresh() {
        with(binding) {
            swipe.setOnRefreshListener { model.process(RefreshAllBalancesIntent) }

            // Configure the refreshing colors
            swipe.setColorSchemeResources(
                R.color.blue_800,
                R.color.blue_600,
                R.color.blue_400,
                R.color.blue_200
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (isHidden) return
        compositeDisposable += actionEvent.subscribe {
            initOrUpdateAssets()
        }

        (activity as? MainActivity)?.let {
            compositeDisposable += it.refreshAnnouncements.observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    if (announcements.enable()) {
                        announcements.checkLatest(announcementHost, compositeDisposable)
                    }
                }
        }

        announcements.checkLatest(announcementHost, compositeDisposable)

        initOrUpdateAssets()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            model.process(RefreshAllBalancesIntent)
        }
    }

    private fun initOrUpdateAssets() {
        if (displayList.isEmpty()) {
            model.process(GetAvailableAssets)
        } else {
            model.process(RefreshAllBalancesIntent)
        }
    }

    override fun onPause() {
        // Save the sort order for use elsewhere, so that other asset lists can have the same
        // ordering. Storing this through prefs is a bit of a hack, um, "optimisation" - we don't
        // want to be getting all the balances every time we want to display assets in balance order.
        // TODO This UI is due for a re-write soon, at which point this ordering should be managed better
        dashboardPrefs.dashboardAssetOrder = displayList.filterIsInstance<CryptoAssetState>()
            .map { it.currency.displayTicker }

        compositeDisposable.clear()
        rxBus.unregister(ActionEvent::class.java, actionEvent)
        super.onPause()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            MainActivity.SETTINGS_EDIT,
            MainActivity.ACCOUNT_EDIT -> model.process(RefreshAllBalancesIntent)
            BACKUP_FUNDS_REQUEST_CODE -> {
                state?.backupSheetDetails?.let {
                    model.process(CheckBackupStatus(it.account, it.action))
                }
            }
            BankAuthActivity.LINK_BANK_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    state?.selectedFiatAccount?.let { fiatAccount ->
                        (state?.dashboardNavigationAction as? DashboardNavigationAction.LinkBankWithPartner)?.let {
                            model.process(
                                LaunchBankTransferFlow(
                                    fiatAccount,
                                    it.assetAction,
                                    true
                                )
                            )
                        }
                    }
                }
            }
        }

        model.process(ResetPortfolioNavigation)
    }

    private fun onAssetClicked(asset: AssetInfo) {
        analytics.logEvent(assetActionEvent(AssetDetailsAnalytics.WALLET_DETAILS, asset))
        model.process(LaunchAssetDetailsFlow(asset))
    }

    private fun onFundsClicked(fiatAccount: FiatAccount) {
        analytics.logEvent(fiatAssetAction(AssetDetailsAnalytics.FIAT_DETAIL_CLICKED, fiatAccount.fiatCurrency))
        model.process(ShowFiatAssetDetails(fiatAccount))
    }

    private val announcementHost = object : AnnouncementHost {

        override val disposables: CompositeDisposable
            get() = compositeDisposable

        override fun showAnnouncementCard(card: AnnouncementCard) {
            model.process(ShowAnnouncement(card))
        }

        override fun dismissAnnouncementCard() {
            model.process(ClearAnnouncement)
        }

        override fun startKyc(campaignType: CampaignType) = navigator().launchKyc(campaignType)

        override fun startSwap() {
            analytics.logEvent(SwapAnalyticsEvents.SwapClickedEvent(LaunchOrigin.DASHBOARD_PROMO))
            navigator().launchSwap()
        }

        override fun startPitLinking() = navigator().launchThePitLinking()

        override fun startFundsBackup() = navigator().launchBackupFunds()

        override fun startSetup2Fa() = navigator().launchSetup2Fa()

        override fun startVerifyEmail() = navigator().launchVerifyEmail()

        override fun startEnableFingerprintLogin() = navigator().launchSetupFingerprintLogin()

        override fun startTransferCrypto() {
            analytics.logEvent(
                TransferAnalyticsEvent.TransferClicked(
                    origin = LaunchOrigin.DASHBOARD_PROMO, type = TransferAnalyticsEvent.AnalyticsTransferType.RECEIVE
                )
            )
            navigator().launchReceive()
        }

        override fun startStxReceivedDetail() =
            model.process(ShowPortfolioSheet(DashboardNavigationAction.StxAirdropComplete))

        override fun finishSimpleBuySignup() {
            navigator().resumeSimpleBuyKyc()
        }

        override fun startSimpleBuy(asset: AssetInfo) {
            navigator().launchSimpleBuy(asset)
        }

        override fun startBuy() {
            analytics.logEvent(
                BuySellClicked(
                    origin = LaunchOrigin.DASHBOARD_PROMO, type = BuySellType.BUY
                )
            )
            navigator().launchBuySell()
        }

        override fun startSell() {
            analytics.logEvent(
                BuySellClicked(
                    origin = LaunchOrigin.DASHBOARD_PROMO, type = BuySellType.SELL
                )
            )
            navigator().launchBuySell(BuySellFragment.BuySellViewType.TYPE_SELL)
        }

        override fun startSend() {
            analytics.logEvent(
                TransferAnalyticsEvent.TransferClicked(
                    origin = LaunchOrigin.DASHBOARD_PROMO, type = TransferAnalyticsEvent.AnalyticsTransferType.SEND
                )
            )
            navigator().launchSend()
        }

        override fun startInterestDashboard() {
            navigator().launchInterestDashboard(LaunchOrigin.DASHBOARD_PROMO)
        }

        override fun showFiatFundsKyc() {
            model.process(ShowPortfolioSheet(DashboardNavigationAction.FiatFundsNoKyc))
        }

        override fun showBankLinking() =
            model.process(ShowBankLinkingSheet())

        override fun openBrowserLink(url: String) =
            requireContext().launchUrlInBrowser(url)

        override fun startRecurringBuyUpsell() {
            startActivity(RecurringBuyOnboardingActivity.newInstance(requireActivity(), false))
        }
    }

    override fun onBankWireTransferSelected(currency: String) {
        state?.selectedFiatAccount?.let {
            model.process(ShowBankLinkingSheet(it))
        }
    }

    override fun startDepositFlow(fiatAccount: FiatAccount) {
        model.process(LaunchBankTransferFlow(fiatAccount, AssetAction.FiatDeposit, false))
    }

    override fun onLinkBankSelected(paymentMethodForAction: LinkablePaymentMethodsForAction) {
        state?.selectedFiatAccount?.let {
            if (paymentMethodForAction is LinkablePaymentMethodsForAction.LinkablePaymentMethodsForDeposit) {
                model.process(LaunchBankTransferFlow(it, AssetAction.FiatDeposit, true))
            } else if (paymentMethodForAction is LinkablePaymentMethodsForAction.LinkablePaymentMethodsForWithdraw) {
                model.process(LaunchBankTransferFlow(it, AssetAction.Withdraw, true))
            }
        }
    }

    override fun startBankTransferWithdrawal(fiatAccount: FiatAccount) {
        model.process(LaunchBankTransferFlow(fiatAccount, AssetAction.Withdraw, false))
    }

    override fun showFundsKyc() {
        model.process(ShowPortfolioSheet(DashboardNavigationAction.FiatFundsNoKyc))
    }

    override fun verificationCtaClicked() {
        navigator().launchKyc(CampaignType.FiatFunds)
    }

    // DialogBottomSheet.Host
    override fun onSheetClosed() {
        model.process(ClearBottomSheet)
    }

    override fun onFlowFinished() {
        model.process(ClearBottomSheet)
    }

    private fun launchSendFor(account: SingleAccount, action: AssetAction) {
        if (account is CustodialTradingAccount) {
            model.process(CheckBackupStatus(account, action))
        } else {
            model.process(LaunchSendFlow(account, action))
        }
    }

    override fun performAssetActionFor(action: AssetAction, account: BlockchainAccount) {
        clearBottomSheet()
        when (action) {
            AssetAction.Send -> launchSendFor(account as SingleAccount, action)
            else -> navigator().performAssetActionFor(action, account)
        }
    }

    override fun goToActivityFor(account: BlockchainAccount) =
        navigator().performAssetActionFor(AssetAction.ViewActivity, account)

    override fun goToInterestDeposit(toAccount: InterestAccount) {
        model.process(LaunchInterestDepositFlow(toAccount))
    }

    override fun goToInterestWithdraw(fromAccount: InterestAccount) {
        model.process(LaunchInterestWithdrawFlow(fromAccount))
    }

    override fun goToSummary(account: SingleAccount, asset: AssetInfo) {
        model.process(UpdateSelectedCryptoAccount(account, asset))
        model.process(ShowPortfolioSheet(DashboardNavigationAction.InterestSummary))
    }

    override fun goToSellFrom(account: CryptoAccount) {
        txLauncher.startFlow(
            activity = requireActivity(),
            sourceAccount = account,
            action = AssetAction.Sell,
            fragmentManager = childFragmentManager,
            flowHost = this@PortfolioFragment,
            compositeDisposable = compositeDisposable
        )
    }

    override fun goToInterestDashboard() {
        navigator().launchInterestDashboard(LaunchOrigin.CURRENCY_PAGE)
    }

    override fun goToBuy(asset: AssetInfo) {
        navigator().launchBuySell(BuySellFragment.BuySellViewType.TYPE_BUY, asset)
    }

    override fun startBackupForTransfer() {
        navigator().launchBackupFunds(this, BACKUP_FUNDS_REQUEST_CODE)
    }

    override fun startTransferFunds(account: SingleAccount, action: AssetAction) {
        model.process(LaunchSendFlow(account, action))
    }

    companion object {
        fun newInstance() = PortfolioFragment()

        fun newInstance(flowToLaunch: AssetAction?, fiatCurrency: String?) =
            PortfolioFragment().apply {
                arguments = Bundle().apply {
                    if (flowToLaunch != null && fiatCurrency != null) {
                        putSerializable(FLOW_TO_LAUNCH, flowToLaunch)
                        putString(FLOW_FIAT_CURRENCY, fiatCurrency)
                    }
                }
            }

        internal const val FLOW_TO_LAUNCH = "FLOW_TO_LAUNCH"
        internal const val FLOW_FIAT_CURRENCY = "FLOW_FIAT_CURRENCY"

        private const val IDX_CARD_ANNOUNCE = 0
        private const val IDX_CARD_BALANCE = 1
        private const val IDX_FUNDS_BALANCE = 2

        private const val BACKUP_FUNDS_REQUEST_CODE = 8265
    }
}

/**
 * supportsPredictiveItemAnimations = false to avoid crashes when computing changes.
 */
internal class SafeLayoutManager(context: Context) : LinearLayoutManager(context) {
    override fun supportsPredictiveItemAnimations() = false
}