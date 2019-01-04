package com.r3.corda.finance.cash.issuer.common.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.finance.cash.issuer.common.contracts.BankAccountContract
import com.r3.corda.finance.cash.issuer.common.types.BankAccount
import com.r3.corda.finance.cash.issuer.common.types.toState
import com.r3.corda.finance.cash.issuer.common.utilities.getBankAccountStateByAccountNumber
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

/**
 * Adds a new [BankAccount] state to the ledger.
 * The state is alwats added as a "uni-lateral state" to the node calling this flow.
 */
@StartableByRPC
@StartableByService
@InitiatingFlow
class AddBankAccount(val bankAccount: BankAccount, val verifier: Party) : FlowLogic<SignedTransaction>() {
    companion object {
        object TX_BUILDING : ProgressTracker.Step("Building a transaction.")
        object TX_SIGNING : ProgressTracker.Step("Signing a transaction.")
        object TX_VERIFICATION : ProgressTracker.Step("Verifying a transaction.")
        object SIGS_GATHERING : ProgressTracker.Step("Gathering a transaction's signatures.") {
            // Wiring up a child progress tracker allows us to see the
            // subflow's progress steps in our flow's progress tracker.
            override fun childProgressTracker() = CollectSignaturesFlow.tracker()
        }

        object VERIFYING_SIGS : ProgressTracker.Step("Verifying a transaction's signatures.")
        object FINALISATION : ProgressTracker.Step("Finalising a transaction.") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        fun tracker() = ProgressTracker(
                TX_BUILDING,
                TX_SIGNING,
                TX_VERIFICATION,
                SIGS_GATHERING,
                VERIFYING_SIGS,
                FINALISATION
        )
    }

    override val progressTracker: ProgressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {
        logger.info("Starting AddBankAccount flow...")
        val accountNumber = bankAccount.accountNumber

        logger.info("Checking for existence of state for $bankAccount.")
        val result = getBankAccountStateByAccountNumber(accountNumber, serviceHub)

        if (result != null) {
            val linearId = result.state.data.linearId
            throw IllegalArgumentException("Bank account $accountNumber already exists with linearId ($linearId).")
        }

        logger.info("No state for $bankAccount. Adding it.")
        progressTracker.currentStep = TX_BUILDING
        val bankAccountState = bankAccount.toState(ourIdentity, verifier)
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        val verifierSsession = initiateFlow(bankAccountState.verifier)

        // The node running this flow is always the only signer.
        val command = Command(BankAccountContract.Add(), listOf(ourIdentity.owningKey))
        val outputStateAndContract = StateAndContract(bankAccountState, BankAccountContract.CONTRACT_ID)
        val unsignedTransaction = TransactionBuilder(notary = notary).withItems(command, outputStateAndContract)

        progressTracker.currentStep = TX_SIGNING
        val partiallySignedTransaction = serviceHub.signInitialTransaction(unsignedTransaction)

        progressTracker.currentStep = TX_VERIFICATION
        partiallySignedTransaction.verify(serviceHub)

        progressTracker.currentStep = SIGS_GATHERING
        val sessionsForFinality = if (serviceHub.myInfo.isLegalIdentity(bankAccountState.verifier)) emptyList() else listOf(verifierSsession)
        val fullySignedTx = subFlow(CollectSignaturesFlow(partiallySignedTransaction, sessionsForFinality, SIGS_GATHERING.childProgressTracker()))

        progressTracker.currentStep = VERIFYING_SIGS
        fullySignedTx.verifyRequiredSignatures()

        progressTracker.currentStep = FINALISATION
        // Share the added bank account state with the verifier/issuer.
        return subFlow(FinalityFlow(fullySignedTx, sessionsForFinality, FINALISATION.childProgressTracker()))
    }

}