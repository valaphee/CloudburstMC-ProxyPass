package org.cloudburstmc.proxypass.network.bedrock.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.google.common.base.Preconditions;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.factories.DefaultJWSVerifierFactory;
import com.nimbusds.jose.shaded.json.JSONArray;
import com.nimbusds.jose.shaded.json.JSONObject;
import com.nimbusds.jose.shaded.json.JSONValue;
import com.nimbusds.jwt.SignedJWT;
import com.valaphee.synergy.proxy.mcbe.auth.DefaultAuth;
import io.netty.util.AsciiString;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.cloudburstmc.proxypass.ProxyPass;
import org.cloudburstmc.proxypass.network.bedrock.util.ForgeryUtils;

import java.security.interfaces.ECPublicKey;
import java.text.ParseException;
import java.util.List;
import java.util.UUID;

import static org.cloudburstmc.protocol.common.util.Preconditions.checkArgument;

@Log4j2
@RequiredArgsConstructor
public class UpstreamPacketHandler implements BedrockPacketHandler {

    private final ProxyServerSession session;
    private final ProxyPass proxy;
    private JSONObject skinData;
    private JSONObject extraData;
    private List<SignedJWT> chainData;
    private AuthData authData;
    private ProxyPlayerSession player;

    private static boolean validateChainData(List<SignedJWT> chain) throws Exception {
        ECPublicKey lastKey = null;
        boolean validChain = false;
        for (SignedJWT jwt : chain) {
            if (!validChain) {
                validChain = verifyJwt(jwt, EncryptionUtils.getMojangPublicKey());
            }

            if (lastKey != null) {
                verifyJwt(jwt, lastKey);
            }

            JsonNode payloadNode = ProxyPass.JSON_MAPPER.readTree(jwt.getPayload().toString());
            JsonNode ipkNode = payloadNode.get("identityPublicKey");
            Preconditions.checkState(ipkNode != null && ipkNode.getNodeType() == JsonNodeType.STRING, "identityPublicKey node is missing in chain");
            lastKey = EncryptionUtils.generateKey(ipkNode.asText());
        }
        return validChain;
    }

    private static boolean verifyJwt(JWSObject jwt, ECPublicKey key) throws JOSEException {
        return jwt.verify(new DefaultJWSVerifierFactory().createJWSVerifier(jwt.getHeader(), key));
    }

    @Override
    public PacketSignal handle(RequestNetworkSettingsPacket packet) {
        int protocolVersion = packet.getProtocolVersion();

        if (protocolVersion != ProxyPass.PROTOCOL_VERSION) {
            PlayStatusPacket status = new PlayStatusPacket();
            if (protocolVersion > ProxyPass.PROTOCOL_VERSION) {
                status.setStatus(PlayStatusPacket.Status.LOGIN_FAILED_SERVER_OLD);
            } else {
                status.setStatus(PlayStatusPacket.Status.LOGIN_FAILED_CLIENT_OLD);
            }

            session.sendPacketImmediately(status);
            return PacketSignal.HANDLED;
        }
        session.setCodec(ProxyPass.CODEC);

        NetworkSettingsPacket networkSettingsPacket = new NetworkSettingsPacket();
        networkSettingsPacket.setCompressionThreshold(0);
        networkSettingsPacket.setCompressionAlgorithm(PacketCompressionAlgorithm.ZLIB);

        session.sendPacketImmediately(networkSettingsPacket);
        session.setCompression(PacketCompressionAlgorithm.ZLIB);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(LoginPacket packet) {
        boolean validChain;
        try {
            validChain = validateChainData(packet.getChain());

            log.debug("Is player data valid? {}", validChain);
            SignedJWT jwt = packet.getChain().get(packet.getChain().size() - 1);
            JsonNode payload = ProxyPass.JSON_MAPPER.readTree(jwt.getPayload().toBytes());

            if (payload.get("extraData").getNodeType() != JsonNodeType.OBJECT) {
                throw new RuntimeException("AuthData was not found!");
            }

            extraData = (JSONObject) jwt.getPayload().toJSONObject().get("extraData");

            this.authData = new AuthData(extraData.getAsString("displayName"),
                    UUID.fromString(extraData.getAsString("identity")), extraData.getAsString("XUID"));

            if (payload.get("identityPublicKey").getNodeType() != JsonNodeType.STRING) {
                throw new RuntimeException("Identity Public Key was not found!");
            }
            ECPublicKey identityPublicKey = EncryptionUtils.generateKey(payload.get("identityPublicKey").textValue());

            JWSObject clientJwt = packet.getExtra();
            verifyJwt(clientJwt, identityPublicKey);

            skinData = new JSONObject(clientJwt.getPayload().toJSONObject());
            chainData = packet.getChain();
            initializeProxySession();
        } catch (Exception e) {
            session.disconnect("disconnectionScreen.internalError.cantConnect");
            throw new RuntimeException("Unable to complete login", e);
        }
        return PacketSignal.HANDLED;
    }

    private void initializeProxySession() {
        log.debug("Initializing proxy session");
        this.proxy.newClient(this.proxy.getTargetAddress(), downstream -> {
            downstream.setCodec(ProxyPass.CODEC);
            downstream.setSendSession(this.session);
            this.session.setSendSession(downstream);

            ProxyPlayerSession proxySession = new ProxyPlayerSession(this.session, downstream, this.proxy, this.authData);
            this.player = proxySession;

            downstream.setPlayer(proxySession);
            this.session.setPlayer(proxySession);

            try {
                JWSObject jwt = chainData.get(chainData.size() - 1);
                JsonNode payload = ProxyPass.JSON_MAPPER.readTree(jwt.getPayload().toBytes());
                player.getLogger().saveJson("chainData", payload);
                player.getLogger().saveJson("skinData", this.skinData);
            } catch (Exception e) {
                log.error("JSON output error: " + e.getMessage(), e);
            }
            skinData.put("DeviceOS", 1);
            SignedJWT skinData = ForgeryUtils.forgeSkinData(proxySession.getProxyKeyPair(), this.skinData);

            LoginPacket login = new LoginPacket();
            DefaultAuth auth = new DefaultAuth(proxySession.getProxyKeyPair());
            auth.setVersion(ProxyPass.MINECRAFT_VERSION);

            Object json = JSONValue.parse(AsciiString.of(auth.getJws()).array());
            checkArgument(json instanceof JSONObject && ((JSONObject) json).containsKey("chain"), "Invalid login chain");
            Object chain = ((JSONObject) json).get("chain");
            checkArgument(chain instanceof JSONArray, "Expected JSON array for login chain");

            try {
                for (Object node : (JSONArray) chain) {
                    checkArgument(node instanceof String, "Expected String in login chain");
                    login.getChain().add(SignedJWT.parse((String) node));
                }
            } catch (ParseException e) {
                throw new IllegalArgumentException("Unable to decode JWT in login chain", e);
            }

            login.setExtra(skinData);
            login.setProtocolVersion(ProxyPass.PROTOCOL_VERSION);

            downstream.setPacketHandler(new DownstreamInitialPacketHandler(downstream, proxySession, this.proxy, login));
            downstream.setLogging(true);

            RequestNetworkSettingsPacket packet = new RequestNetworkSettingsPacket();
            packet.setProtocolVersion(ProxyPass.PROTOCOL_VERSION);
            downstream.sendPacketImmediately(packet);

            //SkinUtils.saveSkin(proxySession, this.skinData);
        });
    }

}
