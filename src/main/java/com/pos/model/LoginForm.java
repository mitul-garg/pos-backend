package com.pos.model;

/**
 * Input DTO for {@code POST /api/auth/login} — {@code {tenantCode, username, password}},
 * matching requirements.md section 13.4.
 *
 * <p><b>Deliberately carries no bean-validation annotations, and the handler does not
 * use {@code @Valid}.</b> That is not an oversight, it is the contract. A blank tenant
 * code is a <i>failed login</i> (401, the generic message), not a malformed request
 * (400, "tenantCode must not be blank"). {@code authService.test.js} pins this: "blank
 * tenant code" sits in the same parameterized list as "wrong password", and the two must
 * be indistinguishable.
 *
 * <p>Adding {@code @NotBlank} here would split that list in two and hand a caller a free
 * oracle — 400 means "you got the shape wrong", 401 means "the shape was right and the
 * credentials were not". Blank-versus-wrong is exactly the distinction the uniform 401
 * exists to hide.
 */
public class LoginForm {

    private String tenantCode;
    private String username;
    private String password;

    public String getTenantCode() {
        return tenantCode;
    }

    public void setTenantCode(String tenantCode) {
        this.tenantCode = tenantCode;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
