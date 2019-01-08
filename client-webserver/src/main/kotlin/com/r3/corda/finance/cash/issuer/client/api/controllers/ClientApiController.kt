package com.r3.corda.finance.cash.issuer.client.api.controllers

import com.r3.corda.finance.cash.issuer.client.flows.RedeemCash
import com.r3.corda.finance.cash.issuer.common.flows.AddBankAccountFlow.AddBankAccount
import com.r3.corda.finance.cash.issuer.common.flows.MoveCash
import com.r3.corda.finance.cash.issuer.common.states.BankAccountState
import com.r3.corda.finance.cash.issuer.common.types.BankAccount
import com.r3.corda.finance.cash.issuer.client.api.model.CashUIModel
import com.r3.corda.finance.cash.issuer.client.api.model.toUiModel
import com.r3.corda.finance.cash.issuer.client.helpers.getBigDecimalFromLong
import net.corda.core.contracts.Amount
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.messaging.startTrackedFlow
import net.corda.core.messaging.vaultQueryBy
import net.corda.core.node.services.IdentityService
import net.corda.core.node.services.Vault
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.loggerFor
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.contracts.getCashBalance
import net.corda.finance.contracts.getCashBalances
import net.corda.server.NodeRPCConnection
import org.slf4j.Logger
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.util.*

val CLIENT_NAMES = listOf("Notary", "Network Map Service")

// This API is accessible from /api. All paths specified below are relative to it.
@RestController
@RequestMapping(value= ["/api"]) // The paths for GET and POST requests are relative to this base path.
class ClientApiController(
        private val rpc: NodeRPCConnection,
        private val template: SimpMessagingTemplate) {

    private val proxy = rpc.proxy
    private val myLegalName: CordaX500Name = proxy.nodeInfo().legalIdentities.first().name

    companion object {
        private val logger: Logger = loggerFor<ClientApiController>()
    }

    /**
     * Returns the node's name.
     */
    @GetMapping("/whoami")
    fun whoami() = mapOf("me" to myLegalName)

    /**
     * Returns all parties registered with the [NetworkMapService]. These names can be used to look up identities
     * using the [IdentityService].
     */
    @GetMapping("/peers")
    fun getPeers(): Map<String, List<CordaX500Name>> {
        val nodeInfo = proxy.networkMapSnapshot()
        return mapOf("peers" to nodeInfo
                .map { it.legalIdentities.first().name }
                //filter out myself, notary and eventual network map started by driver
                .filter { it.organisation !in (CLIENT_NAMES + myLegalName.organisation) })
    }

    /**
     * Returns all bank acoounts.
     * Accessible at /api/bank-accounts
     */
    @GetMapping("/bank-accounts")
    fun getBankAccounts() = proxy.vaultQueryBy<BankAccountState>().states.map { it.state.data.toUiModel() }

    /**
     * Returns current unspented cash.
     * Accessible at /api/cash
     */
    @GetMapping("/cash")
    fun cash() : List<CashUIModel> {
        return proxy.vaultQueryBy<Cash.State>(QueryCriteria.VaultQueryCriteria(status = Vault.StateStatus.UNCONSUMED)).states.map { it.state.data.toUiModel() }
    }

    /**
     * Returns current cash balance for given Currency (ISO Code)
     * Accessible at /api/service/cash/sum/{iso}
     */
    @GetMapping("/cash/sum/{iso}", produces = ["text/plain"])
    fun cashSum(@PathVariable("iso") iso: String) : Long {
        return proxy.getCashBalance(Currency.getInstance(iso)).quantity
    }


    /**
     * Returns current cash balances.
     * Accessible at /api/cash-balances
     */
    @GetMapping("/cash-balances")
    fun getCashBalances() : Map<String, BigDecimal> {
        val cashBalances: Map<Currency, Amount<Currency>> = proxy.getCashBalances()
        val resultMap = mutableMapOf<String, BigDecimal>()
        cashBalances.map { (k,v) -> resultMap.put(k.toString(), getBigDecimalFromLong(v.quantity)) }
        return resultMap
    }

    /**
     * Moves amount of cash balance to another bank account.
     * Accessible at /api/client/move-cash
     */
    @PutMapping("/move-cash")
    fun moveCash(@RequestParam("recipient") recipient: Party, @RequestParam("amount") amount: Amount<Currency>): ResponseEntity<String> {
        return try {
            val signedTx = proxy.startTrackedFlow(::MoveCash, recipient, amount).returnValue.getOrThrow()
            ResponseEntity.status(HttpStatus.CREATED).body("Transaction id ${signedTx.id} committed to ledger.\n Wire Transaction: ${signedTx.tx} \n")
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            ResponseEntity.badRequest().eTag(ex.message!!).build()
        }
    }

    @PutMapping("/reedem-cash")
    fun reedemCash(@RequestParam("amount") amount: Amount<Currency>, @RequestParam("issuer") issuer: Party): ResponseEntity<String> {
        return try {
            proxy.startTrackedFlow(::RedeemCash, amount, issuer).returnValue.getOrThrow()
            ResponseEntity.status(HttpStatus.CREATED).body("Cash Reedem request committed to ledger.\n")
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            ResponseEntity.badRequest().eTag(ex.message!!).build()
        }
    }

        /**
     * Initiates a flow to verify a bank account.
     * flow start AddBankAccount bankAccount: { accountId: "12345", accountName: "Roger's Account",
     * accountNumber: { sortCode: "442200" , accountNumber: "13371337", type: "uk" }, currency: "GBP" }, verifier: Issuer
     */
    @PutMapping("/add-account")
    fun addBankAccount(@RequestBody bankAccount: BankAccount, @RequestParam("verifier") verifier: Party): ResponseEntity<String> {
        return try {
            val signedTx = proxy.startTrackedFlow(::AddBankAccount, bankAccount, verifier).returnValue.getOrThrow()
            ResponseEntity.status(HttpStatus.CREATED).body("Transaction id ${signedTx.id} committed to ledger.\n")
        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            ResponseEntity.badRequest().eTag(ex.message!!).build()
        }
    }

}