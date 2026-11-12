package com.ehe.iotagent.admin;

import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Password policy and password verification without sharing Agent credentials or sessions. */
public final class AdminPasswordPolicy {
    public static final int DEFAULT_MINIMUM = 6;
    private static final int MAXIMUM = 1024;
    private static final int ITERATIONS = 210_000;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private AdminPasswordPolicy() {
    }

    public static String validate(String password, int minimum) {
        if (password == null || password.length() < minimum || password.length() > MAXIMUM
                || !password.equals(password.trim()) || password.indexOf('\r') >= 0 || password.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("新密码至少需要 " + minimum + " 个字符，最多1024字符，且不能换行");
        }
        return password;
    }

    public static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] derived = derive(password, salt, ITERATIONS);
        return "pbkdf2-sha256$" + ITERATIONS + "$" + hex(salt) + "$" + hex(derived);
    }

    public static boolean matches(String password, String encoded) {
        try {
            String[] parts = encoded.split("\\$", -1);
            if (parts.length != 4 || !parts[0].equals("pbkdf2-sha256")) {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = unhex(parts[2]);
            byte[] expected = unhex(parts[3]);
            return MessageDigest.isEqual(expected, derive(password, salt, iterations));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("密码策略不可用", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private static byte[] unhex(String value) {
        if ((value.length() & 1) != 0) throw new IllegalArgumentException("invalid hash");
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("invalid hash");
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }
}
