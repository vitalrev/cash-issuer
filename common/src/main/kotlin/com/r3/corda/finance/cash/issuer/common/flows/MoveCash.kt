package com.r3.corda.finance.cash.issuer.common.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Amount
import net.corda.core.contracts.InsufficientBalanceException
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.flows.CashException
import java.util.*

/**
 * Simple move cash flow for demos.
 */
@StartableByRPC
@InitiatingFlow
class MoveCash(val recipient: Party, val amount: Amount<Currency>) : FlowLogic<SignedTransaction>() {

    companion object {
        object GENERATING_TX : ProgressTracker.Step("Generating node transaction")
        object SIGNING_TX : ProgressTracker.Step("Signing node transaction")
        object MOVING : ProgressTracker.Step("Moving cash.") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        @JvmStatic
        fun tracker() = ProgressTracker(
                GENERATING_TX,
                SIGNING_TX,
                MOVING
        )
    }

    override val progressTracker: ProgressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {
        logger.info("Starting MoveCash flow...")

        progressTracker.currentStep = GENERATING_TX
        val recipientSession = initiateFlow(recipient)
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        val transactionBuilder = TransactionBuilder(notary = notary)

        logger.info("Generating spend for: ${transactionBuilder.lockId}")
        val (spendTX, keysForSigning) = try {
            Cash.generateSpend(services = serviceHub, tx = transactionBuilder, amount = amount, to = recipient, ourIdentity = ourIdentityAndCert)
        } catch (e: InsufficientBalanceException) {
            throw CashException("Insufficient cash for spend: ${e.message}", e)
        }

        val ledgerTx = transactionBuilder.toLedgerTransaction(serviceHub)
        ledgerTx.inputStates.forEach { logger.info((it as Cash.State).toString()) }
        logger.info(transactionBuilder.toWireTransaction(serviceHub).toString())

        progressTracker.currentStep = SIGNING_TX
        logger.info("Signing transaction for: ${spendTX.lockId}")
        val signedTransaction = serviceHub.signInitialTransaction(spendTX, keysForSigning)

        progressTracker.currentStep = MOVING
        logger.info("Finalising transaction for: ${signedTransaction.id}")
        val sessionsForFinality = if (serviceHub.myInfo.isLegalIdentity(recipient)) emptyList() else listOf(recipientSession)
        return subFlow(FinalityFlow(signedTransaction, sessionsForFinality))
    }
}

@InitiatedBy(MoveCash::class)
class MoveCashReceiverFlow(private val otherSide: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        // Not ideal that we have to do this check, but we must as FinalityFlow does not send locally
        if (!serviceHub.myInfo.isLegalIdentity(otherSide.counterparty)) {
            subFlow(ReceiveFinalityFlow(otherSide))
        }
    }
}
