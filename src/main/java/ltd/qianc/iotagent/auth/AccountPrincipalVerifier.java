package ltd.qianc.iotagent.auth;

/** Verifies the existing customer login token and returns the stable upstream principal. */
public interface AccountPrincipalVerifier {
    PrincipalInfo verify(PrincipalInfo presented);
}
