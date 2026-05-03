package com.poliproger.endpointchecker;

import org.ietf.jgss.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.Subject;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import java.util.*;
import java.util.concurrent.CompletionException;

/**
 * JAAS-based Kerberos helper.
 * Logs in using a keytab and creates a GSSCredential for use with
 * Apache HttpClient 5 SPNEGO authentication.
 */
public final class KerberosHelper {

    private static final Logger log = LoggerFactory.getLogger(KerberosHelper.class);

    // SPNEGO OID: 1.3.6.1.5.5.2
    private static final Oid SPNEGO_OID;
    // Kerberos 5 OID: 1.2.840.113554.1.2.2
    private static final Oid KRB5_OID;

    static {
        try {
            SPNEGO_OID = new Oid("1.3.6.1.5.5.2");
            KRB5_OID   = new Oid("1.2.840.113554.1.2.2");
        } catch (GSSException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private KerberosHelper() {}

    /**
     * Performs JAAS login using the given keytab and principal.
     * Returns the authenticated {@link Subject} whose credentials can be used
     * to create a {@link GSSCredential}.
     */
    public static Subject loginWithKeytab(String principal, String keytabPath) throws LoginException {
        log.info("Kerberos login: principal={}, keytab={}", principal, keytabPath);

        Configuration config = new Configuration() {
            @Override
            public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
                Map<String, Object> opts = new HashMap<>();
                opts.put("useKeyTab",         "true");
                opts.put("keyTab",             keytabPath);
                opts.put("principal",          principal);
                opts.put("storeKey",           "true");
                opts.put("isInitiator",        "true");
                opts.put("refreshKrb5Config",  "true");
                opts.put("doNotPrompt",        "true");
                return new AppConfigurationEntry[]{
                        new AppConfigurationEntry(
                                "com.sun.security.auth.module.Krb5LoginModule",
                                AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                                opts)
                };
            }
        };

        LoginContext lc = new LoginContext("KerberosLogin", null, null, config);
        lc.login();
        log.info("Kerberos login successful for {}", principal);
        return lc.getSubject();
    }

    /**
     * Creates a SPNEGO/Kerberos {@link GSSCredential} from an already-authenticated Subject.
     * Must be called after {@link #loginWithKeytab}.
     */
    public static GSSCredential createGssCredential(Subject subject, String principal) throws Exception {
        GSSManager manager = GSSManager.getInstance();
        try {
            return Subject.callAs(subject, () -> {
                GSSName gssName = manager.createName(principal, GSSName.NT_USER_NAME);
                return manager.createCredential(
                        gssName,
                        GSSCredential.DEFAULT_LIFETIME,
                        new Oid[]{SPNEGO_OID, KRB5_OID},
                        GSSCredential.INITIATE_ONLY
                );
            });
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            throw (cause instanceof Exception ex) ? ex : new Exception(cause);
        }
    }
}
