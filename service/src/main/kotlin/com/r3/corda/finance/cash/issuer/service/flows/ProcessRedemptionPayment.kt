package com.r3.corda.finance.cash.issuer.service.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.finance.cash.issuer.common.contracts.NodeTransactionContract
import com.r3.corda.finance.cash.issuer.common.states.BankAccountState
import com.r3.corda.finance.cash.issuer.common.states.NostroTransactionState
import com.r3.corda.finance.cash.issuer.common.types.NodeTransactionStatus
import com.r3.corda.finance.cash.issuer.common.utilities.getPendingRedemptionByNotes
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.StartableByService
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

@StartableByService
class ProcessRedemptionPayment(val signedTransaction: SignedTransaction) : FlowLogic<Unit>() {
    companion object {
        object GENERATING_TX : ProgressTracker.Step("Generating node transaction")
        object SIGNING_TX : ProgressTracker.Step("Signing node transaction")
        object FINALISING_TX : ProgressTracker.Step("Obtaining notary signature and recording node transaction") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        @JvmStatic
        fun tracker() = ProgressTracker(
                GENERATING_TX,
                SIGNING_TX,
                FINALISING_TX
        )
    }

    override val progressTracker = tracker()

    @Suspendable
    override fun call() {
        logger.info("Starting ProcessRedemptionPayment flow...")
        val counterparty = signedTransaction.tx.toLedgerTransaction(serviceHub).referenceInputRefsOfType<BankAccountState>().single {
            it.state.data.owner != ourIdentity
        }.state.data.owner
        logger.info("Counterparty to redeem to is $counterparty")
        val notes = signedTransaction.tx.outputsOfType<NostroTransactionState>().single().description
        val pendingRedemption = try {
            getPendingRedemptionByNotes(notes, serviceHub)!!
        } catch (e: Throwable) {
            throw IllegalStateException("ERROR!!! Oh no!!! The issuer has made an erroneous redemption payment!")
        }
        val totalRedemptionAmountPending = pendingRedemption.state.data.amountTransfer.quantityDelta
        val transactionAmount = signedTransaction.tx.outputsOfType<NostroTransactionState>().single().amountTransfer.quantityDelta
        logger.info("Total redemption amount pending is $totalRedemptionAmountPending. Tx amount is $transactionAmount")
        logger.info("Total redemption payments")
        require(totalRedemptionAmountPending == transactionAmount) { "The payment must equal the redemption amount requested." }

        progressTracker.currentStep = GENERATING_TX
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        val transactionBuilder = TransactionBuilder(notary = notary)
        transactionBuilder
                .addInputState(pendingRedemption)
                .addOutputState(pendingRedemption.state.data.copy(status = NodeTransactionStatus.COMPLETE), NodeTransactionContract.CONTRACT_ID)
                .addReferenceState(signedTransaction.tx.outRefsOfType<NostroTransactionState>().single().referenced())
                .addCommand(NodeTransactionContract.Update(), listOf(ourIdentity.owningKey))

        progressTracker.currentStep = SIGNING_TX
        val stx = serviceHub.signInitialTransaction(transactionBuilder)

        progressTracker.currentStep = FINALISING_TX
        subFlow(FinalityFlow(stx, emptySet<FlowSession>(), FINALISING_TX.childProgressTracker()))
        logger.info(stx.tx.toString())
    }
}