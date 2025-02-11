package org.starcoin.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.novi.serde.Bytes;
import lombok.SneakyThrows;
import okhttp3.*;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.NotImplementedException;
import org.starcoin.bean.ResourceObj;
import org.starcoin.bean.ScriptFunctionObj;
import org.starcoin.bean.TypeObj;
import org.starcoin.stdlib.Helpers;
import org.starcoin.types.Module;
import org.starcoin.types.*;
import org.starcoin.types.TransactionPayload.ScriptFunction;

import java.io.File;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;


public class StarcoinClient {


    public static final MediaType JSON_MEDIA_TYPE = MediaType.parse(
            "application/json; charset=utf-8");
    private final String baseUrl;
    private final int chaindId;
    private OkHttpClient okHttpClient = new OkHttpClient.Builder().build();

    public StarcoinClient(String url, int chainId) {
        this.baseUrl = url;
        this.chaindId = chainId;
    }

    public StarcoinClient(ChainInfo chainInfo) {

        this.baseUrl = chainInfo.getUrl();
        this.chaindId = chainInfo.getChainId();
    }

    @SneakyThrows
    public String call(String method, List<String> params) {
        JSONObject jsonBody = new JSONObject();
        jsonBody.put("jsonrpc", "2.0");
        jsonBody.put("method", method);
        jsonBody.put("id", UUID.randomUUID().toString());
        jsonBody.put("params", params);
        RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, jsonBody.toString());
        Request request = new Request.Builder().post(body).url(this.baseUrl).build();
        Response response = okHttpClient.newCall(request).execute();
        return response.body().string();
    }


    public String transfer(AccountAddress sender, Ed25519PrivateKey privateKey, AccountAddress to,
                           TypeObj typeObj, BigInteger amount) {
        TransactionPayload payload = buildTransferPayload(to, typeObj, amount);
        return submitTransaction(sender, privateKey, payload);
    }

    private TransactionPayload buildTransferPayload(AccountAddress toAddress, TypeObj typeObj,
                                                    BigInteger amount) {
        return Helpers.encode_peer_to_peer_v2_script_function(typeObj.toTypeTag(),
                toAddress, amount);
    }


    @SneakyThrows
    public String submitHexTransaction(Ed25519PrivateKey privateKey,
                                       RawUserTransaction rawUserTransaction) {
        SignedUserTransaction signedUserTransaction = SignatureUtils.signTxn(privateKey,
                rawUserTransaction);
        List<String> params = Lists.newArrayList(Hex.encode(signedUserTransaction.bcsSerialize()));
        return call("txpool.submit_hex_transaction", params);
    }

    public String callScriptFunction(AccountAddress sender, Ed25519PrivateKey privateKey,
                                     ScriptFunctionObj scriptFunctionObj) {
        ScriptFunction scriptFunction = new ScriptFunction(scriptFunctionObj.toScriptFunction());
        RawUserTransaction rawUserTransaction = buildRawUserTransaction(sender, scriptFunction);
        return submitHexTransaction(privateKey, rawUserTransaction);
    }


    @SneakyThrows
    //  @TODO 链上改了返回结构以后要修改
    public AccountResource getAccountSequence(AccountAddress sender) {
        String path = AccountAddressUtils.hex(
                sender) + "/1/0x00000000000000000000000000000001::Account::Account";
        String rst = call("state.get", Lists.newArrayList(path));
        JSONObject jsonObject = JSON.parseObject(rst);
        List<Byte> result = jsonObject
                .getJSONArray("result")
                .toJavaList(Byte.class);
        Byte[] bytes = result.toArray(new Byte[0]);
        return AccountResource.bcsDeserialize(ArrayUtils.toPrimitive(bytes));
    }

    @SneakyThrows
    private RawUserTransaction buildRawUserTransaction(AccountAddress sender,
                                                       TransactionPayload payload) {
        AccountResource accountResource = getAccountSequence(sender);

        long seqNumber = accountResource.sequence_number;
        ChainId chainId = new ChainId((byte) chaindId);
        return new RawUserTransaction(sender, seqNumber, payload,
                10000000L, 1L, "0x1::STC::STC",
                System.currentTimeMillis() / 1000 + TimeUnit.HOURS.toSeconds(
                        1), chainId);

    }


    //  @TODO
    public String dryRunTransaction(AccountAddress sender, Ed25519PrivateKey privateKey,
                                    TransactionPayload payload) {
        throw new NotImplementedException("");
        //    RawUserTransaction rawUserTransaction = buildRawUserTransaction(sender, payload);
        //    return dryRunHexTransaction(privateKey, rawUserTransaction);
    }

    @SneakyThrows
    private String dryRunHexTransaction(Ed25519PrivateKey privateKey,
                                        RawUserTransaction rawUserTransaction) {
        SignedUserTransaction signedUserTransaction = SignatureUtils.signTxn(privateKey,
                rawUserTransaction);
        List<String> params = Lists.newArrayList(Hex.encode(signedUserTransaction.bcsSerialize()));
        return call("contract.dry_run_raw", params);
    }

    public String submitTransaction(AccountAddress sender, Ed25519PrivateKey privateKey,
                                    TransactionPayload payload) {
        RawUserTransaction rawUserTransaction = buildRawUserTransaction(sender, payload);
        return submitHexTransaction(privateKey, rawUserTransaction);
    }


    @SneakyThrows
    public String deployContractPackage(AccountAddress sender, Ed25519PrivateKey privateKey,
                                        String filePath, ScriptFunctionObj initScriptObj) {

        org.starcoin.types.ScriptFunction sf =
                Objects.isNull(initScriptObj) ? null : initScriptObj.toScriptFunction();
        byte[] contractBytes = Files.toByteArray(new File(filePath));
        Module module = new Module(new Bytes(contractBytes));
        org.starcoin.types.Package contractPackage = new org.starcoin.types.Package(sender,
                Lists.newArrayList(
                        module),
                Optional.ofNullable(sf));
        TransactionPayload.Package.Builder builder = new TransactionPayload.Package.Builder();
        builder.value = contractPackage;
        TransactionPayload payload = builder.build();
        return submitTransaction(sender, privateKey, payload);
    }


    public String getTransactionInfo(String txn) {
        return call("chain.get_transaction_info", Lists.newArrayList(txn));
    }

    public String getResource(AccountAddress sender, ResourceObj resourceObj) {
        return call("contract.get_resource",
                Lists.newArrayList(AccountAddressUtils.hex(sender), resourceObj.toRPCString()));
    }
}
