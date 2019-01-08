package com.r3.corda.finance.cash.issuer.client.api.model

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.finance.cash.issuer.common.states.BankAccountState
import com.r3.corda.finance.cash.issuer.common.types.*
import com.r3.corda.finance.cash.issuer.client.helpers.getBigDecimalFromLong
import javafx.collections.ObservableList
import net.corda.core.contracts.ContractState
import net.corda.core.serialization.CordaSerializable
import net.corda.finance.contracts.asset.Cash.State
import java.math.BigDecimal
import java.time.Instant
import java.util.*


@CordaSerializable
@Suspendable
data class CashUIModel(
        val amount: BigDecimal,
        val amountlong: Long,
        val currency: String,
        val owner: String,
        val issuer: String
)
fun State.toUiModel(): CashUIModel {
    return CashUIModel(
            amount = getBigDecimalFromLong(amount.quantity),
            amountlong = amount.quantity,
            currency = amount.token.product.currencyCode,
            owner = owner.toString(),
            issuer = amount.token.issuer.party.toString()
    )
}

@CordaSerializable
@Suspendable
data class BankAccountUiModel(
        val owner: String,
        val internalAccountId: UUID,
        val externalAccountId: String,
        val accountName: String,
        val accountNumber: AccountNumber,
        val currency: Currency,
        val type: BankAccountType,
        val verified: Boolean,
        val lastUpdated: Instant
)
fun BankAccountState.toUiModel(): BankAccountUiModel {
    return BankAccountUiModel(
            owner.toString(),
            linearId.id,
            linearId.externalId!!,
            accountName,
            accountNumber,
            currency,
            type,
            verified,
            lastUpdated
    )
}

fun <T : ContractState, U : Any> ObservableList<T>.transform(block: (T) -> U) = map { block(it) }