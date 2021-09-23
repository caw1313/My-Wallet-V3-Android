package com.blockchain.coincore.eth

import com.blockchain.nabu.datamanagers.TransactionError
import com.blockchain.preferences.WalletStatus
import info.blockchain.balance.CryptoCurrency
import info.blockchain.balance.CryptoValue
import info.blockchain.balance.Money
import info.blockchain.balance.Money.Companion.max
import info.blockchain.wallet.api.data.FeeOptions
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.kotlin.Singles
import org.web3j.crypto.RawTransaction
import org.web3j.utils.Convert
import com.blockchain.coincore.AssetAction
import com.blockchain.coincore.CryptoAddress
import com.blockchain.coincore.FeeInfo
import com.blockchain.coincore.FeeLevel
import com.blockchain.coincore.FeeSelection
import com.blockchain.coincore.PendingTx
import com.blockchain.coincore.TxConfirmation
import com.blockchain.coincore.TxConfirmationValue
import com.blockchain.coincore.TxResult
import com.blockchain.coincore.TxValidationFailure
import com.blockchain.coincore.ValidationState
import com.blockchain.coincore.impl.txEngine.OnChainTxEngineBase
import com.blockchain.coincore.toUserFiat
import com.blockchain.coincore.updateTxValidity
import piuk.blockchain.androidcore.data.ethereum.EthDataManager
import piuk.blockchain.androidcore.data.fees.FeeDataManager
import piuk.blockchain.androidcore.utils.extensions.then
import java.math.BigDecimal
import java.math.BigInteger

open class EthOnChainTxEngine(
    private val ethDataManager: EthDataManager,
    private val feeManager: FeeDataManager,
    walletPreferences: WalletStatus,
    requireSecondPassword: Boolean
) : OnChainTxEngineBase(
    requireSecondPassword,
    walletPreferences
) {

    override fun assertInputsValid() {
        check(txTarget is CryptoAddress)
        check((txTarget as CryptoAddress).asset == CryptoCurrency.ETHER)
        check(sourceAsset == CryptoCurrency.ETHER)
    }

    override fun doInitialiseTx(): Single<PendingTx> =
        Single.just(
            PendingTx(
                amount = CryptoValue.zero(sourceAsset),
                totalBalance = CryptoValue.zero(sourceAsset),
                availableBalance = CryptoValue.zero(sourceAsset),
                feeForFullAvailable = CryptoValue.zero(sourceAsset),
                feeAmount = CryptoValue.zero(sourceAsset),
                feeSelection = FeeSelection(
                    selectedLevel = mapSavedFeeToFeeLevel(fetchDefaultFeeLevel(sourceAsset)),
                    availableLevels = AVAILABLE_FEE_LEVELS,
                    asset = sourceAsset
                ),
                selectedFiat = userFiat
            )
        )

    override fun doBuildConfirmations(pendingTx: PendingTx): Single<PendingTx> =
        Single.just(
            pendingTx.copy(
                confirmations = listOfNotNull(
                    TxConfirmationValue.From(sourceAccount, sourceAsset),
                    TxConfirmationValue.To(
                        txTarget, AssetAction.Send, sourceAccount
                    ),
                    TxConfirmationValue.CompoundNetworkFee(
                        sendingFeeInfo = if (!pendingTx.feeAmount.isZero) {
                            FeeInfo(
                                pendingTx.feeAmount,
                                pendingTx.feeAmount.toUserFiat(exchangeRates),
                                sourceAsset
                            )
                        } else null,
                        feeLevel = pendingTx.feeSelection.selectedLevel
                    ),
                    TxConfirmationValue.Total(
                        totalWithFee = (pendingTx.amount as CryptoValue).plus(
                            pendingTx.feeAmount as CryptoValue
                        ),
                        exchange = pendingTx.amount.toUserFiat(exchangeRates)
                            .plus(pendingTx.feeAmount.toUserFiat(exchangeRates))
                    ),
                    TxConfirmationValue.Description()
                )
            )
        )

    private fun absoluteFees(): Single<Map<FeeLevel, CryptoValue>> =
        feeOptions().map {
            val gasLimit = it.gasLimit
            mapOf(
                FeeLevel.None to CryptoValue.zero(CryptoCurrency.ETHER),
                FeeLevel.Regular to getValueForFeeLevel(gasLimit, it.regularFee),
                FeeLevel.Priority to getValueForFeeLevel(gasLimit, it.priorityFee),
                FeeLevel.Custom to getValueForFeeLevel(gasLimit, it.priorityFee)
            )
        }

    private fun getValueForFeeLevel(gasLimitContract: Long, feeLevel: Long) =
        CryptoValue.fromMinor(
            CryptoCurrency.ETHER,
            Convert.toWei(
                BigDecimal.valueOf(gasLimitContract * feeLevel),
                Convert.Unit.GWEI
            )
        )

    private fun FeeOptions.mapFeeLevel(feeLevel: FeeLevel) =
        when (feeLevel) {
            FeeLevel.None -> 0L
            FeeLevel.Regular -> regularFee
            FeeLevel.Priority -> priorityFee
            FeeLevel.Custom -> priorityFee
        }

    private fun feeOptions(): Single<FeeOptions> =
        feeManager.ethFeeOptions.firstOrError()

    override fun doUpdateAmount(amount: Money, pendingTx: PendingTx): Single<PendingTx> {
        require(amount is CryptoValue)
        require(amount.currency == sourceAsset)

        return Singles.zip(
            sourceAccount.accountBalance.map { it as CryptoValue },
            sourceAccount.actionableBalance.map { it as CryptoValue },
            absoluteFees()
        ) { total, available, feeLevels ->
            val fees = feeLevels[pendingTx.feeSelection.selectedLevel] ?: CryptoValue.zero(sourceAsset)

            pendingTx.copy(
                amount = amount,
                totalBalance = total,
                availableBalance = max(available - fees, CryptoValue.zero(sourceAsset)) as CryptoValue,
                feeForFullAvailable = fees,
                feeAmount = fees,
                feeSelection = pendingTx.feeSelection.copy(
                    feesForLevels = feeLevels
                )
            )
        }
    }

    override fun doValidateAmount(pendingTx: PendingTx): Single<PendingTx> =
        validateAmounts(pendingTx)
            .then { validateSufficientFunds(pendingTx) }
            .updateTxValidity(pendingTx)

    // We can make some assumptions here over the previous impl;
    // 1. a CryptAddress object will be self-validating, so we need not check that it's valid
    override fun doValidateAll(pendingTx: PendingTx): Single<PendingTx> =
        validateAmounts(pendingTx)
            .then { validateSufficientFunds(pendingTx) }
            .then { validateNoPendingTx() }
            .updateTxValidity(pendingTx)

    override fun doExecute(pendingTx: PendingTx, secondPassword: String): Single<TxResult> =
        createTransaction(pendingTx)
            .flatMap {
                ethDataManager.signEthTransaction(it, secondPassword)
            }
            .flatMap { ethDataManager.pushTx(it) }
            .flatMap { hash ->
                pendingTx.getOption<TxConfirmationValue.Description>(TxConfirmation.DESCRIPTION)?.let { notes ->
                    ethDataManager.updateTransactionNotes(hash, notes.text)
                }?.toSingle {
                    hash
                } ?: Single.just(hash)
            }.onErrorResumeNext {
                Single.error(TransactionError.ExecutionFailed)
            }.map {
                TxResult.HashedTxResult(it, pendingTx.amount)
            }

    private fun createTransaction(pendingTx: PendingTx): Single<RawTransaction> {
        val targetAddress = txTarget as CryptoAddress

        return Singles.zip(
            ethDataManager.getNonce(),
            ethDataManager.isContractAddress(targetAddress.address),
            feeOptions()
        ).map { (nonce, isContract, fees) ->
            ethDataManager.createEthTransaction(
                nonce = nonce,
                to = targetAddress.address,
                gasPriceWei = fees.gasPrice(pendingTx.feeSelection.selectedLevel),
                gasLimitGwei = fees.getGasLimit(isContract),
                weiValue = pendingTx.amount.toBigInteger()
            )
        }
    }

    // TODO: Have FeeOptions deal with this conversion
    private fun FeeOptions.gasPrice(feeLevel: FeeLevel): BigInteger =
        Convert.toWei(
            BigDecimal.valueOf(this.mapFeeLevel(feeLevel)),
            Convert.Unit.GWEI
        ).toBigInteger()

    private fun FeeOptions.getGasLimit(isContract: Boolean): BigInteger =
        BigInteger.valueOf(
            if (isContract) gasLimitContract else gasLimit
        )

    private fun validateAmounts(pendingTx: PendingTx): Completable =
        Completable.fromCallable {
            if (pendingTx.amount <= CryptoValue.zero(sourceAsset)) {
                throw TxValidationFailure(ValidationState.INVALID_AMOUNT)
            }
        }

    private fun validateSufficientFunds(pendingTx: PendingTx): Completable =
        Singles.zip(
            sourceAccount.actionableBalance,
            absoluteFees()
        ) { balance: Money, feeLevels ->
            val fee = feeLevels[pendingTx.feeSelection.selectedLevel] ?: CryptoValue.zero(sourceAsset)

            if (fee + pendingTx.amount > balance) {
                throw TxValidationFailure(ValidationState.INSUFFICIENT_FUNDS)
            } else {
                true
            }
        }.ignoreElement()

    private fun validateNoPendingTx() =
        ethDataManager.isLastTxPending()
            .flatMapCompletable { hasUnconfirmed: Boolean ->
                if (hasUnconfirmed) {
                    Completable.error(TxValidationFailure(ValidationState.HAS_TX_IN_FLIGHT))
                } else {
                    Completable.complete()
                }
            }

    companion object {
        private val AVAILABLE_FEE_LEVELS = setOf(FeeLevel.Regular, FeeLevel.Priority)
    }
}