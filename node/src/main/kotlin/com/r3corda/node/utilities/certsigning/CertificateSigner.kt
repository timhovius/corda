package com.r3corda.node.utilities.certsigning

import com.r3corda.core.crypto.X509Utilities
import com.r3corda.core.crypto.X509Utilities.CORDA_CLIENT_CA
import com.r3corda.core.crypto.X509Utilities.CORDA_CLIENT_CA_PRIVATE_KEY
import com.r3corda.core.crypto.X509Utilities.CORDA_ROOT_CA
import com.r3corda.core.crypto.X509Utilities.addOrReplaceCertificate
import com.r3corda.core.crypto.X509Utilities.addOrReplaceKey
import com.r3corda.core.div
import com.r3corda.core.minutes
import com.r3corda.core.utilities.loggerFor
import com.r3corda.node.services.config.FullNodeConfiguration
import com.r3corda.node.services.config.NodeConfiguration
import joptsimple.OptionParser
import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyPair
import java.security.cert.Certificate
import kotlin.system.exitProcess

/**
 * This check the [certificatePath] for certificates required to connect to the Corda network.
 * If the certificates are not found, a [PKCS10CertificationRequest] will be submitted to Corda network permissioning server using [CertificateSigningService].
 * This process will enter a slow polling loop until the request has been approved, and then
 * the certificate chain will be downloaded and stored in [KeyStore] reside in [certificatePath].
 */
class CertificateSigner(val config: NodeConfiguration, val certService: CertificateSigningService) {
    companion object {
        val pollInterval = 1.minutes
        val log = loggerFor<CertificateSigner>()
    }

    fun buildKeyStore() {
        Files.createDirectories(config.certificatesPath)

        val caKeyStore = X509Utilities.loadOrCreateKeyStore(config.keyStorePath, config.keyStorePassword)

        if (!caKeyStore.containsAlias(CORDA_CLIENT_CA)) {
            // No certificate found in key store, create certificate signing request and post request to signing server.
            log.info("No certificate found in key store, creating certificate signing request...")

            // Create or load key pair from the key store.
            val keyPair = X509Utilities.loadOrCreateKeyPairFromKeyStore(config.keyStorePath, config.keyStorePassword,
                    config.keyStorePassword, CORDA_CLIENT_CA_PRIVATE_KEY) {
                X509Utilities.createSelfSignedCACert(config.myLegalName)
            }
            log.info("Submitting certificate signing request to Corda certificate signing server.")
            val requestId = submitCertificateSigningRequest(keyPair)
            log.info("Successfully submitted request to Corda certificate signing server, request ID : $requestId")
            log.info("Start polling server for certificate signing approval.")
            val certificates = pollServerForCertificates(requestId)
            log.info("Certificate signing request approved, installing new certificates.")

            // Save private key and certificate chain to the key store.
            caKeyStore.addOrReplaceKey(CORDA_CLIENT_CA_PRIVATE_KEY, keyPair.private,
                    config.keyStorePassword.toCharArray(), certificates)

            // Assumes certificate chain always starts with client certificate and end with root certificate.
            caKeyStore.addOrReplaceCertificate(CORDA_CLIENT_CA, certificates.first())

            X509Utilities.saveKeyStore(caKeyStore, config.keyStorePath, config.keyStorePassword)

            // Save certificates to trust store.
            val trustStore = X509Utilities.loadOrCreateKeyStore(config.trustStorePath, config.trustStorePassword)

            // Assumes certificate chain always starts with client certificate and end with root certificate.
            trustStore.addOrReplaceCertificate(CORDA_ROOT_CA, certificates.last())

            X509Utilities.saveKeyStore(trustStore, config.trustStorePath, config.trustStorePassword)
        } else {
            log.trace("Certificate already exists, exiting certificate signer...")
        }
    }

    /**
     * Poll Certificate Signing Server for approved certificate,
     * enter a slow polling loop if server return null.
     * @param requestId Certificate signing request ID.
     * @return Map of certificate chain.
     */
    private fun pollServerForCertificates(requestId: String): Array<Certificate> {
        // Poll server to download the signed certificate once request has been approved.
        var certificates = certService.retrieveCertificates(requestId)

        while (certificates == null) {
            Thread.sleep(pollInterval.toMillis())
            certificates = certService.retrieveCertificates(requestId)
        }
        return certificates
    }

    /**
     * Submit Certificate Signing Request to Certificate signing service if request ID not found in file system
     * New request ID will be stored in requestId.txt
     * @param keyPair Public Private key pair generated for SSL certification.
     * @return Request ID return from the server.
     */
    private fun submitCertificateSigningRequest(keyPair: KeyPair): String {
        val requestIdStore = config.certificatesPath / "certificate-request-id.txt"
        // Retrieve request id from file if exists, else post a request to server.
        return if (!Files.exists(requestIdStore)) {
            val request = X509Utilities.createCertificateSigningRequest(config.myLegalName, config.nearestCity, config.emailAddress, keyPair)
            // Post request to signing server via http.
            val requestId = certService.submitRequest(request)
            // Persists request ID to file in case of node shutdown.
            Files.write(requestIdStore, listOf(requestId), Charsets.UTF_8)
            requestId
        } else {
            Files.readAllLines(requestIdStore).first()
        }
    }
}

object ParamsSpec {
    val parser = OptionParser()
    val baseDirectoryArg = parser.accepts("base-dir", "The directory to put all key stores under").withRequiredArg()
    val configFileArg = parser.accepts("config-file", "The path to the config file").withRequiredArg()
}

fun main(args: Array<String>) {
    val cmdlineOptions = try {
        ParamsSpec.parser.parse(*args)
    } catch (ex: Exception) {
        CertificateSigner.log.error("Unable to parse args", ex)
        exitProcess(1)
    }
    val baseDirectoryPath = Paths.get(cmdlineOptions.valueOf(ParamsSpec.baseDirectoryArg) ?: throw IllegalArgumentException("Please provide Corda node base directory path"))
    val configFile = if (cmdlineOptions.has(ParamsSpec.configFileArg)) Paths.get(cmdlineOptions.valueOf(ParamsSpec.configFileArg)) else null
    val conf = FullNodeConfiguration(NodeConfiguration.loadConfig(baseDirectoryPath, configFile, allowMissingConfig = true))
    // TODO: Use HTTPS instead
    CertificateSigner(conf, HTTPCertificateSigningService(conf.certificateSigningService)).buildKeyStore()
}
