package com.lemonlightmc.minecicd.http;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;

public final class ControlTls {

    private ControlTls() {
    }

    /**
     * Builds an SSLContext from a keystore (JKS or PKCS12). Returns null if TLS is disabled.
     */
    public static SSLContext build(String keystorePath, String password, boolean enabled) {
        if (!enabled) {
            return null;
        }
        if (keystorePath == null || keystorePath.isBlank() || password == null) {
            throw new IllegalArgumentException("control.tls.enabled requires keystore and password");
        }
        try {
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            char[] pass = password.toCharArray();
            try (InputStream in = new FileInputStream(keystorePath)) {
                keyStore.load(in, pass);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, pass);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(kmf.getKeyManagers(), null, null);
            return context;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to configure TLS: " + e.getMessage(), e);
        }
    }
}