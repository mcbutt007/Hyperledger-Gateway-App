package com.example.myapplication;

import android.content.Context;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import org.hyperledger.fabric.client.CommitException;
import org.hyperledger.fabric.client.CommitStatusException;
import org.hyperledger.fabric.client.Contract;
import org.hyperledger.fabric.client.EndorseException;
import org.hyperledger.fabric.client.Gateway;
import org.hyperledger.fabric.client.GatewayException;
import org.hyperledger.fabric.client.SubmitException;
import org.hyperledger.fabric.client.identity.Identities;
import org.hyperledger.fabric.client.identity.Identity;
import org.hyperledger.fabric.client.identity.Signer;
import org.hyperledger.fabric.client.identity.Signers;
import org.hyperledger.fabric.client.identity.X509Identity;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.InvalidKeyException;
import java.security.cert.CertificateException;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.TimeUnit;
public class MyGatewayHandler {
    private static final String MSP_ID = "Org1MSP";
    private static final String CHANNEL_NAME = "mychannel";
    private static final String CHAINCODE_NAME = "basic";

    private static final Path CRYPTO_PATH = Paths.get("crypto");
    private static final Path CERT_DIR_PATH = CRYPTO_PATH.resolve(Paths.get("users/User1@org1.example.com/msp/signcerts/cert.pem"));
    private static final Path KEY_DIR_PATH = CRYPTO_PATH.resolve(Paths.get("users/User1@org1.example.com/msp/keystore/a633924bdd7104929921492b759a790c5e8ba25367becd87cf1c1667bb68b36e_sk"));
    private static final Path TLS_CERT_PATH = CRYPTO_PATH.resolve(Paths.get("peers/peer0.org1.example.com/tls/ca.crt"));

    private static final String PEER_ENDPOINT = "192.168.1.207:7051";
    private static final String OVERRIDE_AUTH = "peer0.org1.example.com";

    private Contract contract;
    private final String virusID = String.valueOf(Instant.now().toEpochMilli());
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private static final String TAG = "Hyperledger";
    private Context context;

    private TextView displayTextView;


    public void handleGateway(Context context, TextView displayTextView) throws Exception {
        this.displayTextView = displayTextView;
        this.context = context;
        ManagedChannel channel = newGrpcConnection();

        Gateway.Builder builder = Gateway.newInstance().identity(newIdentity()).signer(newSigner()).connection(channel)
                .evaluateOptions(options -> options.withDeadlineAfter(5, TimeUnit.SECONDS))
                .endorseOptions(options -> options.withDeadlineAfter(15, TimeUnit.SECONDS))
                .submitOptions(options -> options.withDeadlineAfter(5, TimeUnit.SECONDS))
                .commitStatusOptions(options -> options.withDeadlineAfter(1, TimeUnit.MINUTES));

        try (Gateway gateway = builder.connect()) {
            HyperledgerGateway hyperledgerGateway = new HyperledgerGateway(gateway);
            hyperledgerGateway.run();
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
    private ManagedChannel newGrpcConnection() throws IOException {
        var credentials = TlsChannelCredentials.newBuilder()
                .trustManager(convertStringToInputStream(context.getString(R.string.tls_cert)))
                .build();
        return Grpc.newChannelBuilder(PEER_ENDPOINT, credentials)
                .overrideAuthority(OVERRIDE_AUTH)
                .build();
    }

    private Identity newIdentity() throws IOException, CertificateException {
        try (var certReader = convertStringToBufferedReader(context.getString(R.string.cert))) {
            var certificate = Identities.readX509Certificate(certReader);
            return new X509Identity(MSP_ID, certificate);
        }
    }

    private Signer newSigner() throws IOException, InvalidKeyException {
        try (var keyReader = convertStringToBufferedReader(context.getString(R.string.key))) {
            var privateKey = Identities.readPrivateKey(keyReader);
            return Signers.newPrivateKeySigner(privateKey);
        }
    }

    private Path getFirstFilePath(Path dirPath) throws IOException {
        try (java.util.stream.Stream<Path> keyFiles = Files.list(dirPath)) {
            return keyFiles.findFirst()
                    .orElseThrow(() -> new IOException("Directory is empty"));
        }
    }

    private class HyperledgerGateway implements Runnable {

        public HyperledgerGateway(Gateway gateway) {
            var network = gateway.getNetwork(CHANNEL_NAME);
            contract = network.getContract(CHAINCODE_NAME);
        }


        @Override
        public void run() {
            try {
                // Your code to be run in the background thread
                initLedger();
                GetAllSignatures();
                UploadSignature();
                GetSignature();
                updateNonExistentSignature();
            } catch (GatewayException | CommitException e) {
                Log.e("BackgroundTask", "Error occurred", e);
            }
        }
        private void initLedger() throws EndorseException, SubmitException, CommitStatusException, CommitException {
            displayTextView.append("\n--> Submit Transaction: InitLedger, function creates the initial set of virus signatures on the ledger\n");

            contract.submitTransaction("InitLedger");

            displayTextView.append("*** Transaction committed successfully\n");
        }
        private void GetAllSignatures() throws GatewayException {
            displayTextView.append("\n--> Evaluate Transaction: GetAllSignatures, function returns all the current virus signatures on the ledger\n");

            var result = contract.evaluateTransaction("GetAllSignatures");

            displayTextView.append("*** Result: " + prettyJson(result) + "\n");
        }
        private void UploadSignature() throws EndorseException, SubmitException, CommitStatusException, CommitException {
            displayTextView.append("\n--> Submit Transaction: CreateSignature, creates new virus signature with IPFS hash, virusID, Organization and virus name arguments\n");

            contract.submitTransaction("UploadSignature", genIPFSHash(), virusID, "Org1", "virus " + virusID);

            displayTextView.append("*** Transaction committed successfully\n");
        }
        private void GetSignature() throws GatewayException {
            displayTextView.append("\n--> Evaluate Transaction: ReadSignature, function returns virus signature attributes\n");

            var evaluateResult = contract.evaluateTransaction("GetSignature", virusID);

            displayTextView.append("*** Result:" + prettyJson(evaluateResult) + "\n");
        }

        private void updateNonExistentSignature() {
            try {
                displayTextView.append("\n--> Submit Transaction: UpdateSignature virus signature 1412, virus signature 1412 does not exist and should return an error\n");

                contract.submitTransaction("UpdateSignature", genIPFSHash(), "1412", "Org2", "Modifiedvirus3");

                displayTextView.append("******** FAILED to return an error\n");
            } catch (EndorseException | SubmitException | CommitStatusException e) {
                displayTextView.append("*** Successfully caught the error: \n");
                e.printStackTrace(System.out);
                displayTextView.append("Transaction ID: " + e.getTransactionId() + "\n");

                var details = e.getDetails();
                if (!details.isEmpty()) {
                    displayTextView.append("Error Details:\n");
                    for (var detail : details) {
                        displayTextView.append("- address: " + detail.getAddress() + ", mspId: " + detail.getMspId()
                                + ", message: " + detail.getMessage() + "\n");
                    }
                }
            } catch (CommitException e) {
                displayTextView.append("*** Successfully caught the error: " + e + "\n");
                e.printStackTrace(System.out);
                displayTextView.append("Transaction ID: " + e.getTransactionId() + "\n");
                displayTextView.append("Status code: " + e.getCode() + "\n");
            }
        }
    }

    private String prettyJson(final byte[] json) {
        return prettyJson(new String(json, StandardCharsets.UTF_8));
    }

    private String prettyJson(final String json) {
        var parsedJson = JsonParser.parseString(json);
        return gson.toJson(parsedJson);
    }

    public static BufferedReader convertStringToBufferedReader(String inputString) {
        try {
            // Convert string to InputStream
            InputStream inputStream = new ByteArrayInputStream(inputString.getBytes());

            // Wrap InputStream in InputStreamReader
            InputStreamReader inputStreamReader = new InputStreamReader(inputStream);

            // Wrap InputStreamReader in BufferedReader

            // Return the created BufferedReader
            return new BufferedReader(inputStreamReader);
        } catch (Exception e) {
            // Handle any exceptions
            e.printStackTrace();
            return null;
        }
    }
    public static InputStream convertStringToInputStream(String text) {
        // Convert the string to bytes
        byte[] bytes = text.getBytes();

        // Create a ByteArrayInputStream from the bytes
        return new ByteArrayInputStream(bytes);
    }
    public String genIPFSHash() {
        int leftLimit = 48; // numeral '0'
        int rightLimit = 122; // letter 'z'
        int targetStringLength = 32;
        Random random = new Random();

        return random.ints(leftLimit, rightLimit + 1)
                .filter(i -> (i <= 57 || i >= 65) && (i <= 90 || i >= 97))
                .limit(targetStringLength)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }
}
