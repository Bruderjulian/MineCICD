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
     * H-04: restricts protocols to TLSv1.2/1.3, zeroes password array after use, warns if keystore is world-readable.
     */
    public static SSLContext build(String keystorePath, String password, boolean enabled) {
        if (!enabled) {
            return null;
        }
        if (keystorePath == null || keystorePath.isBlank() || password == null) {
            throw new IllegalArgumentException("control.tls.enabled requires keystore and password");
        }
        // H-04: warn if keystore file is world-readable
        try {
            java.nio.file.Path p = java.nio.file.Path.of(keystorePath);
            if (java.nio.file.Files.exists(p)) {
                try {
                    java.util.Set<java.nio.file.attribute.PosixFilePermission> perms = java.nio.file.Files.getPosixFilePermissions(p);
                    if (perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ) || perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ)) {
                        System.err.println("[MineCICD] Warning: keystore " + keystorePath + " is world-readable; run chmod 600");
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        char[] pass = password.toCharArray();
        try {
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            try (InputStream in = new FileInputStream(keystorePath)) {
                keyStore.load(in, pass);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, pass);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(kmf.getKeyManagers(), null, null);
            // Protocol restriction to TLSv1.2/1.3 is enforced in ControlServer's HttpsConfigurator
            return context;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to configure TLS: " + e.getMessage(), e);
        } finally {
            java.util.Arrays.fill(pass, '\0');
        }
    }
}