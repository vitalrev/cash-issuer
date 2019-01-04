package com.r3.corda.finance.cash.issuer.client.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.finance.cash.issuer.common.flows.AbstractRedeemCash
import net.corda.core.contracts.Amount
import net.corda.core.contracts.PartyAndReference
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.ProgressTracker
import net.corda.finance.contracts.asset.AbstractCashSelection
import net.corda.finance.issuedBy
import java.util.*

@StartableByRPC
@StartableByService
class RedeemCash(val amount: Amount<Currency>, val issuer: Party) : AbstractRedeemCash() {

    companion object {
        object REDEEMING : ProgressTracker.Step("Redeeming cash.")
        object SEND_CASH_STATES : ProgressTracker.Step("Send cash states to issuer.")
        object SEND_AMOUNT: ProgressTracker.Step("Send amount to issuer.")
        object RECEIVE_FINALITY: ProgressTracker.Step("Receive finality transaction.")

        @JvmStatic
        fun tracker() = ProgressTracker(
                REDEEMING,
                SEND_CASH_STATES,
                SEND_AMOUNT,
                RECEIVE_FINALITY
        )
    }

    override val progressTracker: ProgressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {
        logger.info("Starting RedeemCash flow...")
        val builder = TransactionBuilder(notary = null)
        //get unconsumed cash states
        val exitStates = AbstractCashSelection
                .getInstance { serviceHub.jdbcSession().metaData }
                .unconsumedCashStatesForSpending(serviceHub, amount, setOf(issuer), builder.notary, builder.lockId, setOf())
        //exitStates.forEach { logger.info(it.state.data.toString()) }

        progressTracker.currentStep = REDEEMING
        val otherSession = initiateFlow(issuer)
        logger.info("Sending states to exit to $issuer")

        //sign tx
        progressTracker.currentStep = SEND_CASH_STATES
        subFlow(SendStateAndRefFlow(otherSession, exitStates))

        progressTracker.currentStep = SEND_AMOUNT
        otherSession.send(amount.issuedBy(PartyAndReference(issuer, OpaqueBytes.of(0))))

        subFlow(object : SignTransactionFlow(otherSession) {
            override fun checkTransaction(stx: SignedTransaction) = Unit
        })

        progressTracker.currentStep = RECEIVE_FINALITY
        return subFlow(ReceiveFinalityFlow(otherSession))
    }
}