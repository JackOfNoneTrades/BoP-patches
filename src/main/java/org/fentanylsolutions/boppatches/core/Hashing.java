package org.fentanylsolutions.boppatches.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class Hashing {

    private Hashing() {}

    static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes);
            StringBuilder builder = new StringBuilder(digest.getDigestLength() * 2);
            for (byte value : digest.digest()) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 should always be available", exception);
        }
    }
}
