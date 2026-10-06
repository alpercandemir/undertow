package dev.undertow.service;

import dev.undertow.reporting.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class ServiceSecurity {
    private static final SecureRandom RANDOM = new SecureRandom();

    private ServiceSecurity() {}

    public static String randomToken() {
        byte[] data = new byte[32];
        RANDOM.nextBytes(data);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    public static String digest(String value) {
        return Json.hash(value);
    }

    public static boolean matches(String left, String right) {
        return left != null
                && right != null
                && MessageDigest.isEqual(
                        left.getBytes(StandardCharsets.UTF_8),
                        right.getBytes(StandardCharsets.UTF_8));
    }

    public static void verifyWebhook(byte[] body, String signature, String secret)
            throws Exception {
        if (secret == null
                || secret.isBlank()
                || signature == null
                || !signature.matches("sha256=[a-f0-9]{64}"))
            throw new ServiceException(401, "invalid_signature");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        if (!matches(expected, signature)) throw new ServiceException(401, "invalid_signature");
    }
}
