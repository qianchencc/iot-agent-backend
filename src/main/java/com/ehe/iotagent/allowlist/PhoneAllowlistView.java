package com.ehe.iotagent.allowlist;

import java.util.List;

/** 当前白名单快照；revision 供管理员界面的并发版本校验。 */
public record PhoneAllowlistView(
        long revision,
        List<AllowedPhone> users) {

    public PhoneAllowlistView {
        users = List.copyOf(users);
    }
}
